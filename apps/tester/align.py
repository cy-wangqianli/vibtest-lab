#!/usr/bin/env python3
"""最小 zipalign：保证每个 entry 的数据偏移 4 字节对齐，
并把 resources.arsc 强制为 stored（Android 11+ 对 targetSdk>=30 的要求）。"""
import sys, zipfile

SRC, DST = sys.argv[1], sys.argv[2]

with zipfile.ZipFile(SRC) as zin, open(DST, "wb") as raw:
    zout = zipfile.ZipFile(raw, "w")

    def pad_extra(offset, name_len, need_at_least=4):
        base = 30 + name_len
        e = (4 - (offset + base) % 4) % 4
        if e < need_at_least:
            e += 4
        payload = e - 4
        return b"\x35\xd9" + payload.to_bytes(2, "little") + b"\x00" * payload

    # 按原顺序逐条写出，逐条计算真实偏移
    entries = [(i, zin.read(i.filename)) for i in zin.infolist()]
    for zi, data in entries:
        off = raw.tell()
        extra = pad_extra(off, len(zi.filename.encode("utf-8")))
        nzi = zipfile.ZipInfo(zi.filename, date_time=zi.date_time)
        nzi.external_attr = zi.external_attr
        nzi.internal_attr = zi.internal_attr
        nzi.create_system = 3
        nzi.compress_type = (zipfile.ZIP_STORED
                             if zi.filename == "resources.arsc"
                             else zipfile.ZIP_DEFLATED)
        nzi.extra = extra
        with zout.open(nzi, "w") as f:
            f.write(data)
    zout.close()

# 校验
with zipfile.ZipFile(DST) as z:
    bad = []
    for i in z.infolist():
        off = i.header_offset + 30 + len(i.filename.encode()) + len(i.extra)
        if off % 4:
            bad.append((i.filename, off % 4))
    arsc = [i for i in z.infolist() if i.filename == "resources.arsc"]
    mode = arsc[0].compress_type if arsc else None
print(f"entries={len(z.infolist())} misaligned={bad} resources.arsc="
      f"{'stored' if mode == 0 else mode}")
sys.exit(1 if bad else 0)
