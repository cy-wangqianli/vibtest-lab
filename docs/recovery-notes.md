# 交接包取证与恢复记录

> 记录时间：2026-11-07 · 对象：`vibtest-handoff.zip`（2026-11-07 18:20 接收）
> 这份文档回答一个问题：**这个交接包丢了什么、哪些是能确认的、怎么验证**。
> 所有结论都标了证据来源，可以直接复跑。

---

## 0. 一句话结论

交接包的**活代码没丢**。丢的是：① 4 个从未被接线的实验性视图类，② 一个只生成清单的
`sync_vibshake2.py`，③ 一批只被那 4 个类使用的 assets。前两项已按成品 APK 反推重建，
并用 `aapt dump xmltree` / `dexdump` 与成品做了逐项比对。

---

## 1. 最初的误判（保留，因为教训有用）

第一眼看到「成品 dex 比源码多 4 个类、assets 多 6 个文件、版本号也不同」，判断是
**「`apps/` 源码落后成品一代」**。这个判断是错的。

错在把「成品里有源码没有的东西」等价成了「源码陈旧」。正确的判据是
**看共享部分是否一致**，以及**多出来的部分有没有人引用**。

---

## 2. 证据链

### 2.1 共享类逐类比对方法集合 —— 零差异

用 `dexdump` 解析两个 APK 的 `classes.dex`，按 `Class descriptor` 切块，
抽出每个类的 `name :` 行（方法/字段名），逐类对比：

| | 成品类数 | 源码构建类数 | 共享类 | 共享类中的差异 |
|---|---|---|---|---|
| Tester | 52 | 43 | 43 | **0** |
| Generator | 56 | 51 | 51 | **0** |

`MainActivity` 的方法与字段集合也完全一致（含 `lambda$onCreate$0..14` 这类合成名，
名字都是编译器按源码顺序生成的，能对上说明源码结构一致）。

复跑命令：

```bash
dexdump <解出来的 classes.dex> > dump.txt
# 然后按 "Class #N" 切块，抽 "name : '...'" 行，逐类 Compare-Object / set 比较
```

### 2.2 多出来的类：全是死代码

成品多出的类（tester 9 个 / generator 5 个，来自 4 个 / 3 个源文件）：

| 文件 | 形态 | 方法特征 |
|---|---|---|
| `City2DView` | WebView 包装 | `js/setListener/setPicking/closeLoop/step/addOwn/addRemote/clearAll`，加载 `city2d.html` |
| `Map3DView` | WebView 包装 | `push/pushMaybe/json/trim/round1/addOwn/addRemote/clearAll`，节流推送点集 |
| `RouteView` | Canvas 自绘 | `onDraw/buildPath/sx/sy/bounds/trim/...`，PAINT 风格同 `StatsChart` |
| `RouteView3D` | Canvas 自绘 | `onDraw/drawGrid/drawTrack/proj/...`，带伪 3D 投影 |

**可达性检查**：扫描成品 dex 中**除声明类以外**的所有类块，搜索这四个类名 ——
`tester: 0 处`、`generator: 0 处`。即 MainActivity 和任何其它类都**没有引用过它们**。

→ 它们是作者 `src/` 里放着的实验文件，被 `build.sh` 的 `find src -name '*.java'` 一起编译
打包进了 APK，但从未接线。**这就是为什么源码树里没有它们却不影响 App 行为。**

### 2.3 assets 差异 —— 全是孤儿资源

成品多出的 assets 对应关系：

| assets | 消费者 |
|---|---|
| `city2d.html` + `mapdata.js` | `City2DView`（死代码） |
| `city.html` + `three.min.js` | `Map3DView`（死代码） |
| `mapleaflet.html` + `geo/{cities,countries,land}.js` | 上述视图（无 Java 引用） |

同名文件（`leaflet.js/css`、`mapstreet.html`、`china_offline.js`）两侧 **sha256 完全一致**。
`china_offline.js` **本来就在交接包里** —— `README-HANDOFF.md` §5 说它"仓库未附带"是文档写错了。

### 2.4 清单差异 —— 这才是真正的产品线差异

| 项 | `apps/tester` 构建 | `apks/VibShakeTest2.apk` |
|---|---|---|
| package | `com.dsh.vibshake` | `com.dsh.vibshake2` |
| versionCode / Name | 69 / 5.4 | 72 / 3.8 |
| application label | 震摇测试 | 震摇测试2 |
| activity 名 | `.MainActivity` | `com.dsh.vibshake.MainActivity` |
| receiver 名 | `.MockShakeReceiver` | `com.dsh.vibshake.MockShakeReceiver` |
| resources.arsc 包名 | `com.dsh.vibshake` | `com.dsh.vibshake2` |
| 广播 action | `com.dsh.vibshake.*` | `com.dsh.vibshake.*`（不变） |

**关键点**：改名只改 `package`，类仍在 `com.dsh.vibshake` 包下。
清单里的 `.MainActivity` 会在安装时按 manifest 的 package 解析 —— package 一改，
系统就去找 `com.dsh.vibshake2.MainActivity`，而类根本不在那儿 → ActivityNotFound。
所以组件名必须**展开成全限定名**。成品 APK 里这两个组件确实都是全限定的，与推断吻合。

同理 `resources.arsc` 里的包名会跟着 aapt 构建时的 package 走，也是成品实测确认的。

---

## 3. 恢复清单

| 项 | 做法 | 验证方式 | 状态 |
|---|---|---|---|
| assets（6+6 个） | 从 APK 的 zip 条目逐字节取出 | sha256 双向比对 + 条目集合相等 | ✅ 完成 |
| `tools/sync_vibshake2.py` | 按 §2.4 规则重写 | 生成的清单再打成 APK，`dump xmltree` 与成品比 permissions/actions/components/labels/资源包名 → **5 项全 PASS** | ✅ 完成 |
| generator 版本号 | 清单 4.0/31 → 4.1/32（成品实测值） | `aapt dump badging` | ✅ 完成 |
| Windows 构建链 | `apps/*/build.ps1` | 构建 + `apksigner verify` + `zipalign -c` | ✅ 完成 |
| 4 个孤儿视图类 | jadx 1.5.6 反编译回收，补进 `src/` | 编译通过；`verify_parity.py` 类集合与成品一致 | ✅ 完成 |
| `debug.keystore`（签名密钥） | **未找回** | 成品证书 SHA-256 `6dfae415…`；现有 keystore `a68fa76e…`，不同 | ❌ 无法恢复 |

> 关于 `sync_vibshake2.py`：**原脚本已丢失，这是重建版**。
> 它的规则不是猜的，是逐条从成品二进制清单反推、再用 `aapt` 复现验证的。
> 唯一无法从主工程推导的是版本号（独立版本线），因此做成显式参数，默认值取最后一次上线值。

> 关于反编译回收的 7 个文件：文件头都标了来源。只做了两处处理——清理反编译噪音注释、
> 把 jadx 生成的合成 lambda 引用（`this.f$0.m23lambda$…()`，编译不过）还原成源码级 lambda。
> 语义未改动。

### 3.1 最终验收结果

| 成品 | 重建 | 结果 |
|---|---|---|
| `apks/VibShakeTest2.apk` | 554,084 B | **PASS（0 项不一致）** |
| `apks/VibGen.apk` | 476,260 B | **PASS（0 项不一致）** |
| `apks/StepSim.apk` | 16,851 B | **PASS（0 项不一致）** |

差异只剩两项：`compileSdkVersion` 34→35（构建所用 platform 决定）和签名（密钥丢失）。

---

## 4. 怎么验收

```bash
# 逐项对比「期望成品」与「新构建产物」：清单/资源包名/assets/dex 类与方法
python tools/verify_parity.py --expect apks/VibShakeTest2.apk \
                              --actual apps/tester/build/VibShakeTest2.apk
```

判据：
- 关键项全一致 → `PASS`
- 允许的差异只有 `compileSdkVersion`（成品用 android-34 构建，本机只有 android-35）
  和签名（重建用 debug keystore）——脚本会单独列出，不判失败。

`--strict` 可把 compileSdkVersion 也纳入严格比对。

---

## 5. 交接包的环境前提（与本文档无关但会绊人）

- `aapt.exe` / `dexdump.exe` 吃**含中文的绝对路径**会报 `Illegal byte sequence`。
  解决：先把 cwd 切到文件所在目录，只传相对文件名（`tools/verify_parity.py` 已内置）。
- `aapt package -M` 的清单**文件名必须是 `AndroidManifest.xml`**，
  传 `AndroidManifest.vibshake2.xml` 会报 "No AndroidManifest.xml file found"。
  所以构建 v2 时要把生成的清单复制成临时目录里的 `AndroidManifest.xml`。
- 本机沙箱下 `tempfile.mkdtemp()` 建的目录不可写（0700 切断了继承的写 ACE），用 `os.makedirs()`。
