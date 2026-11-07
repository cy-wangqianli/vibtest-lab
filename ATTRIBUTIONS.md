# 第三方组件与数据来源声明

本仓库的**自研代码**（`apps/*/src`、`tools/*.py`、构建脚本、文档）以根目录 [LICENSE](LICENSE)（MIT）授权。

下列第三方组件与数据**各自适用其原始许可**，声明如下。

---

## 一、随仓库分发的第三方代码

| 组件 | 位置 | 许可 | 版权 |
|---|---|---|---|
| Leaflet 1.9.4 | `apps/*/assets/leaflet.js`、`leaflet.css` | **BSD-2-Clause** | © 2010-2023 Volodymyr Agafonkin, © 2010-2023 Leaflet contributors |
| three.js（UMD 构建，r150–r159 代） | `apps/generator/assets/three.min.js` | **MIT** | © 2010-2023 Three.js Authors |

两者的版权与许可声明均保留在其文件头部（`@preserve` / `SPDX-License-Identifier: MIT`），请勿删除。

## 二、仓库【不包含】的衍生地图数据

以下文件**被 `.gitignore` 排除，不在本仓库中**。需要时请用 `tools/` 下的脚本自行生成。
这样处理是为了避免把受不同许可约束、且可能暴露具体地理位置的衍生数据一并再分发。

| 文件 | 生成方式 | 数据源与许可 |
|---|---|---|
| `apps/tester/assets/mapdata.js` | `python tools/fetch_osm.py` → `python tools/build_mapdata.py` | **© OpenStreetMap contributors，ODbL 1.0**。再分发或公开衍生数据库须署名并以相同方式共享 |
| `apps/*/assets/china_offline.js` | `python tools/build_china_offline.py` | **阿里云 DataV 行政区划边界**（`geo.datav.aliyun.com`）。该来源授权状态未明确，脚本注释已注明"仅建议个人学习/研究使用，公开发布前请自行确认授权"；且中国境内公开发布地图边界数据另涉审图号等要求。**自行评估后再发布** |
| `apps/*/assets/geo/countries.js`、`geo/land.js` | 需自行准备 | **Natural Earth**（公有领域，建议署名 naturalearthdata.com） |
| `apps/*/assets/geo/cities.js` | 需自行准备 | 字段格式（`n/z/y/x/p/c`＝名/中文名/纬/经/人口/国）为 **GeoNames** cities 数据集格式，**CC-BY 4.0**，必须署名 geonames.org |

## 三、运行时地图瓦片（不随仓库分发，由 App 在运行时请求）

| 瓦片源 | 说明 |
|---|---|
| 高德在线瓦片（`wprd0{s}.is.autonavi.com`） | 使用时受高德地图服务条款约束。本项目仅用于个人研究性测试，**未申请商用授权** |
| OpenStreetMap 标准瓦片 | © OpenStreetMap contributors，ODbL；遵循其瓦片使用政策 |

## 四、研究引用

若在论文或报告中引用本项目，请一并说明上述数据来源及其许可差异。
