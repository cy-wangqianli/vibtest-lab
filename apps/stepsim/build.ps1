#requires -Version 5.1
<#
.SYNOPSIS
    手工打包 APK（Windows / PowerShell 版，不需要 Gradle、不需要 bash）。

.DESCRIPTION
    对应原 Linux 脚本 build.sh 的六个阶段，语义一一对应：
      [1/6] aapt package   清单 + res + assets  ->  base.apk（--min-sdk-version / --target-sdk-version）
      [2/6] javac          源码 -> .class（--release 8，classpath = android.jar）
      [3/6] d8             .class -> classes.dex（--min-api 26 --release）
      [4/6] 装 dex         把 classes.dex 写进 base.apk -> unsigned.apk
      [5/6] zipalign       -p -f 4（代替原 align.py：4 字节对齐 + resources.arsc 保持 stored）
      [6/6] apksigner      v1+v2 签名 -> <项目>\build\<ApkName>，并校验签名 / 包名 / 对齐

    SDK 路径、build-tools 版本、platform 版本、JDK 全部自动探测，可用参数覆盖。

    中文路径坑（本机实测）：SDK 里的 aapt.exe / zipalign.exe 是 C++ 工具，用 ANSI argv 打开文件，
    参数中出现非 ASCII 字符就报 "Illegal byte sequence"（android.jar 打不开 -> 满屏找不到 android: 属性）。
    处理办法：脚本自动求出一个 anchor（从它到项目目录的相对路径全为 ASCII），
    Push-Location 到 anchor，所有传给 native 工具的参数都用相对路径。
    Java 系工具（javac / java / apksigner.jar）和 .NET 代码是 Unicode 安全的，不受影响。

.PARAMETER SdkRoot
    Android SDK 根目录。默认自动探测：-SdkRoot -> ANDROID_HOME -> ANDROID_SDK_ROOT
    -> 项目上层目录的 .toolchains\android-sdk、android-sdk -> 常见安装位置。
.PARAMETER BuildTools
    build-tools 版本号（如 35.0.0）。默认取 build-tools 下可用的最高版本。
.PARAMETER Platform
    platforms 目录名（如 android-35，也可只写 35）。默认取可用的最高 API。
.PARAMETER JdkHome
    JDK 根目录。默认 -JdkHome -> JAVA_HOME -> PATH 上的 javac -> D:\tools\jdk* / Program Files 等。
.PARAMETER OutDir
    输出目录。默认 <项目目录>\build（中间产物 base.apk / unsigned.apk / aligned.apk 也在这里）。
.PARAMETER Manifest
    清单文件路径。默认 <项目目录>\AndroidManifest.xml。
    相对路径先按当前工作目录解析，找不到再按项目目录解析。
    注意：build-tools 35 的 aapt.exe 要求清单文件名字面是 AndroidManifest.xml，
    文件名不同时脚本会自动复制成 <OutDir>\manifest\AndroidManifest.xml 再喂给 -M。
.PARAMETER Out
    最终 APK 的完整输出路径（可含文件名）。默认 <OutDir>\<ApkName>。
    相对路径按当前工作目录解析。给同一份源码出多个变体（例如 v2 清单）时用它，
    这样不会覆盖默认产物、也不动 apps/*.apk 成品。
.PARAMETER ApkName
    产物文件名。默认 StepSim.apk。
.PARAMETER Keystore
    签名 keystore 路径；不存在则用 keytool 生成。
    默认 <workspace>\.scratch\keystore\debug.keystore（找不到 .scratch 时退回 <项目目录>\debug.keystore）。
.PARAMETER MinSdk
    aapt --min-sdk-version / d8 --min-api。默认 26。
.PARAMETER TargetSdk
    aapt --target-sdk-version。默认 34（与目标成品一致；compileSdk 由所选 platform 决定）。
.PARAMETER Align
    zipalign 对齐字节数。默认 4。
.PARAMETER RegenIcon
    构建前调用 gen_icon.py 重画 res/drawable/ic_launcher.png。默认不跑（图标已在 res/ 里；
    注意它会改写 res/ 目录，原 build.sh 每次构建都会跑，这里改成显式开关）。
.PARAMETER PythonExe
    -RegenIcon 时使用的 python.exe（本机 PATH 上的 python 是商店占位符，不能直接用）。

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File .\build.ps1 -BuildTools 34.0.0 -Platform android-34 -TargetSdk 34
#>
[CmdletBinding()]
param(
    [string]$SdkRoot,
    [string]$BuildTools,
    [string]$Platform,
    [string]$JdkHome,
    [string]$OutDir,
    [string]$Manifest,
    [string]$Out,
    [string]$ApkName = 'StepSim.apk',
    [string]$Keystore,
    [int]$MinSdk = 26,
    [int]$TargetSdk = 34,
    [int]$Align = 4,
    [switch]$RegenIcon,
    [string]$PythonExe
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$script:StageTimes = [ordered]@{}
$script:TotalWatch = [System.Diagnostics.Stopwatch]::StartNew()

# ----------------------------------------------------------------------------
# 通用工具函数
# ----------------------------------------------------------------------------

# Windows 命令行参数转义（ProcessStartInfo.Arguments 是单字符串，需要自己拼）
function ConvertTo-CommandLineArg([string]$Arg) {
    if ($null -eq $Arg) { return '""' }
    if ($Arg.Length -eq 0) { return '""' }
    if ($Arg -notmatch '[\s"]') { return $Arg }
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append('"')
    $backslashes = 0
    foreach ($ch in $Arg.ToCharArray()) {
        if ($ch -eq '\') {
            $backslashes++
            [void]$sb.Append('\')
        }
        elseif ($ch -eq '"') {
            [void]$sb.Append(('\' * ($backslashes * 2 + 1)))
            [void]$sb.Append('"')
            $backslashes = 0
        }
        else {
            if ($backslashes -gt 0) {
                [void]$sb.Append(('\' * $backslashes))
                $backslashes = 0
            }
            [void]$sb.Append($ch)
        }
    }
    if ($backslashes -gt 0) { [void]$sb.Append(('\' * ($backslashes * 2))) }
    [void]$sb.Append('"')
    return $sb.ToString()
}

# 调用外部程序：用 .NET Process 而不是 & 调用，避免 PS 5.1 把 native stderr 当错误、
# 也便于按需解码 GBK 中文日志并拿到精确退出码/耗时。
function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @(),
        [string]$WorkingDirectory,
        [string]$Label,
        [switch]$Echo
    )
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $FilePath
    if ($WorkingDirectory) { $psi.WorkingDirectory = $WorkingDirectory }
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $psi.StandardErrorEncoding = [System.Text.Encoding]::Default   # javac/keytool 的中文提示是 GBK
    $psi.Arguments = (($Arguments | ForEach-Object { ConvertTo-CommandLineArg $_ }) -join ' ')

    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $proc = [System.Diagnostics.Process]::Start($psi)
    $stdout = $proc.StandardOutput.ReadToEnd()
    $stderr = $proc.StandardError.ReadToEnd()
    $proc.WaitForExit()
    $watch.Stop()

    if ($Echo) {
        if ($stdout.Trim()) { Write-Host $stdout.TrimEnd() }
        if ($stderr.Trim()) { Write-Host ("  [stderr] " + ($stderr.TrimEnd() -replace "`r?`n", "`r`n  ")) }
    }
    return [pscustomobject]@{
        Label     = $Label
        ExitCode  = $proc.ExitCode
        StdOut    = $stdout
        StdErr    = $stderr
        ElapsedMs = [int]$watch.ElapsedMilliseconds
    }
}

function Assert-NativeOk {
    param($Result, [string]$What)
    if ($Result.ExitCode -ne 0) {
        $err = $Result.StdErr.Trim()
        if (-not $err) { $err = $Result.StdOut.Trim() }
        throw "$What 失败（exit=$($Result.ExitCode)）: $err"
    }
}

# 求 anchor：向上走，直到再往上会让「相对路径」出现非 ASCII 为止
function Get-AsciiAnchor([string]$Path) {
    $cur = $Path.TrimEnd('\')
    while ($true) {
        $parent = [System.IO.Path]::GetDirectoryName($cur)
        if ([string]::IsNullOrEmpty($parent)) { break }
        $leaf = [System.IO.Path]::GetFileName($cur)
        if ($leaf -match '[^\x00-\x7F]') { break }
        $cur = $parent
    }
    return $cur
}

function ConvertTo-RelativePath {
    param([string]$BaseDir, [string]$Target)
    $b = [System.IO.Path]::GetFullPath($BaseDir).TrimEnd('\', '/')
    $t = [System.IO.Path]::GetFullPath($Target).TrimEnd('\', '/')
    if ($b -ieq $t) { return '.' }
    $bParts = $b -split '[\\/]'
    $tParts = $t -split '[\\/]'
    $n = 0
    while ($n -lt $bParts.Count -and $n -lt $tParts.Count -and ($bParts[$n] -ieq $tParts[$n])) { $n++ }
    $parts = New-Object System.Collections.Generic.List[string]
    for ($i = $n; $i -lt $bParts.Count; $i++) { $parts.Add('..') }
    for ($i = $n; $i -lt $tParts.Count; $i++) { $parts.Add($tParts[$i]) }
    if ($parts.Count -eq 0) { return '.' }
    return ($parts -join '\')
}

# build-tools / platform 版本排序用
function Get-VersionKey([string]$Name) {
    $m = [regex]::Match($Name, '^\d+(\.\d+){0,2}')
    if ($m.Success) {
        try { return [version]$m.Value } catch { return [version]'0.0' }
    }
    return [version]'0.0'
}

function Find-SdkRoot {
    param([string]$Hint, [string]$ProjectDir)
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($Hint) { $candidates.Add($Hint) }
    foreach ($envName in @('ANDROID_HOME', 'ANDROID_SDK_ROOT')) {
        $v = [Environment]::GetEnvironmentVariable($envName)
        if ($v) { $candidates.Add($v) }
    }
    # 从项目目录逐级向上找（本仓库的 SDK 在 <workspace>\.toolchains\android-sdk）
    $dir = $ProjectDir
    for ($i = 0; $i -lt 8 -and $dir; $i++) {
        foreach ($sub in @('.toolchains\android-sdk', '.android-sdk', 'android-sdk', 'sdk')) {
            $candidates.Add((Join-Path $dir $sub))
        }
        $parent = [System.IO.Path]::GetDirectoryName($dir)
        if ([string]::IsNullOrEmpty($parent) -or $parent -eq $dir) { break }
        $dir = $parent
    }
    foreach ($p in @("$env:LOCALAPPDATA\Android\Sdk", "$env:USERPROFILE\android-sdk",
                     "$env:USERPROFILE\Android\Sdk", "$env:ProgramFiles\Android\Sdk",
                     'C:\Android\Sdk', 'D:\Android\Sdk', 'D:\tools\android-sdk')) {
        if ($p) { $candidates.Add($p) }
    }
    foreach ($c in $candidates) {
        if (-not $c) { continue }
        if (-not (Test-Path -LiteralPath $c)) { continue }
        $full = (Resolve-Path -LiteralPath $c).Path
        if ((Test-Path -LiteralPath (Join-Path $full 'build-tools')) -and
            (Test-Path -LiteralPath (Join-Path $full 'platforms'))) {
            return $full
        }
    }
    throw "找不到 Android SDK。请用 -SdkRoot 指定（需要含 build-tools\ 和 platforms\ 的目录）。"
}

function Find-BuildTools {
    param([string]$SdkRoot, [string]$VersionHint)
    $btRoot = Join-Path $SdkRoot 'build-tools'
    if (-not (Test-Path -LiteralPath $btRoot)) { throw "SDK 里没有 build-tools 目录: $btRoot" }
    $dirs = @(Get-ChildItem -LiteralPath $btRoot -Directory | Where-Object {
        (Test-Path -LiteralPath (Join-Path $_.FullName 'aapt.exe')) -and
        (Test-Path -LiteralPath (Join-Path $_.FullName 'zipalign.exe')) -and
        (Test-Path -LiteralPath (Join-Path $_.FullName 'lib\apksigner.jar')) -and
        (Test-Path -LiteralPath (Join-Path $_.FullName 'lib\d8.jar'))
    })
    if ($VersionHint) {
        $hit = @($dirs | Where-Object { $_.Name -eq $VersionHint })
        if (-not $hit) { throw "build-tools 下没有可用的版本 '$VersionHint'（需要含 aapt.exe/zipalign.exe/lib\apksigner.jar/lib\d8.jar）" }
        return $hit[0].FullName
    }
    if ($dirs.Count -eq 0) { throw "build-tools 下没有找到完整可用的版本（需要 aapt.exe/zipalign.exe/lib\apksigner.jar/lib\d8.jar）" }
    return (@($dirs | Sort-Object { Get-VersionKey $_.Name })[-1]).FullName
}

function Find-Platform {
    param([string]$SdkRoot, [string]$Hint)
    $pRoot = Join-Path $SdkRoot 'platforms'
    if (-not (Test-Path -LiteralPath $pRoot)) { throw "SDK 里没有 platforms 目录: $pRoot" }
    $dirs = @(Get-ChildItem -LiteralPath $pRoot -Directory | Where-Object {
        $_.Name -match '^android-\d+$' -and (Test-Path -LiteralPath (Join-Path $_.FullName 'android.jar'))
    })
    if ($dirs.Count -eq 0) { throw "platforms 下没有含 android.jar 的 android-* 目录" }
    if ($Hint) {
        $name = $Hint
        if ($name -notmatch '^android-') { $name = "android-$name" }
        $hit = @($dirs | Where-Object { $_.Name -eq $name })
        if (-not $hit) { throw "platforms 下没有 '$name'（可选: $((@($dirs | ForEach-Object { $_.Name })) -join ', ')）" }
        return $hit[0].Name
    }
    $sorted = @($dirs | Sort-Object { [int]($_.Name -replace '^android-', '') })
    return $sorted[-1].Name
}

function Find-JdkHome {
    param([string]$Hint)
    $candidates = New-Object System.Collections.Generic.List[string]
    if ($Hint) { $candidates.Add($Hint) }
    $envJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME')
    if ($envJavaHome) { $candidates.Add($envJavaHome) }
    $javacCmd = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($javacCmd) { $candidates.Add([System.IO.Path]::GetDirectoryName([System.IO.Path]::GetDirectoryName($javacCmd.Source))) }
    $scanRoots = @('D:\tools', "$env:ProgramFiles\Java", "$env:ProgramFiles\Eclipse Adoptium",
                   "$env:ProgramFiles\Microsoft", "$env:ProgramFiles\Android\Android Studio\jbr",
                   "$env:LOCALAPPDATA\Programs\Android Studio\jbr", "$env:USERPROFILE\.jdks")
    $found = New-Object System.Collections.Generic.List[object]
    foreach ($root in $scanRoots) {
        if (-not $root -or -not (Test-Path -LiteralPath $root)) { continue }
        foreach ($d in @(Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue)) {
            $found.Add([pscustomobject]@{ Path = $d.FullName; Key = Get-VersionKey ($d.Name -replace '^jdk-?', '') })
        }
    }
    foreach ($f in @($found | Sort-Object Key -Descending)) { $candidates.Add($f.Path) }
    foreach ($c in $candidates) {
        if (-not $c) { continue }
        if (-not (Test-Path -LiteralPath $c)) { continue }
        $full = (Resolve-Path -LiteralPath $c).Path
        if ((Test-Path -LiteralPath (Join-Path $full 'bin\javac.exe')) -and
            (Test-Path -LiteralPath (Join-Path $full 'bin\java.exe')) -and
            (Test-Path -LiteralPath (Join-Path $full 'bin\keytool.exe'))) {
            return $full
        }
    }
    throw "找不到可用的 JDK（需要 bin\javac.exe + java.exe + keytool.exe）。请用 -JdkHome 指定。"
}

# zip 条目摘要（检查 resources.arsc 是否 stored、classes.dex 是否在）
function Get-ZipEntrySummary {
    param([string]$Path)
    Add-Type -AssemblyName System.IO.Compression | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null
    $zip = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $Path).Path)
    try {
        $list = foreach ($e in $zip.Entries) {
            [pscustomobject]@{
                Name       = $e.FullName
                Size       = $e.Length
                Compressed = $e.CompressedLength
                Stored     = ($e.CompressedLength -eq $e.Length)
            }
        }
        return @($list)
    }
    finally { $zip.Dispose() }
}

function Write-Stage([string]$Text) {
    Write-Host ''
    Write-Host "===== $Text ====="
}

# ----------------------------------------------------------------------------
# 0. 解析配置（全部自动探测 + 参数覆盖）
# ----------------------------------------------------------------------------
if (-not $PSScriptRoot) { $scriptDir = (Get-Location).Path } else { $scriptDir = $PSScriptRoot }
$projAbs = (Resolve-Path -LiteralPath $scriptDir).Path
Write-Host "VibTest 手工 APK 构建（Windows）· 项目: $projAbs"

$sdkAbs  = Find-SdkRoot  -Hint $SdkRoot -ProjectDir $projAbs
$btAbs   = Find-BuildTools -SdkRoot $sdkAbs -VersionHint $BuildTools
$btVer   = [System.IO.Path]::GetFileName($btAbs)
$platformName = Find-Platform -SdkRoot $sdkAbs -Hint $Platform
$jarAbs  = Join-Path (Join-Path $sdkAbs 'platforms') (Join-Path $platformName 'android.jar')
$jdkAbs  = Find-JdkHome -Hint $JdkHome
$javaAbs = Join-Path $jdkAbs 'bin\java.exe'
$javacAbs = Join-Path $jdkAbs 'bin\javac.exe'
$keytoolAbs = Join-Path $jdkAbs 'bin\keytool.exe'
$aaptAbs = Join-Path $btAbs 'aapt.exe'
$zipalignAbs = Join-Path $btAbs 'zipalign.exe'
$d8JarAbs = Join-Path $btAbs 'lib\d8.jar'
$apksignerJarAbs = Join-Path $btAbs 'lib\apksigner.jar'

if (-not $OutDir) {
    $outAbs = Join-Path $projAbs 'build'
}
elseif ([System.IO.Path]::IsPathRooted($OutDir)) {
    $outAbs = $OutDir
}
else {
    $outAbs = Join-Path $projAbs $OutDir
}
if ($outAbs.TrimEnd('\') -ieq $projAbs.TrimEnd('\')) { throw "-OutDir 不能等于项目目录（会清掉源码），请换一个目录。" }

# ---- 清单文件（-Manifest；默认 <项目>\AndroidManifest.xml） ----
if ($Manifest) {
    if ([System.IO.Path]::IsPathRooted($Manifest)) {
        $manifestAbs = $Manifest
    }
    else {
        $tryCwd = Join-Path (Get-Location).Path $Manifest
        $tryProj = Join-Path $projAbs $Manifest
        if (Test-Path -LiteralPath $tryCwd) { $manifestAbs = $tryCwd }
        elseif (Test-Path -LiteralPath $tryProj) { $manifestAbs = $tryProj }
        else { throw "找不到清单文件 '$Manifest'（当前目录和项目目录都没有）" }
    }
}
else {
    $manifestAbs = Join-Path $projAbs 'AndroidManifest.xml'
}
if (-not (Test-Path -LiteralPath $manifestAbs)) { throw "清单文件不存在: $manifestAbs" }
$manifestAbs = (Resolve-Path -LiteralPath $manifestAbs).Path

# ---- 最终产物路径（-Out；默认 <OutDir>\<ApkName>） ----
if ($Out) {
    if ([System.IO.Path]::IsPathRooted($Out)) { $apkAbs = $Out }
    else { $apkAbs = Join-Path (Get-Location).Path $Out }
}
else {
    $apkAbs = Join-Path $outAbs $ApkName
}
$apkAbs = [System.IO.Path]::GetFullPath($apkAbs)

# keystore 默认放在 workspace 的 .scratch 下，避免往 apps/ 里塞二进制
if (-not $Keystore) {
    $root = $projAbs
    $workspaceRoot = $null
    for ($i = 0; $i -lt 10 -and $root; $i++) {
        if ((Test-Path -LiteralPath (Join-Path $root '.scratch')) -or (Test-Path -LiteralPath (Join-Path $root '.toolchains'))) {
            $workspaceRoot = $root
        }
        $parent = [System.IO.Path]::GetDirectoryName($root)
        if ([string]::IsNullOrEmpty($parent) -or $parent -eq $root) { break }
        $root = $parent
    }
    if ($workspaceRoot) { $Keystore = Join-Path $workspaceRoot '.scratch\keystore\debug.keystore' }
    else { $Keystore = Join-Path $projAbs 'debug.keystore' }
}
elseif (-not [System.IO.Path]::IsPathRooted($Keystore)) {
    $Keystore = Join-Path $projAbs $Keystore
}
$ksAbs = $Keystore

Write-Host ("  SDK          : {0}" -f $sdkAbs)
Write-Host ("  build-tools  : {0}" -f $btVer)
Write-Host ("  platform     : {0}" -f $platformName)
Write-Host ("  JDK          : {0}" -f $jdkAbs)
Write-Host ("  keystore     : {0}" -f $ksAbs)
Write-Host ("  min/target SDK: {0} / {1}" -f $MinSdk, $TargetSdk)
Write-Host ("  out          : {0}" -f $outAbs)
Write-Host ("  manifest     : {0}" -f $manifestAbs)
Write-Host ("  final apk    : {0}" -f $apkAbs)

# 工作目录切到 anchor，让所有 native 工具的参数都是相对 ASCII 路径
$anchorAbs = Get-AsciiAnchor $projAbs
Push-Location -LiteralPath $anchorAbs
$transcriptStarted = $false
try {
    $projRel = ConvertTo-RelativePath $anchorAbs $projAbs
    $sdkRel  = ConvertTo-RelativePath $anchorAbs $sdkAbs
    $jdkRel  = ConvertTo-RelativePath $anchorAbs $jdkAbs
    $outRel  = ConvertTo-RelativePath $anchorAbs $outAbs
    $manifestRel = ConvertTo-RelativePath $anchorAbs $manifestAbs
    $apkRel  = ConvertTo-RelativePath $anchorAbs $apkAbs
    foreach ($pair in @(@('项目', $projRel), @('SDK', $sdkRel), @('输出', $outRel), @('清单', $manifestRel), @('产物', $apkRel))) {
        if ($pair[1] -match '[^\x00-\x7F]') {
            $msg = "{0} 相对 anchor 的路径含非 ASCII（'{1}'），native 工具会报 Illegal byte sequence。" -f $pair[0], $pair[1]
            $msg += '请从更深的纯 ASCII 目录运行，或把 SDK/项目挪到纯 ASCII 路径。'
            throw $msg
        }
    }
    Write-Host ("  anchor(cwd)  : {0}" -f $anchorAbs)

    # 清理 + 目录
    if (Test-Path -LiteralPath $outAbs) { Remove-Item -LiteralPath $outAbs -Recurse -Force }
    New-Item -ItemType Directory -Path $outAbs -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $outAbs 'classes') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $outAbs 'dex') -Force | Out-Null

    # aapt.exe（build-tools 35）要求 -M 指向的文件名字面是 AndroidManifest.xml；
    # 传 v2 清单这类文件时先复制成 <OutDir>\manifest\AndroidManifest.xml 再构建。
    if ([System.IO.Path]::GetFileName($manifestAbs) -ne 'AndroidManifest.xml') {
        $manifestCopyDir = Join-Path $outAbs 'manifest'
        New-Item -ItemType Directory -Path $manifestCopyDir -Force | Out-Null
        Copy-Item -LiteralPath $manifestAbs -Destination (Join-Path $manifestCopyDir 'AndroidManifest.xml') -Force
        $manifestRel = "$outRel\manifest\AndroidManifest.xml"
        Write-Host ("  (清单文件名不是 AndroidManifest.xml，已复制到 {0})" -f (Join-Path $manifestCopyDir 'AndroidManifest.xml'))
    }
    try { Start-Transcript -Path (Join-Path $outAbs 'build.log') -Force | Out-Null; $transcriptStarted = $true } catch { }

    # -------- 可选：重画图标（会改 res/，默认不跑） --------
    if ($RegenIcon) {
        if (-not $PythonExe) {
            throw "-RegenIcon 需要 -PythonExe <python.exe>（本机 PATH 上的 python 是商店占位符，不能直接用）"
        }
        Write-Stage '[0] gen_icon.py（重画图标，会改写 res/）'
        $r = Invoke-Native -FilePath $PythonExe -Arguments @((Join-Path $projAbs 'gen_icon.py')) `
            -WorkingDirectory $projAbs -Label 'gen_icon' -Echo
        Assert-NativeOk $r 'gen_icon.py'
    }

    # ------------------------------------------------------------------
    # [1/6] aapt package
    # ------------------------------------------------------------------
    Write-Stage '[1/6] aapt package（清单 + res + assets -> base.apk）'
    $aaptArgs = @('package', '-f',
        '-M', $manifestRel,
        '-S', "$projRel\res")
    if (Test-Path -LiteralPath (Join-Path $projAbs 'assets')) {
        $aaptArgs += @('-A', "$projRel\assets")
    }
    else {
        Write-Host '  (没有 assets/ 目录，跳过 -A)'
    }
    $aaptArgs += @('-I', "$sdkRel\platforms\$platformName\android.jar",
        '-F', "$outRel\base.apk",
        '--min-sdk-version', "$MinSdk",
        '--target-sdk-version', "$TargetSdk")
    $r = Invoke-Native -FilePath $aaptAbs -Arguments $aaptArgs -WorkingDirectory $anchorAbs -Label 'aapt' -Echo
    Assert-NativeOk $r 'aapt package'
    $script:StageTimes['1/6 aapt package'] = $r.ElapsedMs
    Write-Host ("  -> base.apk {0} bytes" -f (Get-Item -LiteralPath (Join-Path $outAbs 'base.apk')).Length)

    # ------------------------------------------------------------------
    # [2/6] javac --release 8
    # ------------------------------------------------------------------
    Write-Stage '[2/6] javac --release 8'
    $srcRoot = Join-Path $projAbs 'src'
    $sources = @(Get-ChildItem -LiteralPath $srcRoot -Recurse -File -Filter '*.java' |
        ForEach-Object { ConvertTo-RelativePath $anchorAbs $_.FullName })
    if ($sources.Count -eq 0) { throw "src/ 下没有 .java 文件: $srcRoot" }
    $sourcesFile = Join-Path $outAbs 'sources.txt'
    [System.IO.File]::WriteAllLines($sourcesFile, [string[]]$sources, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host ("  java 文件 {0} 个 -> sources.txt" -f $sources.Count)
    $r = Invoke-Native -FilePath $javacAbs -WorkingDirectory $anchorAbs -Label 'javac' -Echo -Arguments @(
        '-nowarn', '--release', '8', '-encoding', 'UTF-8',
        '-classpath', "$sdkRel\platforms\$platformName\android.jar",
        '-d', "$outRel\classes",
        "@$outRel\sources.txt")
    Assert-NativeOk $r 'javac'
    $script:StageTimes['2/6 javac'] = $r.ElapsedMs
    $classCount = @(Get-ChildItem -LiteralPath (Join-Path $outAbs 'classes') -Recurse -File -Filter '*.class').Count
    Write-Host ("  -> {0} 个 .class" -f $classCount)

    # ------------------------------------------------------------------
    # [3/6] d8
    # ------------------------------------------------------------------
    Write-Stage '[3/6] d8（.class -> classes.dex）'
    $classes = @(Get-ChildItem -LiteralPath (Join-Path $outAbs 'classes') -Recurse -File -Filter '*.class' |
        ForEach-Object { ConvertTo-RelativePath $anchorAbs $_.FullName })
    $classesFile = Join-Path $outAbs 'classes.txt'
    [System.IO.File]::WriteAllLines($classesFile, [string[]]$classes, (New-Object System.Text.UTF8Encoding($false)))
    $r = Invoke-Native -FilePath $javaAbs -WorkingDirectory $anchorAbs -Label 'd8' -Echo -Arguments @(
        '-cp', "$sdkRel\build-tools\$btVer\lib\d8.jar", 'com.android.tools.r8.D8',
        '--lib', "$sdkRel\platforms\$platformName\android.jar",
        '--min-api', "$MinSdk", '--release',
        '--output', "$outRel\dex",
        "@$outRel\classes.txt")
    Assert-NativeOk $r 'd8'
    $script:StageTimes['3/6 d8'] = $r.ElapsedMs
    $dexFileAbs = Join-Path $outAbs 'dex\classes.dex'
    if (-not (Test-Path -LiteralPath $dexFileAbs)) { throw "d8 没有产出 classes.dex" }
    Write-Host ("  -> classes.dex {0} bytes" -f (Get-Item -LiteralPath $dexFileAbs).Length)

    # ------------------------------------------------------------------
    # [4/6] 把 classes.dex 写进 base.apk
    #   原脚本是 `zip -q -X unsigned.apk classes.dex`（Linux）。Windows 没有 zip 命令；
    #   这里用 .NET ZipArchive.Update 追加条目（实测会保留已有条目的压缩方式与压缩数据，
    #   所以 resources.arsc 仍是 stored）。
    # ------------------------------------------------------------------
    Write-Stage '[4/6] 装 dex（base.apk + classes.dex -> unsigned.apk）'
    $stage4Watch = [System.Diagnostics.Stopwatch]::StartNew()
    Copy-Item -LiteralPath (Join-Path $outAbs 'base.apk') -Destination (Join-Path $outAbs 'unsigned.apk') -Force
    Add-Type -AssemblyName System.IO.Compression | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null
    $unsignedAbs = Join-Path $outAbs 'unsigned.apk'
    $zip = [System.IO.Compression.ZipFile]::Open($unsignedAbs, [System.IO.Compression.ZipArchiveMode]::Update)
    try {
        $newEntry = $zip.CreateEntry('classes.dex', [System.IO.Compression.CompressionLevel]::Optimal)
        $inStream = [System.IO.File]::OpenRead($dexFileAbs)
        try {
            $outStream = $newEntry.Open()
            try { $inStream.CopyTo($outStream) } finally { $outStream.Dispose() }
        }
        finally { $inStream.Dispose() }
    }
    finally { $zip.Dispose() }
    $entries = Get-ZipEntrySummary -Path $unsignedAbs
    $arsc = @($entries | Where-Object { $_.Name -eq 'resources.arsc' })
    if ($arsc.Count -eq 0) { throw 'unsigned.apk 里没有 resources.arsc' }
    if (-not $arsc[0].Stored) { throw 'resources.arsc 不是 stored（压缩方式错误），Android 11+ 安装会失败' }
    if (-not (@($entries | Where-Object { $_.Name -eq 'classes.dex' }))) { throw 'classes.dex 没写进 unsigned.apk' }
    Write-Host ("  -> unsigned.apk {0} entries（resources.arsc=stored, classes.dex={1} bytes）" -f $entries.Count, (($entries | Where-Object { $_.Name -eq 'classes.dex' })[0].Size))
    $stage4Watch.Stop()
    $script:StageTimes['4/6 装 dex'] = [int]$stage4Watch.ElapsedMilliseconds

    # ------------------------------------------------------------------
    # [5/6] zipalign（代替 align.py）
    # ------------------------------------------------------------------
    Write-Stage ("[5/6] zipalign -p -f {0}（4 字节对齐 + resources.arsc stored）" -f $Align)
    $r = Invoke-Native -FilePath $zipalignAbs -WorkingDirectory $anchorAbs -Label 'zipalign' -Echo -Arguments @(
        '-p', '-f', "$Align", "$outRel\unsigned.apk", "$outRel\aligned.apk")
    Assert-NativeOk $r 'zipalign'
    $script:StageTimes['5/6 zipalign'] = $r.ElapsedMs
    $entries = Get-ZipEntrySummary -Path (Join-Path $outAbs 'aligned.apk')
    $arsc = @($entries | Where-Object { $_.Name -eq 'resources.arsc' })
    if ($arsc.Count -eq 0 -or -not $arsc[0].Stored) { throw 'align 之后 resources.arsc 不是 stored' }
    $r = Invoke-Native -FilePath $zipalignAbs -WorkingDirectory $anchorAbs -Label 'zipalign-check' -Echo -Arguments @('-c', '-p', "$Align", "$outRel\aligned.apk")
    Assert-NativeOk $r 'zipalign -c'
    Write-Host '  -> zipalign -c 校验通过，resources.arsc 仍为 stored'

    # ------------------------------------------------------------------
    # [6/6] apksigner 签名 + 校验
    # ------------------------------------------------------------------
    Write-Stage '[6/6] apksigner 签名（v1+v2）'
    if (-not (Test-Path -LiteralPath $ksAbs)) {
        Write-Host "  keystore 不存在，生成: $ksAbs"
        $ksDir = [System.IO.Path]::GetDirectoryName($ksAbs)
        if ($ksDir -and -not (Test-Path -LiteralPath $ksDir)) { New-Item -ItemType Directory -Path $ksDir -Force | Out-Null }
        $r = Invoke-Native -FilePath $keytoolAbs -WorkingDirectory $anchorAbs -Label 'keytool' -Arguments @(
            '-genkeypair', '-v', '-keystore', (ConvertTo-RelativePath $anchorAbs $ksAbs),
            '-storepass', 'android', '-keypass', 'android',
            '-alias', 'dsh', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
            '-dname', 'CN=DSH Test, O=DSH, C=CN', '-noprompt')
        Assert-NativeOk $r 'keytool -genkeypair'
    }
    else {
        Write-Host "  复用已有 keystore: $ksAbs"
    }
    $apkDirAbs = [System.IO.Path]::GetDirectoryName($apkAbs)
    if ($apkDirAbs -and -not (Test-Path -LiteralPath $apkDirAbs)) {
        New-Item -ItemType Directory -Path $apkDirAbs -Force | Out-Null
    }
    $r = Invoke-Native -FilePath $javaAbs -WorkingDirectory $anchorAbs -Label 'apksigner' -Echo -Arguments @(
        '-jar', "$sdkRel\build-tools\$btVer\lib\apksigner.jar", 'sign',
        '--ks', (ConvertTo-RelativePath $anchorAbs $ksAbs),
        '--ks-pass', 'pass:android', '--key-pass', 'pass:android',
        '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true',
        '--v4-signing-enabled', 'false',
        '--out', $apkRel, "$outRel\aligned.apk")
    Assert-NativeOk $r 'apksigner sign'
    $script:StageTimes['6/6 签名'] = $r.ElapsedMs

    Write-Stage '校验'
    $r = Invoke-Native -FilePath $javaAbs -WorkingDirectory $anchorAbs -Label 'verify' -Echo -Arguments @(
        '-jar', "$sdkRel\build-tools\$btVer\lib\apksigner.jar", 'verify', '--verbose', '--print-certs', $apkRel)
    Assert-NativeOk $r 'apksigner verify'
    # minSdk >= 24 时 apksigner 默认不校验 v1（用不到）；强制按 21 再验一次，确认 v1 也在
    if ($MinSdk -ge 24) {
        $r21 = Invoke-Native -FilePath $javaAbs -WorkingDirectory $anchorAbs -Label 'verify-v1' -Arguments @(
            '-jar', "$sdkRel\build-tools\$btVer\lib\apksigner.jar", 'verify', '--verbose', '--min-sdk-version', '21', $apkRel)
        Assert-NativeOk $r21 'apksigner verify --min-sdk-version 21'
        $v1 = ($r21.StdOut -split "`r?`n" | Where-Object { $_ -match 'v1 scheme' } | Select-Object -First 1)
        Write-Host ("  v1 兼容性: {0}" -f $v1.Trim())
    }
    $r = Invoke-Native -FilePath $aaptAbs -WorkingDirectory $anchorAbs -Label 'badging' -Arguments @('dump', 'badging', $apkRel)
    Assert-NativeOk $r 'aapt dump badging'
    $badgingLines = @($r.StdOut -split "`r?`n" | Where-Object {
        $_ -match '^package:|^sdkVersion:|^targetSdkVersion:|^application-label:|^launchable-activity:'
    })
    Write-Host '  aapt dump badging：'
    $badgingLines | ForEach-Object { Write-Host "    $_" }

    # 完整体积 / 哈希
    $apkItem = Get-Item -LiteralPath $apkAbs
    $sha = (Get-FileHash -LiteralPath $apkAbs -Algorithm SHA256).Hash
    Write-Host ''
    Write-Host '================ 构建成功 ================'
    Write-Host ("  APK      : {0}" -f $apkAbs)
    Write-Host ("  log      : {0}" -f (Join-Path $outAbs 'build.log'))
    Write-Host ("  size     : {0:N0} bytes ({1:N1} KB)" -f $apkItem.Length, ($apkItem.Length / 1KB))
    Write-Host ("  sha256   : {0}" -f $sha)
    Write-Host ("  package  : {0}" -f ($badgingLines | Where-Object { $_ -match '^package:' }))
    Write-Host '  各阶段耗时：'
    foreach ($k in $script:StageTimes.Keys) {
        Write-Host ("    {0,-18} {1,6} ms" -f $k, $script:StageTimes[$k])
    }
    $script:TotalWatch.Stop()
    Write-Host ("  总计       {0,6} ms" -f [int]$script:TotalWatch.ElapsedMilliseconds)

    [pscustomobject]@{
        App       = 'stepsim'
        ApkPath   = $apkAbs
        Manifest  = $manifestAbs
        ApkName   = [System.IO.Path]::GetFileName($apkAbs)
        SizeBytes = $apkItem.Length
        Sha256    = $sha
        Package   = (($badgingLines | Where-Object { $_ -match '^package:' }) -join '')
        SdkRoot   = $sdkAbs
        BuildTools = $btVer
        Platform  = $platformName
        JdkHome   = $jdkAbs
        TotalMs   = [int]$script:TotalWatch.ElapsedMilliseconds
    }
}
finally {
    if ($transcriptStarted) { try { Stop-Transcript | Out-Null } catch { } }
    Pop-Location
}
