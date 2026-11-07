#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从主工程清单生成「震摇测试2」(com.dsh.vibshake2) 的清单。

★ 本文件是【重建版】。
  原 tools/sync_vibshake2.py 在交接包里丢失（README-HANDOFF.md §6.3 提到它但未附带）。
  这里按 apks/VibShakeTest2.apk 的二进制清单反推等价实现，规则全部有成品证据支撑：
      aapt dump xmltree apks/VibShakeTest2.apk AndroidManifest.xml

  推导依据（主工程 apps/tester/AndroidManifest.xml → 成品）：
    package              com.dsh.vibshake          -> com.dsh.vibshake2
    versionCode / Name   69 / 5.4                  -> 72 / 3.8
    application label    震摇测试                   -> 震摇测试2
    activity label       震摇测试                   -> 震摇测试2
    activity name        .MainActivity             -> com.dsh.vibshake.MainActivity
    receiver name        .MockShakeReceiver        -> com.dsh.vibshake.MockShakeReceiver
    广播 action          com.dsh.vibshake.*        -> 保持不变（跨 App 协议，改名即断）

  为什么组件名必须展开成全限定名：
    清单里的 `.MainActivity` 在安装时按 manifest 的 package 解析。package 改成
    com.dsh.vibshake2 后，系统会去找 com.dsh.vibshake2.MainActivity，而类仍在
    com.dsh.vibshake 包下（源码包名不动）→ ActivityNotFound。
    成品 APK 里这两个组件名确实是全限定的 com.dsh.vibshake.*，与此推断吻合。

  为什么版本号必须显式传参：
    它是独立版本线（主工程 5.4/69，v2 是 3.8/72），不存在从主工程推导的公式。
    默认值取自最后一次上线的成品，可用参数覆盖。

用法:
    python3 tools/sync_vibshake2.py                       # 默认 → apps/tester/AndroidManifest.vibshake2.xml
    python3 tools/sync_vibshake2.py --version-name 3.9 --version-code 73
    python3 tools/sync_vibshake2.py --check               # 只校验，不写文件

★ 只要改了主工程清单（加权限/加 receiver/加组件），**必须重跑本脚本**，
  否则 v2 清单会缺东西 —— README §6.3 说这个坑已经踩过一次。
  脚本每次从主清单整体重生成，不存在增量合并导致的漏项。
"""

import argparse
import re
import sys
from pathlib import Path

BASE_PACKAGE = "com.dsh.vibshake"
V2_PACKAGE = "com.dsh.vibshake2"
SRC_LABEL = "震摇测试"
V2_LABEL = "震摇测试2"

HERE = Path(__file__).resolve().parent
DEFAULT_SRC = HERE.parent / "apps" / "tester" / "AndroidManifest.xml"
DEFAULT_OUT = HERE.parent / "apps" / "tester" / "AndroidManifest.vibshake2.xml"

# 成品里的值（apks/VibShakeTest2.apk 实测）
DEFAULT_VERSION_NAME = "3.8"
DEFAULT_VERSION_CODE = "72"


class SyncError(Exception):
    pass


def sub_once(text, pattern, repl, what):
    new, n = re.subn(pattern, repl, text, count=1)
    if n != 1:
        raise SyncError("未能替换 %s（主清单结构可能已变，请检查）：%s" % (what, pattern))
    return new


def transform(src_text, version_name, version_code, label_src, label_dst,
              base_package, v2_package):
    t = src_text

    # 1) package
    t = sub_once(t, r'package="%s"' % re.escape(base_package),
                 'package="%s"' % v2_package, "package 属性")

    # 2) versionCode / versionName
    t = sub_once(t, r'android:versionCode="[^"]*"',
                 'android:versionCode="%s"' % version_code, "versionCode")
    t = sub_once(t, r'android:versionName="[^"]*"',
                 'android:versionName="%s"' % version_name, "versionName")

    # 3) label（application 与 activity 两处，成品两处都是 震摇测试2）
    if label_src != label_dst:
        t2, n = re.subn(r'android:label="%s"' % re.escape(label_src),
                        'android:label="%s"' % label_dst, t)
        if n == 0:
            raise SyncError("主清单里没找到 android:label=\"%s\"" % label_src)
        t = t2

    # 4) 组件简写 `.Foo` -> `<base_package>.Foo`
    #    只针对以点开头的取值；action / permission 都是完整串，不受影响。
    t, n = re.subn(r'(android:name=")\.([A-Za-z_][\w.]*")',
                   lambda m: m.group(1) + base_package + "." + m.group(2), t)
    if n == 0:
        raise SyncError("主清单里没有以点开头的组件名可展开（结构可能已变）")

    # 5) 自检
    leftovers = re.findall(r'android:name="\.[^"]*"', t)
    if leftovers:
        raise SyncError("仍有未展开的组件简写: %s" % leftovers)
    if 'package="%s"' % base_package in t:
        raise SyncError("package 仍是主工程包名")
    # 广播 action 必须原样保留（改名会让两个 App 收不到彼此的广播）
    for act in ("MOCK_SHAKE", "MOCK_STEP", "GEO", "LOOP", "ROUTE"):
        if ("%s.%s" % (base_package, act)) not in t:
            print("  ⚠ 主清单未声明 action %s.%s（若主工程删了它，这里只是提示）"
                  % (base_package, act))

    return t


def summarize(text):
    """从结果里抽取验收要点，供 --check 与人工比对。"""
    out = []
    m = re.search(r'package="([^"]+)"', text)
    out.append(("package", m.group(1) if m else "?"))
    m = re.search(r'android:versionCode="([^"]*)"', text)
    out.append(("versionCode", m.group(1) if m else "?"))
    m = re.search(r'android:versionName="([^"]*)"', text)
    out.append(("versionName", m.group(1) if m else "?"))
    for lab in re.findall(r'android:label="([^"]+)"', text):
        out.append(("label", lab))
    for name in re.findall(r'android:name="(com\.dsh\.[^"]+\.(?:MainActivity|MockShakeReceiver))"', text):
        out.append(("component", name))
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="从主工程清单生成「震摇测试2」(com.dsh.vibshake2) 清单")
    ap.add_argument("--src", default=str(DEFAULT_SRC), help="主工程清单路径")
    ap.add_argument("--out", default=str(DEFAULT_OUT), help="输出清单路径")
    ap.add_argument("--version-name", default=DEFAULT_VERSION_NAME)
    ap.add_argument("--version-code", default=DEFAULT_VERSION_CODE)
    ap.add_argument("--label-src", default=SRC_LABEL)
    ap.add_argument("--label-dst", default=V2_LABEL)
    ap.add_argument("--base-package", default=BASE_PACKAGE)
    ap.add_argument("--v2-package", default=V2_PACKAGE)
    ap.add_argument("--check", action="store_true",
                    help="只做校验并打印结果，不写文件")
    args = ap.parse_args(argv)

    src = Path(args.src)
    if not src.is_file():
        print("✗ 主工程清单不存在: %s" % src, file=sys.stderr)
        return 2

    src_text = src.read_text(encoding="utf-8")
    try:
        out_text = transform(src_text, args.version_name, args.version_code,
                             args.label_src, args.label_dst,
                             args.base_package, args.v2_package)
    except SyncError as e:
        print("✗ 生成失败: %s" % e, file=sys.stderr)
        return 1

    print("主工程清单: %s" % src)
    print("目标包名  : %s  (versionCode=%s, versionName=%s)"
          % (args.v2_package, args.version_code, args.version_name))
    print("验收要点（应与 apks/VibShakeTest2.apk 的 dump xmltree 一致）:")
    for k, v in summarize(out_text):
        print("    %-12s %s" % (k, v))

    if args.check:
        print("--check：未写文件")
        return 0

    out = Path(args.out)
    old = out.read_text(encoding="utf-8") if out.is_file() else None
    out.write_text(out_text, encoding="utf-8")
    print("%s %s" % ("↻ 已更新" if old is not None else "＋ 已生成", out))
    if old is not None and old != out_text:
        print("   （内容有变化）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
