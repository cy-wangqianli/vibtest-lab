# 交接说明 · VibTest Lab

> 给接手方的完整上下文。读完这份就能继续开发，不必追溯历史对话。
>
> **2026-11-07 修订**：本文件已被重建过一轮。原交接包里的源码比成品缺东西、文档也有三处
> 硬错，均已修正并在文中标注。取证过程与证据链见 [`docs/recovery-notes.md`](docs/recovery-notes.md)。

---

## 0. 用途与研究边界（先读这一段）

本项目是一套**自建的红蓝对抗实验装置**：用一个自研 App 去激励传感器、用另一个自研 App 去观测和评分，
研究"运动数据从传感器到统计结果"这条链上，**哪些特征可以把模拟数据与真实数据区分开**。

- 三个 App **只与彼此通信**：所有广播都用 `setPackage(...)` 固定到本项目自己的包名，
  不注入、不拦截、不规避任何第三方应用。
- 目标是**统计可分性**（间隔规律性、速度变异、重复落点、位置跳变），不是"如何把数据刷进某个平台"。
- **不接受**、也不会合入任何针对第三方应用的规避、注入或反检测内容。

> 说明：`addTestProvider` 模拟定位与振动激励本身是系统级能力，客观上可被其它用途借用。
> 本项目提供的是实验方法，使用者需自行遵守所在平台的服务条款与当地法律。

**关于地图数据**：仓库**不包含**衍生地图数据（`mapdata.js`、`china_offline.js`、`geo/*`），
它们授权各异、且会暴露具体地理位置，已由 `.gitignore` 排除。需要时运行 `tools/` 下的脚本自行生成，
来源与许可差异见 [ATTRIBUTIONS.md](ATTRIBUTIONS.md)。

---

## 1. 这是什么

三个 Android 应用 + 一套离线仿真脚本，研究「运动数据从传感器到统计结果」这条链路：

| 应用 | 包名 | 角色 | 作用 |
|---|---|---|---|
| 震摇测试2 | `com.dsh.vibshake2` | 蓝队 | 采集：步态识别、GPS 轨迹、统计、数据可信度评分 |
| 震动发生器 | `com.dsh.vibgen` | 红队 | 激励：震动波形、模拟路线/定位、难样本生成 |
| 步数模拟器 | `com.dsh.stepsim` | 辅助 | 只发步数事件，不碰传感器 |

三者通过**广播**互通，也都能独立运行（震摇测试读真实传感器，发生器单独出震动）。

### 1.1 两条「震摇测试」产品线（容易混，先看清楚）

同一份 `apps/tester/src` 代码，用两份清单打成两个不同 App：

| | 震摇测试（祖先） | 震摇测试2（当前产品） |
|---|---|---|
| package | `com.dsh.vibshake` | `com.dsh.vibshake2` |
| 版本 | 5.4 (69) | **3.8 (72)** |
| 清单文件 | `apps/tester/AndroidManifest.xml` | `apps/tester/AndroidManifest.vibshake2.xml`（**生成物，勿手改**） |
| 生成方式 | — | `python tools/sync_vibshake2.py` |
| 类所在包 | `com.dsh.vibshake` | 同样是 `com.dsh.vibshake`（只改 App ID，不改代码包名） |

两个包名不同 ⇒ 手机上会**并存成两个 App**，互不覆盖。

**边界**：所有实验限定在这几个 App 之间，代码里没有任何针对第三方应用的规避、注入或反检测逻辑，也不接受此类贡献。

---

## 2. 技术栈与构建

**没有用 Gradle**。原因：目标机器是手机上的 arm64 Ubuntu 容器，Google 的 `build-tools` 里 `aapt2` 只有 x86-64 版本，AGP 一调就失败。所以走手工链：

```
aapt package（清单+资源+assets）
  → javac --release 8（classpath = android.jar）
  → d8（--min-api 26）
  → 对齐（4 字节 + resources.arsc 强制 stored）
  → apksigner（v1+v2）
```

### 2.1 Linux（原路线，`build.sh`）

```bash
apt-get install -y openjdk-17-jdk-headless aapt zip unzip python3
# Android SDK: build-tools;34.0.0 + platforms;android-34
export ANDROID_HOME=$HOME/android-sdk

cd apps/tester && bash build.sh      # → VibShakeTest.apk
cd apps/generator && bash build.sh   # → VibGen.apk
cd apps/stepsim && bash build.sh     # → StepSim.apk
```

### 2.2 Windows（新增，`build.ps1`）

三个 app 各有一份自包含的 `build.ps1`，语义与 `build.sh` 的六阶段一致，
SDK / build-tools / platform / JDK **全部自动探测**，不硬编码版本。

```powershell
# 默认：用项目自带的 AndroidManifest.xml，产物落 <项目>\build\<ApkName>
.\apps\tester\build.ps1
.\apps\generator\build.ps1
.\apps\stepsim\build.ps1

# 打「震摇测试2」(com.dsh.vibshake2)：复用 tester 的 src/res/assets，只换清单
.\apps\tester\build.ps1 `
    -Manifest apps\tester\AndroidManifest.vibshake2.xml `
    -OutDir   <任意目录> `
    -Out      <任意目录>\VibShakeTest2.apk
```

常用参数：`-Manifest` `-Out` `-OutDir` `-ApkName` `-Keystore` `-MinSdk` `-TargetSdk`
`-SdkRoot` `-BuildTools` `-Platform` `-JdkHome` `-RegenIcon`。

实测单包构建约 4 秒（首次多约 1.5 秒用于生成 keystore）。

> 若 PowerShell 报「在此系统上禁止运行脚本」，先执行：
> `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force`
> 脚本必须保持 **UTF-8 with BOM**（PowerShell 5.1 用 `-File` 启动时按 GBK 读无 BOM 文件，中文会乱码）。

换到 Gradle 也完全可以：把 `src/`、`assets/`、`AndroidManifest.xml` 搬进标准工程，
`minSdk 26 / compileSdk 34 / Java 8`。`aapt` 生成的 `R` 类没有被使用（纯代码 UI）。

---

## 3. 应用架构

### 3.1 震摇测试（`apps/tester/src/com/dsh/vibshake/`）

| 文件 | 职责 |
|---|---|
| `MainActivity.java` | 单 Activity，全部 UI 由代码构建（ScrollView + LinearLayout） |
| `LeafletMapView.java` | WebView 包装，加载 `assets/mapstreet.html`，JS 桥 `setHere/addLive/showLoop/setStats/resetView` |
| `StatsChart.java` | 自绘速度曲线（Canvas） |
| `SplitChart.java` | 自绘每公里配速柱状图 |
| `GeoUtil.java` | WGS-84 ↔ GCJ-02 转换 |
| `MockShakeReceiver.java` | 收广播：`MOCK_SHAKE` / `MOCK_STEP` / `ROUTE` / `GEO` / `LOOP` |
| `MockLoc.java` | 残留在 tester 里未使用，定位功能已移到 generator，可删 |
| `City2DView.java` | 城市 2D 视图（Canvas 城市底图 + 轨迹），加载 `city2d.html`。**见 §3.4** |
| `Map3DView.java` | 3D 地形视图，加载 `city.html`。**见 §3.4** |
| `RouteView.java` / `RouteView3D.java` | Canvas 自绘的 2D / 伪 3D 轨迹视图。**见 §3.4** |

关键行为：

- 传感器：`SENSOR_DELAY_FASTEST`，双通道步态检测
  - 慢通道：`lp += 0.35*(linear-lp)`，阈值 `max(1.2, floor*2.0)`，回滞 60%，去抖 `clamp(lastGap*0.5, 180, 320)`
  - 快通道：原始差分 `d > max(2.0, dFloor*3.5)`，独立 150 ms 去抖
- 距离优先级：系统定位 > 广播坐标 > `步数 × 步幅`
- 坐标：系统 WGS-84 → 转 GCJ-02 再画到高德底图
- 可信度评分：间隔规律性、速度变异、重复落点、速度超人类、位置跳变（详见 `docs/detection.md`）

### 3.2 震动发生器（`apps/generator/src/com/dsh/vibgen/`）

| 文件 | 职责 |
|---|---|
| `MainActivity.java` | 全部 UI + 波形选择 + 地图 + 模拟定位控制 |
| `VibeService.java` | 前台服务，把震动波形交给系统播放（后台不断） |
| `SimKeepAlive.java` | 模拟运动期间的前台保活，防止 MIUI 杀进程导致广播中断 |
| `MockLoc.java` | 沿闭环/定点推送定位；无权限时降级为纯广播 |
| `LeafletMapView.java` | 同 tester |
| `GeoUtil.java` | 同 tester |
| `SimReceiver.java` | 接收测试器发来的 `SIM_LOOP` |
| `Map3DView.java` / `RouteView.java` / `RouteView3D.java` | 同 tester 的三个视图（**见 §3.4**，同样是死代码） |

关键行为：

- 震动波形：正弦 / 脉冲 / 扫频 / 计数 / 跑步（步频 120–200 spm）
- 难样本：短脉冲 15 ms、间隔抖动、双峰、扫参、静默段、随机幅度、拟真抖动
- 地图闭环 → `gcj2wgs` → `MockLoc`；同时广播 `com.dsh.vibshake.LOOP` 给测试器
- 模拟定位：`addTestProvider` + `setTestProviderLocation`（需开发者选项 + 选为模拟位置应用）

### 3.3 StepSim（`apps/stepsim/`）

最小应用：按设定步频向 `com.dsh.vibshake` / `com.dsh.vibshake2` 广播 `MOCK_STEP`，不读传感器、不震动。

### 3.4 ★ 四个「孤儿视图」是死代码（重要，别被误导）

`City2DView` / `Map3DView` / `RouteView` / `RouteView3D` 这四个类（generator 里是后三个）
**在成品 APK 里存在，但没有任何类引用它们**——`MainActivity` 里零引用，全 APK 零引用。
它们是作者放在 `src/` 里、被 `build.sh` 的 `find src -name '*.java'` 一起编译打包进去的实验文件，
从未接线。配套的 assets（`city2d.html` `city.html` `mapleaflet.html` `mapdata.js` `three.min.js`
`geo/*.js`）同样是孤儿资源，运行时永远不会被加载。

**为什么留着**：为了让源码能逐项重建出成品（`tools/verify_parity.py` 会比对类集合）。
**如果想删**：这四个 `.java` + 上面那批 assets 一起删即可，App 行为不变；
删完 `verify_parity.py` 会报类集合差异 —— 这是预期的，用 `--expect` 指向删过的基线即可。

> 这 7 个文件的原源码在交接中丢失，现版本是**从成品 APK 反编译回收**的（详见 §6）。

---

## 4. 广播协议

| action | 方向 | extras | 用途 |
|---|---|---|---|
| `com.dsh.vibshake.MOCK_SHAKE` | Gen → Tester | `seq` | 模拟摇动事件 |
| `com.dsh.vibshake.MOCK_STEP` | Gen/StepSim → Tester | `seq` | 模拟脚步 |
| `com.dsh.vibshake.GEO` | Gen → Tester | `lat`,`lng`,`speed` | 坐标回落 |
| `com.dsh.vibshake.LOOP` | Gen → Tester | `json` | 只读展示闭环 |
| `com.dsh.vibshake.SIM_LOOP` | Tester → Gen | `json`,`len` | 测试器生成模拟路线给发生器 |

广播都带 `setPackage(...)`，因为 Android 8+ 限制隐式广播；接收端必须在清单里声明且 `exported="true"`。

★ **action 字符串不随包名变**。`com.dsh.vibshake2` 收发的仍是 `com.dsh.vibshake.*` —— 这是跨 App 协议，
改名会让两个 App 收不到彼此的广播。`sync_vibshake2.py` 会刻意保留它们。

---

## 5. 地图

- 引擎：Leaflet（`assets/leaflet.js`，BSD-2）
- 底图：高德在线瓦片 `wprd0{s}.is.autonavi.com/appmaptile?...&style=7`，失败 8 次自动切 OSM
- 离线层：`assets/china_offline.js`（省级轮廓，约 54 KB，**本次已确认在仓库里**
  —— 原 README 说"未附带"是错的；生成脚本 `tools/build_china_offline.py`）
- 交互：点选加点、`Catmull-Rom` 闭合平滑、拖动/双指缩放、比例尺、回到当前位置
- 坐标：地图用 GCJ-02，系统用 WGS-84，转换在 `GeoUtil`

### 5.1 地图锚点集中配置（换城市只改一处）

米制平面坐标要换算成经纬度才画得上地图，锚点原先散落在 7 个文件、12 处，漏改一处就是
「整条轨迹偏移几十公里」这种极难查的 bug。现在唯一来源是 [`tools/mapcity.json`](tools/mapcity.json)：

```bash
python tools/set_city.py                      # 把 mapcity.json 同步到所有 Java 常量与 HTML
python tools/set_city.py --check              # 只校验：所有文件是否都与 json 一致（可进 CI）
python tools/set_city.py --city deyang        # 切到内置城市（写回 json 并同步）
python tools/set_city.py --city chengdu --fetch --km 3
                                              # 顺带重生成 mapdata.js（需联网 Overpass）
```

内置 11 城：`deyang chengdu mianyang chongqing xian beijing shanghai guangzhou shenzhen wuhan hangzhou`。

★ **换城市后必须重生成 `mapdata.js`**（`--fetch` 或直接跑 `tools/fetch_osm.py`），
否则离线城市平面还是老城市的，会和锚点错位。
★ 本脚本**刻意不新增 Java 类**——本项目 dex 类集合是与成品 APK 的比对基准，
多一个 `MapConfig` 类会让 `tools/verify_parity.py` FAIL。所以它是原地重写常量，类结构不变。

---

## 6. 本次恢复的东西（交接包丢了什么）

| 丢失项 | 现状 | 验证方式 |
|---|---|---|
| 4 个孤儿视图类（tester 4 个文件 / generator 3 个） | 已从成品 dex 反编译回收，补进 `src/` | 编译通过 + `verify_parity.py` 类集合一致 |
| `tools/sync_vibshake2.py` | 已按成品清单反推重写 | 生成的清单打成 APK 后 `dump xmltree` 与成品比 permissions/actions/components/labels/资源包名 **5 项全 PASS** |
| 7 个孤儿 assets（6+6，含 `geo/` 子目录） | 已从成品 APK 逐字节取回 | 每文件 sha256 与成品一致 |
| `debug.keystore`（签名密钥） | **未找回，无法找回** | 见 §9.1 |

详细证据链：[`docs/recovery-notes.md`](docs/recovery-notes.md)。

> 反编译回收的 7 个文件，文件头都有来源标注。已做的处理只有两件：
> 清理反编译噪音注释、把 jadx 生成的合成 lambda 引用还原成源码级 lambda。**语义未改动。**

---

## 7. 怎么验收「源码能重建出成品」

```bash
python tools/verify_parity.py --expect apks/VibShakeTest2.apk \
                             --actual <新构建的>.apk
```

比对四个维度：清单（package/版本/label/组件全限定名/权限/action）、
`resources.arsc` 包名、assets（文件集 + 每个 sha256）、dex（类集合 + 每类方法集合）。

当前实测结果：

| 成品 | 重建 | 结果 |
|---|---|---|
| `apks/VibShakeTest2.apk` | `build.ps1 -Manifest ...vibshake2.xml` | **PASS（0 项不一致）** |
| `apks/VibGen.apk` | `apps/generator/build.ps1` | **PASS（0 项不一致）** |
| `apks/StepSim.apk` | `apps/stepsim/build.ps1` | **PASS（0 项不一致）** |

**允许的差异**（脚本会自动列出、不判失败）：
- `compileSdkVersion` 34 → 35：由构建时 `-I` 的 android.jar 决定；`targetSdk` 仍是 34、`minSdk` 26。
  安装器/运行时/Play 都只看 targetSdk，无实际影响。要 badging 显示 34 需装 `platforms;android-34` 后重打。
- 签名：重建用新 keystore（见 §9.1），证书不同不影响功能。

`--strict` 可把 compileSdkVersion 也纳入严格比对；
`--strict-synthetics` 连 d8/R8 生成的 `$$ExternalSyntheticLambda*` 一起比（需要与成品同版本 build-tools，否则必然误报）。

**声明的差异用 `--skip` 排除**：改了地图锚点（§5.1）之后，`mapstreet.html` / `mapleaflet.html`
的内容必然与成品不同，这时用

```bash
python tools/verify_parity.py --expect apks/VibGen.apk \
                             --actual apps/generator/build/VibGen.apk --skip assets
```

仍会严格比对清单、`resources.arsc` 包名与全部 dex 类/方法；只有 assets 维度被跳过。
（`--skip` 可取 `manifest` / `resources` / `assets` / `dex`，可多次给。）

---

## 8. 已重建的产物在哪

| 产物 | 路径 |
|---|---|
| 震摇测试2（v2 清单变体） | 按 §2.2 的命令自行构建（`-Out` 指定输出；仓库不预置构建产物） |
| 震动发生器 | `apps/generator/build/VibGen.apk` |
| 步数模拟器 | `apps/stepsim/build/StepSim.apk` |
| 震摇测试（祖先线） | `apps/tester/build/VibShakeTest.apk` |

★ **`apks/` 里的三个文件是交接时收到的参考成品，请勿覆盖** —— 它们是 `verify_parity.py` 的比对基线。

★ `apps/tester/VibShakeTest.apk`（v5.4/69，94 KB）是**历史产物**：它构建于那 4 个孤儿类加入 `src/`
之前，所以现在的源码已经**无法**再复现它（类集合会多 9 个）。留着仅作历史参考。

---

## 9. 已知问题 / 待办

### 9.1 ★ 签名密钥丢失，覆盖安装会失败

成品三个 APK 都由同一把证书签名：

```
DN: CN=DSH Test, O=DSH, C=CN
SHA-256: 6dfae415aab7a55d1994ba00d32177fbeb800fd46722604f9b6babc816cfc813
```

原 `debug.keystore` **不在交接包里**（`build.sh` 里 `$PROJ/debug.keystore` 只有不存在时才生成）。
现在重建用的是新生成的 keystore（`<workspace>\.scratch\keystore\debug.keystore`，SHA-256 `a68fa76e…`）。

后果：把重建包装到已装旧版的手机上会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
两条路：①先卸载旧版再装；②找回原 keystore 后 `-Keystore <路径>` 重新签名（只有这样才能保留升级能力）。

其余待办（原样保留）：

1. **高德地图不跟随模拟定位**：其 SDK 过滤 `Location.isFromMockProvider()`，非 root 无法绕过（也不再尝试）。震摇测试不过滤，所以能跟。
2. **MIUI 后台杀进程**：已用前台服务缓解；更彻底要 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（未做）。
3. **`vibshake2` 的清单是生成的**：改 `apps/tester/AndroidManifest.xml`（加权限/加 receiver）后**必须重跑**
   `python tools/sync_vibshake2.py`，否则 v2 清单会漏东西。这个坑已经踩过一次。
4. **统计口径**：GPS 与步数两套算法并存，涉及 `maxKmh/minKmh/bestPace` 的地方必须判断 `gpsFresh()`，否则数字会自相矛盾。
5. **短脉冲检测不到**（15 ms/40%）：仿真显示这是采样率与平滑造成的物理下限，降阈值会带来大量误检。
6. **StepSim 切后台会停**：没有前台服务。
7. 地图瓦片依赖网络；离线只有省界，没有街道。
8. 4 个孤儿视图类仍是死代码（§3.4）——要么接线，要么连同孤儿 assets 一起删。

---

## 10. 目录

```
apps/tester/      采集 + 检测（同时产出 com.dsh.vibshake 与 com.dsh.vibshake2 两条线）
apps/generator/   激励 + 模拟运动
apps/stepsim/     步数广播
tools/verify_parity.py        APK 一致性验收（清单/资源包名/assets/dex 逐项比）
tools/sync_vibshake2.py       主清单 → v2 清单（重建版）
tools/build_china_offline.py  生成省级离线底图
tools/fetch_osm.py            按城市抓 OSM 道路/建筑
tools/build_mapdata.py        OSM JSON → 本地米制平面（产出 mapdata.js）
tools/sim/                    检测器仿真（纯标准库）
docs/detection.md             检测算法细节
docs/recovery-notes.md        ★ 本次取证与恢复记录（丢了什么、怎么验证）
apks/             交接时的参考成品（勿覆盖，验收基线）
```

---

## 11. 性能与体积

| 项 | 数值 |
|---|---|
| 震摇测试2 APK | 541 KB（重建 554,084 B / 参考 554,165 B） |
| 震动发生器 APK | 465 KB（重建 476,260 B / 参考 480,431 B） |
| 步数模拟器 APK | 16 KB |
| 地图资源 | leaflet 145 KB + 省界 54 KB + 城市平面 `mapdata.js` 674 KB（仅 tester，且是孤儿资源） |
| 构建耗时（Windows，单包） | 约 4 秒 |
| 内存 | 两份 WebView（主地图 + 全屏选点）同时存在，低端机可能吃紧 |

---

## 12. 开发建议

- 改地图页只需改 `apps/*/assets/mapstreet.html`，两端共用，改完两边都要同步。
- **换地图城市别手动改常量**：改 `tools/mapcity.json` 或跑 `python tools/set_city.py --city <城市>`（§5.1），
  它会同步全部 12 处；改完记得重生成 `mapdata.js`，并用 `--check` 确认没有漏改。
- 加检测项：在 `MainActivity.scoreFixes()` 里加判据，记得同步更新 `docs/detection.md` 的扣分表。
- 加波形：Generator 的 `build()` / `VibeService.vibratePattern()` 两处都要改（前台服务是独立实现）。
- 调阈值前先跑 `tools/sim/` 的仿真，别在真机上盲试。
- 改完一定要跑 `tools/verify_parity.py`，别靠肉眼看"好像一样"。

### 12.1 本机（Windows）环境坑，会反复绊人

1. **中文路径**：`aapt.exe` / `dexdump.exe` 吃含中文的**绝对路径**会报 `Illegal byte sequence`。
   解决：把 cwd 切到文件所在目录，只传**相对文件名**（`verify_parity.py`、`build.ps1` 都已内置处理；
   `subst` 虚拟盘被沙箱拒绝、8.3 短名被系统禁用，都走不通）。
2. **`aapt package -M` 的清单文件名必须字面是 `AndroidManifest.xml`**，
   传别的名字会报 `ERROR: No AndroidManifest.xml file found.`。
   `build.ps1 -Manifest` 会自动复制成 `<OutDir>\manifest\AndroidManifest.xml` 再构建。
3. **沙箱下 `tempfile.mkdtemp()` 建的目录不可写**（0700 切断继承的写 ACE），用 `os.makedirs()`。
   系统 temp 也写不进，临时文件请建在项目目录/`.scratch` 下。
4. **jadx 的 `-jar` 启动的是 GUI**（无显示环境会静默挂住）。命令行要用
   `java -cp jadx-all.jar jadx.cli.JadxCLI …`，并加 `-Duser.home=<可写目录>` 否则它往只读的用户配置目录写缓存。
5. PowerShell 的 `$home` 是只读自动变量，别拿来当普通变量名。
