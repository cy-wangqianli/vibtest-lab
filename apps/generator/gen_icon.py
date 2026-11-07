#!/usr/bin/env python3
"""纯标准库生成 192x192 应用图标（深底 + 青绿波形），输出 res/drawable/ic_launcher.png"""
import math, os, struct, zlib

S = 192
BG = (18, 20, 22, 255)
ACC = (255, 183, 77, 255)
DIM = (140, 116, 82, 255)
R = 42  # 圆角半径

def rr_alpha(x, y):
    """圆角矩形内部为 255，外部 0，边缘做一点抗锯齿"""
    cx = min(max(x, R), S - 1 - R)
    cy = min(max(y, R), S - 1 - R)
    d = math.hypot(x - cx, y - cy)
    if d <= R - 1:
        return 255
    if d >= R + 1:
        return 0
    return int(255 * (R + 1 - d) / 2)

rows = []
for y in range(S):
    row = bytearray()
    for x in range(S):
        a = rr_alpha(x, y)
        col = BG
        if a > 0:
            # 波形
            t = (x - 48) / 16.0
            if 48 <= x <= 144:
                wy = 96 + 26 * math.sin(t)
                if abs(y - wy) <= 4.5:
                    col = ACC
            # 两侧竖条
            if (x in range(30, 36) or x in range(157, 163)) and 76 <= y <= 116:
                col = DIM
        row += bytes((col[0], col[1], col[2], a))
    rows.append(bytes(row))

raw = b"".join(b"\x00" + r for r in rows)

def chunk(tag, data):
    c = struct.pack(">I", len(data)) + tag + data
    return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

png = (b"\x89PNG\r\n\x1a\n"
       + chunk(b"IHDR", struct.pack(">IIBBBBB", S, S, 8, 6, 0, 0, 0))
       + chunk(b"IDAT", zlib.compress(raw, 9))
       + chunk(b"IEND", b""))

os.makedirs("res/drawable", exist_ok=True)
with open("res/drawable/ic_launcher.png", "wb") as f:
    f.write(png)
print("icon written", len(png), "bytes")
