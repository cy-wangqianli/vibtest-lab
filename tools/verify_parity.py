#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""APK 一致性验收：拿「期望的成品」和「新构建出来的」逐项对比。

背景：交接包里 apps/ 源码与 apks/ 成品的关系需要可复跑的判据，不能靠肉眼。
本脚本把四个可客观比较的维度全部展开：

  1. 清单  —— package / versionCode / versionName / label / 组件全限定名 /
             权限 / 广播 action / minSdk / targetSdk
  2. 资源表 —— resources.arsc 的包名（改名工程最容易漏的一项）
  3. assets —— 文件集合 + 每个文件 sha256
  4. 代码   —— dex 里的类集合 + 每个类的方法集合

已知且允许的差异（默认不判失败，单独列出）：
  * compileSdkVersion / platformBuildVersionCode / platformBuildVersionName
    —— 由构建时用的 platform 决定（成品是 android-34，本机只有 android-35）
  * 签名 —— 重建用的是 debug keystore

用法:
    python tools/verify_parity.py --expect apks/VibShakeTest2.apk \\
                                  --actual apps/tester/build/VibShakeTest2.apk
    python tools/verify_parity.py --expect ... --actual ... --strict   # 连署名 sdk 一起比

退出码: 0 = 全部关键项一致, 1 = 有差异, 2 = 工具/参数错误
"""

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import uuid
import zipfile
from pathlib import Path

# 控制台编码兜底：Windows 默认 GBK，打印 ✓ / 中文会抛 UnicodeEncodeError
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:                                       # noqa: BLE001
        pass

# ---------------------------------------------------------------- 工具定位

def find_sdk(explicit=None):
    cands = []
    if explicit:
        cands.append(Path(explicit))
    env = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if env:
        cands.append(Path(env))
    # 本仓库的 toolchains 布局
    here = Path(__file__).resolve().parent.parent          # .../vibtest-handoff
    cands.append(here.parent / ".toolchains" / "android-sdk")
    for c in cands:
        if c and c.is_dir():
            return c
    return None


def find_build_tools(sdk):
    bt = sdk / "build-tools"
    if not bt.is_dir():
        return None
    vers = sorted([p for p in bt.iterdir() if p.is_dir()],
                  key=lambda p: [int(x) for x in re.findall(r"\d+", p.name)] or [0])
    for v in reversed(vers):
        if (v / "aapt.exe").is_file() or (v / "aapt").is_file():
            return v
    return None


def exe(bt, name):
    for suffix in (".exe", ".bat", ""):
        p = bt / (name + suffix)
        if p.is_file():
            return str(p)
    return None


# ---------------------------------------------------------------- 调用外部工具
# 注意：aapt/dexdump 吃含中文的绝对路径会报 "Illegal byte sequence"。
# 解决：把 cwd 切到文件所在目录，只传相对文件名。

def run_tool(tool, args, cwd):
    try:
        out = subprocess.run([tool] + args, cwd=str(cwd), capture_output=True,
                             timeout=180)
    except Exception as e:                                  # noqa: BLE001
        return "", "无法执行 %s: %s" % (tool, e)
    txt = out.stdout.decode("utf-8", "replace")
    err = out.stderr.decode("utf-8", "replace")
    if out.returncode != 0 and not txt:
        return "", "工具返回 %d: %s" % (out.returncode, (err or "").strip()[:400])
    return txt, ""


def rel_call(tool, apk_path, args, apk_pos=0):
    """在 apk 所在目录里用相对文件名调用工具。

    args 里用 "@APK@" 占位，避免各子命令把 APK 放在不同位置时传错。
    """
    apk = Path(apk_path).resolve()
    argv = [apk.name if a == "@APK@" else a for a in args]
    if "@APK@" not in args:                     # 兜底：放回默认位置
        argv = argv[:apk_pos] + [apk.name] + argv[apk_pos:]
    return run_tool(tool, argv, apk.parent)


# ---------------------------------------------------------------- 各维度抽取

def get_badging(aapt, apk):
    txt, err = rel_call(aapt, apk, ["dump", "badging", "@APK@"])
    if err:
        raise RuntimeError("badging 失败: %s" % err)
    d = {}
    m = re.search(r"^package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'",
                  txt, re.M)
    if m:
        d["package"] = m.group(1)
        d["versionCode"] = m.group(2)
        d["versionName"] = m.group(3)
    m = re.search(r"^application-label:'([^']*)'", txt, re.M)
    if m:
        d["label"] = m.group(1)
    m = re.search(r"^sdkVersion:'([^']*)'", txt, re.M)
    if m:
        d["minSdk"] = m.group(1)
    m = re.search(r"^targetSdkVersion:'([^']*)'", txt, re.M)
    if m:
        d["targetSdk"] = m.group(1)
    m = re.search(r"compileSdkVersion='([^']*)'", txt)
    if m:
        d["compileSdk"] = m.group(1)
    return d


def get_manifest_tree(aapt, apk):
    """把 xmltree 压成 {属性名序列}，并单独抽出需要精确比对的值。"""
    txt, err = rel_call(aapt, apk, ["dump", "xmltree", "@APK@", "AndroidManifest.xml"])
    if err:
        raise RuntimeError("xmltree 失败: %s" % err)
    facts = {"permissions": [], "actions": [], "components": [], "labels": []}
    # 元素 -> 它的 android:name（用于判断是权限/action/组件）
    stack = []
    for line in txt.splitlines():
        s = line.strip()
        if s.startswith("E: "):
            elem = s.split()[1].split("(")[0]
            stack.append(elem)
        elif s.startswith("A: android:name") or s.startswith("A: android:label"):
            m = re.search(r'A: android:(name|label)\([^)]*\)="([^"]*)"', s)
            if not m:
                continue
            kind, val = m.group(1), m.group(2)
            cur = stack[-1] if stack else "?"
            if kind == "label":
                facts["labels"].append(val)
            elif cur == "uses-permission":
                facts["permissions"].append(val)
            elif cur == "action":
                facts["actions"].append(val)
            elif cur in ("activity", "receiver", "service", "provider", "activity-alias"):
                facts["components"].append("%s:%s" % (cur, val))
    facts["permissions"] = sorted(set(facts["permissions"]))
    facts["actions"] = sorted(set(facts["actions"]))
    facts["components"] = sorted(set(facts["components"]))
    facts["labels"] = sorted(set(facts["labels"]))
    return facts


def get_res_package(aapt, apk):
    txt, err = rel_call(aapt, apk, ["dump", "resources", "@APK@"])
    if err:
        return "?"
    m = re.search(r"Package 0 id=0x7f name=([\w.]+)", txt)
    return m.group(1) if m else "?"


def get_assets(apk):
    """assets/ 下每个条目的 sha256。"""
    res = {}
    with zipfile.ZipFile(apk) as z:
        for n in z.namelist():
            if n.startswith("assets/") and not n.endswith("/"):
                h = hashlib.sha256(z.read(n)).hexdigest()
                res[n] = h
    return res


def get_classes(dexdump, apk):
    """{类描述符: 排序后的方法名集合}"""
    apk = Path(apk).resolve()
    # 注意：这里必须用 os.makedirs 而不是 tempfile.mkdtemp。
    # 本机 DSH 沙箱下，mkdtemp 建的目录权限是 0700，会把父目录继承来的
    # 写权限 ACE 挡掉，导致随后写文件报 PermissionError；makedirs 则正常继承。
    tmpdir = apk.parent / (".verifydex-" + uuid.uuid4().hex[:8])
    os.makedirs(str(tmpdir))
    try:
        dex = tmpdir / "classes.dex"
        with zipfile.ZipFile(apk) as z:
            with z.open("classes.dex") as src, open(dex, "wb") as dst:
                shutil.copyfileobj(src, dst)
        txt, err = run_tool(dexdump, [dex.name], tmpdir)
        if err:
            raise RuntimeError("dexdump 失败: %s" % err)
    finally:
        shutil.rmtree(str(tmpdir), ignore_errors=True)

    classes = {}
    cur = None
    for line in txt.splitlines():
        m = re.search(r"Class descriptor\s+:\s+'(L[^']+)'", line)
        if m:
            cur = m.group(1)
            classes.setdefault(cur, set())
            continue
        m = re.match(r"^\s+name\s+: '([^']+)'", line)
        if m and cur:
            nm = m.group(1)
            # 编译器生成的内部名字不参与比对
            if nm in ("<init>",):
                continue
            if nm.startswith("access$"):
                nm = "access$"
            classes[cur].add(nm)
    return {k: sorted(v) for k, v in classes.items()}


# ---------------------------------------------------------------- 合成物过滤
# 编译器/Lambda 脱糖产生的名字取决于 d8/R8 的版本，与源码无关。
# 成品用 build-tools 34 构建、本机是 35，两边的合成名必然不同，
# 放进比对只会制造误报，所以默认剔除（--strict-synthetics 可保留）。
SYNTH_CLASS_RE = re.compile(r"(\$\$ExternalSyntheticLambda\d*|\$\$Lambda\$[\w$]*)$")
# 只剔 R8/D8 内部命名；`lambda$onCreate$3$...` 这类是源码结构决定的，保留参与比对
SYNTH_METHOD_PREFIXES = ("$r8$lambda$", "access$", "-$$Lambda$")


def is_synth_class(desc):
    # desc 形如 Lcom/dsh/vibgen/MainActivity$$ExternalSyntheticLambda0; —— 要先去掉首尾
    return bool(SYNTH_CLASS_RE.search(desc.strip("L;")))


def filter_synthetics(classes):
    out = {}
    for k, methods in classes.items():
        if is_synth_class(k):
            continue
        out[k] = [m for m in methods
                  if not any(m.startswith(p) for p in SYNTH_METHOD_PREFIXES)]
    return out


# ---------------------------------------------------------------- 比对与报告

IGNORED_MANIFEST = {"compileSdk"}


def cmp_dict(name, a, b, rows, ignore=()):
    keys = sorted(set(a) | set(b))
    ok = True
    for k in keys:
        if k in ignore:
            continue
        if a.get(k) != b.get(k):
            ok = False
            rows.append((False, "%s.%s" % (name, k), str(a.get(k)), str(b.get(k))))
    return ok


def cmp_set(name, a, b, rows, item_fmt=lambda x: x):
    only_a = sorted(set(a) - set(b))
    only_b = sorted(set(b) - set(a))
    ok = not only_a and not only_b
    if only_a:
        rows.append((False, "%s 只有期望侧" % name, fmt_list(only_a), ""))
    if only_b:
        rows.append((False, "%s 只有实际侧" % name, "", fmt_list(only_b)))
    return ok


def fmt_list(items, limit=8):
    items = list(items)
    s = ", ".join(str(x) for x in items[:limit])
    if len(items) > limit:
        s += " …(共 %d)" % len(items)
    return s


def main(argv=None):
    ap = argparse.ArgumentParser(description="APK 一致性验收")
    ap.add_argument("--expect", required=True, help="期望的成品 APK")
    ap.add_argument("--actual", required=True, help="新构建出来的 APK")
    ap.add_argument("--sdk", default=None, help="Android SDK 路径")
    ap.add_argument("--strict", action="store_true",
                    help="连 compileSdkVersion 一起严格比对")
    ap.add_argument("--strict-synthetics", action="store_true",
                    help="连 d8/R8 生成的合成类与方法名一起比对"
                         "（需要与成品同版本的 build-tools，否则必然误报）")
    ap.add_argument("--skip", action="append", default=[],
                    choices=["manifest", "resources", "assets", "dex"],
                    help="跳过某一维度。典型用法：--skip assets —— 改了地图锚点后 "
                         "mapstreet.html/mapleaflet.html 的内容必然与成品不同，这是**声明的差异**，"
                         "不是回归。")
    args = ap.parse_args(argv)

    for p in (args.expect, args.actual):
        if not Path(p).is_file():
            print("✗ 文件不存在: %s" % p, file=sys.stderr)
            return 2

    sdk = find_sdk(args.sdk)
    if not sdk:
        print("✗ 找不到 Android SDK（用 --sdk 指定）", file=sys.stderr)
        return 2
    bt = find_build_tools(sdk)
    aapt = exe(bt, "aapt")
    dexdump = exe(bt, "dexdump")
    if not (aapt and dexdump):
        print("✗ build-tools 里缺 aapt 或 dexdump: %s" % bt, file=sys.stderr)
        return 2

    rows = []          # (ok, 项目, 期望, 实际)
    all_ok = True
    skip = set(args.skip or [])

    print("期望: %s" % Path(args.expect).resolve())
    print("实际: %s" % Path(args.actual).resolve())
    print("SDK : %s" % bt)
    print()

    try:
        if "manifest" not in skip:
            bd_e = get_badging(aapt, args.expect)
            bd_a = get_badging(aapt, args.actual)
            ignore = () if args.strict else IGNORED_MANIFEST
            all_ok &= cmp_dict("清单", bd_e, bd_a, rows, ignore=ignore)

            tr_e = get_manifest_tree(aapt, args.expect)
            tr_a = get_manifest_tree(aapt, args.actual)
            for key in ("permissions", "actions", "components", "labels"):
                all_ok &= cmp_set(key, tr_e.get(key, []), tr_a.get(key, []), rows)
        else:
            bd_e = bd_a = {}

        if "resources" not in skip:
            res_e = get_res_package(aapt, args.expect)
            res_a = get_res_package(aapt, args.actual)
            if res_e != res_a:
                all_ok = False
                rows.append((False, "resources.arsc 包名", res_e, res_a))

        if "assets" not in skip:
            as_e = get_assets(args.expect)
            as_a = get_assets(args.actual)
            all_ok &= cmp_set("assets 文件集", as_e, as_a, rows)
            common = sorted(set(as_e) & set(as_a))
            bad = [n for n in common if as_e[n] != as_a[n]]
            if bad:
                all_ok = False
                rows.append((False, "assets 内容不一致", fmt_list(bad), ""))

        if "dex" not in skip:
            cl_e = get_classes(dexdump, args.expect)
            cl_a = get_classes(dexdump, args.actual)
            if not args.strict_synthetics:
                cl_e = filter_synthetics(cl_e)
                cl_a = filter_synthetics(cl_a)
            all_ok &= cmp_set("dex 类集合", cl_e, cl_a, rows)
            for c in sorted(set(cl_e) & set(cl_a)):
                if cl_e[c] != cl_a[c]:
                    all_ok = False
                    d = set(cl_e[c]) ^ set(cl_a[c])
                    rows.append((False, "类方法差异 %s" % c, fmt_list(sorted(d)), ""))
    except RuntimeError as e:
        print("✗ %s" % e, file=sys.stderr)
        return 2

    # 打印
    if not rows:
        print("✓ 全部关键项一致")
    else:
        w = max(len(r[1]) for r in rows)
        for ok, item, exp, act in rows:
            print("%s %-*s" % ("✓" if ok else "✗", w, item))
            if not ok:
                print("      期望: %s" % exp)
                print("      实际: %s" % act)

    # 允许的差异，单独提示
    if not args.strict:
        if bd_e.get("compileSdk") != bd_a.get("compileSdk"):
            print("\nℹ 允许的差异: compileSdkVersion %s -> %s（由构建所用 platform 决定，不影响行为）"
                  % (bd_e.get("compileSdk"), bd_a.get("compileSdk")))
        print("ℹ 签名未比对（重建用 debug keystore；如需比对签名用 apksigner verify --print-certs）")

    n_bad = sum(1 for r in rows if not r[0])
    print("\n结果: %s（%d 项不一致）" % ("PASS" if all_ok else "FAIL", n_bad))
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
