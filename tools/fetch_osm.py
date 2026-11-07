#!/usr/bin/env python3
"""按城市抓 OSM 道路与建筑，生成 mapdata.js。

用法：
    python3 fetch_osm.py chengdu
    python3 fetch_osm.py chengdu --km 3 --out ../apps/tester/assets/mapdata.js
    python3 fetch_osm.py --list

数据来源：Overpass API（OSM，ODbL）。请遵守各镜像的使用频率限制。
"""
import argparse, json, math, os, sys, time
import urllib.parse, urllib.request

CITIES = {
    "deyang":   (31.1289, 104.3979, 2.5),
    "chengdu":  (30.6570, 104.0660, 3.0),
    "mianyang": (31.4675, 104.6796, 2.5),
    "chongqing":(29.5630, 106.5516, 3.0),
    "xian":     (34.3416, 108.9398, 3.0),
    "beijing":  (39.9042, 116.4074, 3.0),
    "shanghai": (31.2304, 121.4737, 3.0),
    "guangzhou":(23.1291, 113.2644, 3.0),
    "shenzhen": (22.5431, 114.0579, 3.0),
    "wuhan":    (30.5928, 114.3055, 3.0),
    "hangzhou": (30.2741, 120.1551, 3.0),
}

MIRRORS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]

ROAD_CLASS = {
    "motorway": 0, "trunk": 0,
    "primary": 1, "primary_link": 1,
    "secondary": 2, "secondary_link": 2,
    "tertiary": 3, "tertiary_link": 3,
    "residential": 4, "unclassified": 4, "living_street": 4,
    "service": 5, "pedestrian": 5,
}


def bbox_of(lat, lon, km):
    dlat = km / 111.0
    dlon = km / (111.0 * math.cos(math.radians(lat)))
    return lat - dlat, lon - dlon, lat + dlat, lon + dlon


def query(bbox, timeout=180):
    q = f"""[out:json][timeout:{timeout}];
(way["highway"]({bbox[0]:.5f},{bbox[1]:.5f},{bbox[2]:.5f},{bbox[3]:.5f});
 way["building"]({bbox[0]:.5f},{bbox[1]:.5f},{bbox[2]:.5f},{bbox[3]:.5f}););
out geom;"""
    data = urllib.parse.urlencode({"data": q}).encode()
    last = None
    for ep in MIRRORS:
        try:
            t = time.time()
            r = urllib.request.urlopen(
                urllib.request.Request(ep, data=data, headers={"User-Agent": "vibtest-lab/1.0"}),
                timeout=timeout + 30)
            raw = r.read()
            print(f"  {ep.split('/')[2]}: {len(raw)/1048576:.1f} MB, {time.time()-t:.0f}s")
            return json.loads(raw)
        except Exception as e:
            last = e
            print(f"  {ep.split('/')[2]} 失败: {str(e)[:60]}")
    raise SystemExit(f"所有镜像都失败：{last}")


def build(doc, out_path):
    els = doc.get("elements", [])
    lats, lons = [], []
    for e in els:
        for g in e.get("geometry", []):
            lats.append(g["lat"]); lons.append(g["lon"])
    if not lats:
        raise SystemExit("没有拿到几何数据")
    lat0 = sum(lats) / len(lats)
    lon0 = sum(lons) / len(lons)
    mx = 111320.0 * math.cos(math.radians(lat0))
    my = 110540.0

    def px(g):
        return round((g["lon"] - lon0) * mx, 1), round((g["lat"] - lat0) * my, 1)

    roads, buildings = [], []
    for e in els:
        tags = e.get("tags", {})
        geo = e.get("geometry")
        if not geo or len(geo) < 2:
            continue
        if "highway" in tags:
            cls = ROAD_CLASS.get(tags["highway"])
            if cls is None:
                continue
            coords = []
            for g in geo:
                x, y = px(g); coords += [x, y]
            if len(coords) >= 4:
                roads.append([cls, coords])
        elif "building" in tags:
            coords = []
            for g in geo:
                x, y = px(g); coords += [x, y]
            if len(coords) < 6:
                continue
            area = 0.0
            n = len(coords)
            for i in range(0, n, 2):
                j = (i + 2) % n
                area += coords[i] * coords[j + 1] - coords[j] * coords[i + 1]
            if abs(area) / 2 < 15:
                continue
            buildings.append(coords)

    js = "var MAP=" + json.dumps({"roads": roads, "buildings": buildings}, separators=(",", ":")) + ";"
    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        f.write(js)
    print(f"  roads={len(roads)} buildings={len(buildings)} "
          f"size={len(js)/1048576:.2f}MB -> {out_path}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("city", nargs="?")
    ap.add_argument("--km", type=float, default=None, help="半径（公里），默认按城市表")
    ap.add_argument("--out", default=None)
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()

    if a.list or not a.city:
        print("可用城市：")
        for k, v in CITIES.items():
            print(f"  {k:<10} {v[0]:.4f},{v[1]:.4f}  ±{v[2]} km")
        return

    if a.city not in CITIES:
        raise SystemExit(f"未知城市 {a.city}，用 --list 查看")

    lat, lon, km = CITIES[a.city]
    km = a.km or km
    bbox = bbox_of(lat, lon, km)
    out = a.out or f"{a.city}_mapdata.js"
    print(f"抓取 {a.city} ±{km} km：{bbox}")
    doc = query(bbox)
    build(doc, out)


if __name__ == "__main__":
    main()
