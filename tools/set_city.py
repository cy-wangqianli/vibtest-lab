#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""地图锚点单一来源 → 同步到所有需要它的地方。

为什么需要这个脚本
------------------
米制平面坐标要换算成经纬度才能画到地图上，锚点（原点经纬度）原先散落在 7 处：
  · apps/{tester,generator}/src/com/dsh/*/LeafletMapView.java   BASE_LAT / BASE_LNG
  · apps/{tester,generator}/assets/mapstreet.html               var BASE = [lat, lng]
  · apps/{tester,generator}/assets/mapleaflet.html              var BASE = [lat, lng]
  · apps/tester/src/.../MainActivity.java                       兜底默认坐标
  · apps/generator/src/.../MainActivity.java                    输入框提示值
漏改一处的后果是「轨迹整体偏移几十公里」这种很难查的 bug。

现在唯一来源是 tools/mapcity.json，改完跑本脚本即可。

★ 刻意【不新增 Java 类】：本项目的 dex 类集合是与成品 APK 逐项比对的基准
  （tools/verify_parity.py），多出一个 MapConfig 类会让验收 FAIL。所以这里做的是
  「原地重写常量」，类结构完全不变。

用法
----
    python tools/set_city.py                     # 把 mapcity.json 的锚点同步到所有文件
    python tools/set_city.py --check             # 只校验：所有文件是否都与 json 一致
    python tools/set_city.py --city chengdu      # 用内置城市表切换（并写回 json）
    python tools/set_city.py --lat 30.657 --lng 104.066
    python tools/set_city.py --city chengdu --fetch --km 3
                                                 # 顺带重生成 mapdata.js（需联网 Overpass）

★ 换城市后必须重生成 mapdata.js：离线城市平面（city2d.html / city.html 用的米制数据）
  是以锚点为中心抓的，锚点变了而数据没变，画出来就是错位。
"""

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

# 控制台编码兜底：Windows 默认 GBK，打印 ↻/✗/中文符号会抛 UnicodeEncodeError
for _s in (sys.stdout, sys.stderr):
    try:
        _s.reconfigure(encoding="utf-8", errors="replace")
    except Exception:                                      # noqa: BLE001
        pass

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
CFG = HERE / "mapcity.json"

# 与 fetch_osm.py 内置城市表保持一致（换城市时两者可以对上）
CITIES = {
    "deyang": (31.1289, 104.3979),
    "chengdu": (30.6570, 104.0660),
    "mianyang": (31.4675, 104.6796),
    "chongqing": (29.5630, 106.5516),
    "xian": (34.3416, 108.9398),
    "beijing": (39.9042, 116.4074),
    "shanghai": (31.2304, 121.4737),
    "guangzhou": (23.1291, 113.2644),
    "shenzhen": (22.5431, 114.0579),
    "wuhan": (30.5928, 114.3055),
    "hangzhou": (30.2741, 120.1551),
}

# (相对路径, 说明) —— 这些文件里含锚点
JAVA_LL = [
    "apps/tester/src/com/dsh/vibshake/LeafletMapView.java",
    "apps/generator/src/com/dsh/vibgen/LeafletMapView.java",
]
HTML_PAGES = [
    "apps/tester/assets/mapstreet.html",
    "apps/tester/assets/mapleaflet.html",
    "apps/generator/assets/mapstreet.html",
    "apps/generator/assets/mapleaflet.html",
]
TESTER_MAIN = "apps/tester/src/com/dsh/vibshake/MainActivity.java"
GEN_MAIN = "apps/generator/src/com/dsh/vibgen/MainActivity.java"

PATTERNS = [
    # (正则, 替换模板, 说明)  —— 用命名组 lat/lng，保证只替换数字部分
    (re.compile(r"(BASE_LAT\s*=\s*)(-?\d+\.\d+)(\s*;)"),
     None, "LeafletMapView.BASE_LAT"),
    (re.compile(r"(BASE_LNG\s*=\s*)(-?\d+\.\d+)(\s*;)"),
     None, "LeafletMapView.BASE_LNG"),
    (re.compile(r"(var BASE = \[)(-?\d+\.\d+)(\s*,\s*)(-?\d+\.\d+)(\s*\])"),
     None, "HTML var BASE"),
]


def load_cfg():
    if not CFG.is_file():
        return {"city": None, "lat": CITIES["chengdu"][0], "lng": CITIES["chengdu"][1]}
    return json.loads(CFG.read_text(encoding="utf-8"))


def save_cfg(cfg):
    CFG.write_text(json.dumps(cfg, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def apply_to_text(text, lat, lng):
    """返回 (新文本, 替换计数)"""
    n = 0

    def sub_ll(m):
        nonlocal n
        n += 1
        key = "BASE_LAT" if "BASE_LAT" in m.group(0) else "BASE_LNG"
        val = lat if key == "BASE_LAT" else lng
        return "%s%s%s" % (m.group(1), val, m.group(3))

    text = PATTERNS[0][0].sub(sub_ll, text)
    text = PATTERNS[1][0].sub(sub_ll, text)
    # HTML: var BASE = [lat, lng]
    def sub_base(m):
        nonlocal n
        n += 1
        return "%s%s%s%s%s" % (m.group(1), lat, m.group(3), lng, m.group(5))

    text = PATTERNS[2][0].sub(sub_base, text)

    # tester MainActivity 兜底：Double.isNaN(lastLat) ? <lat> : lastLat
    def sub_fallback(m):
        nonlocal n
        n += 1
        var = m.group(2)
        val = lat if var == "Lat" else lng
        return "%s%s%s%s%s" % (m.group(1), var, m.group(3), val, m.group(5))

    text = re.sub(r"(Double\.isNaN\(last)(Lat|Lng)(\) \? )(-?\d+\.\d+)( : last\2)",
                  sub_fallback, text)

    # generator MainActivity 输入框提示：纬度 <lat> / 经度 <lng>
    text2, c1 = re.subn(r"(纬度 )(-?\d+\.\d+)", lambda m: m.group(1) + str(lat), text)
    text3, c2 = re.subn(r"(经度 )(-?\d+\.\d+)", lambda m: m.group(1) + str(lng), text2)
    n += c1 + c2
    return text3, n


def read(p):
    return Path(ROOT / p).read_text(encoding="utf-8")


def write(p, t):
    Path(ROOT / p).write_text(t, encoding="utf-8")


def run(cfg, check_only=False):
    lat, lng = cfg["lat"], cfg["lng"]
    targets = JAVA_LL + HTML_PAGES + [TESTER_MAIN, GEN_MAIN]
    total = 0
    problems = []
    for rel in targets:
        f = ROOT / rel
        if not f.is_file():
            print("  ⚠ 跳过（不存在）: %s" % rel)
            continue
        old = read(rel)
        if check_only:
            _, n = apply_to_text(old, lat, lng)
            # check 模式：看「再套一次」会不会变，变了说明不一致
            new, _ = apply_to_text(old, lat, lng)
            if new != old:
                problems.append(rel)
                print("  ✗ 不一致: %s" % rel)
            total += n
        else:
            new, n = apply_to_text(old, lat, lng)
            if new != old:
                write(rel, new)
                print("  ↻ %-56s 改 %d 处" % (rel, n))
            total += n
    if check_only:
        if problems:
            print("\n✗ %d 个文件与 mapcity.json 不一致，跑 python tools/set_city.py 同步" % len(problems))
            return 1
        print("\n✓ 全部文件与 mapcity.json 一致（锚点 %s, %s）" % (lat, lng))
        return 0
    print("\n✓ 已同步锚点 lat=%s lng=%s，共改 %d 处" % (lat, lng, total))
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description="地图锚点单一来源同步")
    ap.add_argument("--city", help="内置城市名（%s）" % ", ".join(sorted(CITIES)))
    ap.add_argument("--lat", type=float)
    ap.add_argument("--lng", type=float)
    ap.add_argument("--check", action="store_true", help="只校验，不写")
    ap.add_argument("--fetch", action="store_true", help="顺带重生成 mapdata.js（需网络）")
    ap.add_argument("--km", type=float, help="配合 --fetch 的抓取半径（km）")
    a = ap.parse_args(argv)

    cfg = load_cfg()

    if a.city or a.lat is not None or a.lng is not None:
        if a.city:
            if a.city not in CITIES:
                print("✗ 未知城市 '%s'，可选: %s" % (a.city, ", ".join(sorted(CITIES))))
                return 2
            lat, lng = CITIES[a.city]
        else:
            if a.lat is None or a.lng is None:
                print("✗ --lat 与 --lng 必须同时给", file=sys.stderr)
                return 2
            lat, lng = a.lat, a.lng
        cfg.update({"city": a.city or cfg.get("city"), "lat": lat, "lng": lng})
        if not a.check:
            save_cfg(cfg)
            print("已更新 %s → %s, %s" % (CFG.relative_to(ROOT), lat, lng))

    print("锚点: %s (%s, %s)" % (cfg.get("city") or "custom", cfg["lat"], cfg["lng"]))
    rc = run(cfg, check_only=a.check)
    if rc != 0 or a.check:
        return rc

    if a.fetch:
        city = cfg.get("city")
        if city not in CITIES:
            print("⚠ --fetch 需要先 --city 指定内置城市", file=sys.stderr)
            return 1
        cmd = [sys.executable, str(HERE / "fetch_osm.py"), city]
        if a.km:
            cmd += ["--km", str(a.km)]
        print("\n→ 重新生成 mapdata.js: %s" % " ".join(cmd))
        return subprocess.call(cmd)
    return 0


if __name__ == "__main__":
    sys.exit(main())
