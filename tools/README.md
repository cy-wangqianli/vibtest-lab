# 工具

## sim/adversarial_sim.py

合成四种波形（正常/短脉冲/抖动/双峰），对比两版步态检测器的漏检与多检。

```bash
python3 sim/adversarial_sim.py
```

## sim/sim2.py

```bash
python3 sim/sim2.py a   # 200Hz 双通道 vs 100Hz 单通道的检出率网格
python3 sim/sim2.py b   # 静默段恢复测试
```

## build_mapdata.py

把 Overpass 导出的 OSM JSON 压成本地米制平面，生成 `mapdata.js`（供 Canvas 渲染使用）。

```bash
python3 build_mapdata.py chengdu_osm.json ../apps/tester/assets/mapdata.js
```

bbox 在脚本顶部改。只保留 `highway` 与 `building`，建筑面积 < 15 m² 的丢弃。

数据来自 OpenStreetMap，ODbL 许可，使用时须署名。
