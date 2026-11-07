#!/usr/bin/env python3
"""生成中国省级离线底图 china_offline.js（供 Leaflet 离线渲染）。

用法：
    python3 build_china_offline.py                      # 输出到 ../apps/tester/assets
    python3 build_china_offline.py --out 路径.js

数据源：阿里云 DataV 行政区划边界（geo.datav.aliyun.com）。
该数据由阿里云提供，仅建议个人学习/研究使用；公开发布前请自行确认授权。
若需完全开放的替代源，可用 Natural Earth 的 admin-1 边界自行替换本脚本的输入。
"""
import argparse, json, math, os, urllib.request

URL = "https://geo.datav.aliyun.com/areas_v3/bound/100000_full.json"


def ring_area(r):
    s = 0.0
    for i in range(len(r) - 1):
        s += r[i][0] * r[i + 1][1] - r[i + 1][0] * r[i][1]
    return abs(s) / 2


def simplify(ring, max_points=90):
    step = max(1, len(ring) // max_points)
    return [[round(p[0], 2), round(p[1], 2)] for p in ring[::step]]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="../apps/tester/assets/china_offline.js")
    ap.add_argument("--min-area", type=float, default=0.02)
    ap.add_argument("--max-points", type=int, default=90)
    a = ap.parse_args()

    print("下载省级边界…")
    raw = urllib.request.urlopen(
        urllib.request.Request(URL, headers={"User-Agent": "vibtest-lab/1.0"}), timeout=60).read()
    src = json.loads(raw)
    print(f"  {len(raw)/1024:.0f} KB，{len(src.get('features', []))} 个省级单元")

    out = []
    for f in src.get("features", []):
        name = (f.get("properties") or {}).get("name", "")
        g = f.get("geometry") or {}
        t = g.get("type")
        polys = [g["coordinates"]] if t == "Polygon" else (g["coordinates"] if t == "MultiPolygon" else [])
        for poly in polys:
            for ring in poly[:1]:                     # 只保留外环
                if len(ring) < 4 or ring_area(ring) < a.min_area:
                    continue
                simp = simplify(ring, a.max_points)
                if len(simp) < 4:
                    continue
                cx = sum(p[0] for p in simp) / len(simp)
                cy = sum(p[1] for p in simp) / len(simp)
                out.append({"n": name, "c": [round(cx, 2), round(cy, 2)], "r": simp})

    js = "window.CN_PROVINCES=" + json.dumps(out, separators=(",", ":"), ensure_ascii=False) + ";"
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    with open(a.out, "w", encoding="utf-8") as fh:
        fh.write(js)
    pts = sum(len(o["r"]) for o in out)
    print(f"rings={len(out)} points={pts} size={len(js)/1024:.0f}KB -> {a.out}")


if __name__ == "__main__":
    main()
