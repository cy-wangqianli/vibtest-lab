#!/usr/bin/env python3
"""把 Overpass 导出的 OSM JSON 压成本地米制平面，生成 mapdata.js"""
import json, math, sys, os

SRC = sys.argv[1] if len(sys.argv) > 1 else "chengdu_osm.json"
DST = sys.argv[2] if len(sys.argv) > 2 else "../vibshake/assets/mapdata.js"

d = json.load(open(SRC, encoding="utf-8"))
els = d.get("elements", [])

lats = [e["lat"] for e in els if "lat" in e] + \
       [g["lat"] for e in els if "geometry" in e for g in e["geometry"]]
lons = [e["lon"] for e in els if "lon" in e] + \
       [g["lon"] for e in els if "geometry" in e for g in e["geometry"]]
lat0 = sum(lats) / len(lats)
lon0 = sum(lons) / len(lons)

MX = 111320.0 * math.cos(math.radians(lat0))
MY = 110540.0

def px(lat, lon):
    return round((lon - lon0) * MX, 1), round((lat - lat0) * MY, 1)

ROAD_CLASS = {
    "motorway": 0, "trunk": 0,
    "primary": 1, "primary_link": 1,
    "secondary": 2, "secondary_link": 2,
    "tertiary": 3, "tertiary_link": 3,
    "residential": 4, "unclassified": 4, "living_street": 4,
    "service": 5, "pedestrian": 5,
}

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
            x, y = px(g["lat"], g["lon"])
            coords.extend([x, y])
        if len(coords) >= 4:
            roads.append([cls, coords])

    elif "building" in tags:
        coords = []
        for g in geo:
            x, y = px(g["lat"], g["lon"])
            coords.extend([x, y])
        if len(coords) < 6:
            continue
        # 面积过滤（鞋带公式，粗略）
        area = 0.0
        n = len(coords)
        for i in range(0, n, 2):
            j = (i + 2) % n
            area += coords[i] * coords[j + 1] - coords[j] * coords[i + 1]
        if abs(area) / 2 < 15:
            continue
        buildings.append(coords)

payload = {"roads": roads, "buildings": buildings}
js = "var MAP=" + json.dumps(payload, separators=(",", ":")) + ";"
os.makedirs(os.path.dirname(DST), exist_ok=True)
with open(DST, "w", encoding="utf-8") as f:
    f.write(js)

print(f"roads={len(roads)} buildings={len(buildings)} "
      f"size={len(js)/1048576:.2f}MB -> {DST}")
