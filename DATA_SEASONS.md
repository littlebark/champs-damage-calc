# 赛季化数据方案（可演进）

冠军是赛季制：每赛季新增宝可梦 / 道具 / 特性，调整招式威力、属性与可学池，
并可能放出新 Mega / Mega Z。计算器数据必须能**按赛季切换、回溯历史、增量追加**，
而不是把一份静态表写死在代码里。

## 1. 数据分层（按变更频率从低到高）

| 层 | 内容 | 变更频率 | 来源 | 目录 |
| --- | --- | --- | --- | --- |
| L0 底座 | 主系列种族值骨架、招式静态信息（属性/分类/威力/命中/优先度）、道具与特性效果、相性表 | 极低（按 Showdown 版本冻结） | Pokémon Showdown 离线文件 | `data/raw/` |
| L1 冠军静态 | 冠军名单、形态（Base/Mega/Mega Z/地区/性别/其他）、属性、特性槽、反推种族值、**可学招式全集** | 随大版本/补丁 | championsbattledata.com `pokemon-index.json` + metadata CSV；`descriptions.json` | `data/raw/`（原始）→ `data/champions/species/`（派生快照） |
| L2 赛季规则 | 该赛季可用宝可梦/道具白名单、招式威力/属性补丁、可学池增删、特性/道具改动、新 Mega 石 | 每赛季 | index 各赛季 `battleSummary` + 补丁公告（champsdex / Bulbapedia） | `data/champions/<模块>/<season>.json` |
| L3 使用率 | Top 招式/道具/队友/性格/能力点/特性的出场率推荐 | 每期/每日 | index `summary.battleCurrent`、用户本地 `battle_data/` CSV | `data/champions/usage/<season>/<format>/<slug>.json` |

- L3 **不进伤害计算硬逻辑**，只用于界面默认配招推荐；改使用率不影响伤害数值。
- L1 的「可学招式全集」是下拉列表的来源；L2 可在其上做赛季封禁/解禁（如某招式该赛季不可用）。

## 2. 目录与文件职责

```
data/
├── raw/                                  # 原始下载，只读归档，不改写
│   ├── showdown/                          # L0 底座快照（pokedex/moves/items/abilities/typechart/learnsets）
│   ├── champions-pokemon-index-<ver>.json # L1+L2+L3 原始 index（站点 dataVersion/generatedAt）
│   ├── champions-descriptions.json        # 道具/特性英文描述文本
│   └── metadata/                         # 按宝可梦分的 metadata CSV（按需批量下载）
└── champions/
    ├── manifest.json                     # 赛季登记表（默认赛季、继承关系、各快照指针、数据版本）
    ├── species/<season>.json             # L1/L2：宝可梦+形态+种族+属性+特性+可学池（派生）
    ├── moves-overrides/<season>.json     # L2：招式补丁（覆盖底座威力/属性/分类）
    ├── items/<season>.json               # L2：道具白名单与冠军专属道具效果
    ├── abilities/<season>.json           # L2：特性冠军调整与新特性
    └── usage/<season>/<format>/<slug>.json  # L3：使用率配招推荐（派生）
```

### manifest.json 结构

```json
{
  "defaultSeason": "Current",
  "base": {
    "showdownSnapshot": "2026-11-13",
    "championsIndexDataVersion": "<index.dataVersion>",
    "championsIndexGeneratedAt": "<index.generatedAt>"
  },
  "seasons": [
    {
      "id": "Current",
      "label": "当前赛季",
      "regulation": "M-A",
      "live": true,
      "inheritsFrom": null,
      "sourceDataVersion": "<index.dataVersion>",
      "fetchedAt": "2026-11-13",
      "roster": "species/Current.json",
      "moves": "moves-overrides/Current.json",
      "items": "items/Current.json",
      "abilities": "abilities/Current.json",
      "usageDir": "usage/Current/"
    },
    {
      "id": "M7",
      "label": "第 7 赛季",
      "regulation": "M-B",
      "live": false,
      "inheritsFrom": "M6",
      "roster": "species/M7.json",
      "moves": "moves-overrides/M7.json",
      "items": "items/M7.json",
      "abilities": "abilities/M7.json",
      "usageDir": "usage/M7/"
    }
  ]
}
```

站点原生赛季代号：`Current, M7, M6, M5, M4, M3, M2, M1`（见 index 顶层 `seasons`）；
规则集代号为 Regulation M-A / M-B / M-C，登记到 `regulation` 字段，与赛季 id 解耦。

## 3. 赛季继承与增量追加

1. **新赛季落地**：拉取新版 `pokemon-index.json` 归档到 `raw/`（带 dataVersion，不覆盖旧档）。
2. **派生快照**：`build` 脚本从 raw 解析出该赛季的 species / items / abilities / usage 快照。
3. **diff 上一赛季**：对比 `inheritsFrom` 指向的旧快照，产出三类变更并写入新赛季文件：
   - 新增：首次进入池子的宝可梦 / 道具 / 特性 / Mega 石；
   - 移除：该赛季禁用或退环境条目；
   - 改动：种族值、属性、特性、可学池、招式威力/属性发生变化。
4. **历史只读**：旧赛季快照与 usage 文件永久保留，可回溯任意历史赛季的计算与推荐；
   新版本只追加新赛季文件，不回填改写旧文件。
5. **回退链（查询时）**：某条目按 `L2 赛季补丁 → L1 冠军静态 → L0 Showdown 底座` 逐级查找，
   命中即止；冠军原创形态/特性/道具底座没有时，直接由 L1/L2 提供。

## 4. 采集与派生流水线（待脚本化）

```
fetch（下载，归档到 raw/，记录 dataVersion/日期）
  └─ index.json：名单、形态、可学池、各赛季使用率
  └─ metadata CSV：属性、特性、体重、默认能力值
  └─ descriptions.json：道具/特性描述文本

build（raw → champions/ 派生快照 + diff + 更新 manifest）
  ├─ species/<season>.json   反推种族值、合并形态、挂可学池
  ├─ moves-overrides/...     补丁表人工/公告录入
  ├─ items/abilities/...      底座效果 + 冠军专属条目
  └─ usage/...               Top10 配招转结构化推荐

verify（对照截图 / 现成计算器反推校验数值）
```

## 5. 已确认的冠军数据事实（本轮探查）

- index 共 **263** 只宝可梦；`learnableMoveNames` 去重后 **511** 个招式，**全部**能在
  Showdown `moves.json` 命中——冠军未引入全新招式，招式静态信息可由 L0 底座提供，
  差异只在可学名单与招式补丁。
- 263 只 `showdownId` **全部**能在 Showdown pokedex 命中。
- 形态类型：Base、Mega、Mega Z、地区形态（Hisui/Alola/Galar/Paldea）、性别、
  罗トム/泡沫栗鼠等多形态、Mega X/Y 等；冠军原创 Mega / Mega Z 底座无对应条目。
- metadata/index 里的 hp/atk/... 是 **0 能力点、平衡性格下的默认能力值**，不是种族值；
  种族值反推：`HP 种族 = 默认 HP − 75`，其余 `种族 = 默认能力值 − 20`。
- Base 形态反推种族与底座多数吻合（如炽焰咆哮虎 95/115/90/80/90/60 完全一致）；
  少量地区形态条目与底座存在偏差，按冠军反推值为准，底座仅作参考。
- `descriptions.json` 含 `items`（含 Abomasite、Absolite、Absolite Z 等 Mega 石 / Z 石）
  与 `abilities` 两个英文字典；效果数值仍以底座解析 + 冠军覆盖为准。

### 5.1 第二数据源（社区结构化数据集，交叉校验）

仓库 `otterlyclueless/pokemon-champions-data`（CC BY 4.0），归档在 `data/raw/community/`，
来源 Showdown + Serebii Champions 页（2026-04-16，v1.1.0）。三源对账结论：

- **种族值**：可配对 Base 形态与 index 反推 0 不一致 → 反推公式（HP−75 / 其余−20）成立；
  此前 86 项偏差主要是地区形态 slug 错配，非真实种族差。
- **名单**：index 263 条（变体 slug）vs community 258 条（形态拆独立条目）；
  落库键 = `基础名 + form`，不要用纯名字匹配。
- **可学招式**：184 只可配对，80 只集合有出入；两源取交集为可信基线，差集待游戏内截图仲裁。
- **招式补丁线索**：community `moves.json` 带 `inChampions`（494 可用 / 406 不可用）、
  PP / 威力 / 命中改动统计（威力 12、PP 399、命中 2），供 L2 `moves-overrides` 录入。
- **不采纳项**：其 `stat-formula.md` 用主系列旧模板且自述 SP/IV 映射未核实；
  能力值公式以截图校验版为准。该源无赛季维度。

### 5.2 第三数据源：参考助手 v18 客户端挖掘快照

仓库 `crazylei12/Pokemon-Champions-dmg_cal`（MIT），快照在 `data/ref/v18/`，
复用边界见 `reference/REUSE_NOTES.md`。版本：客户端 1.2.0 / 主数据 v18 / learnset 2026-09-09。

- **种族值**：396 形态直接给 `baseStats`，无需从默认能力值反推；219 只 Base 与 index 反推 0 不一致。
- **可学池**：每形态 `moveNumbers`（waza_learn ∩ available），358 形态与 index 仅 5 只差一个 `Slash`，待仲裁。
- **道具/特性**：166 道具、215 特性编号→showdownId；`presets.json` 含 408 个推荐配置与中英性格、招式元数据。
- **中文映射**：`zh-Hans.json` 1313 条 `canonicalId/showdownId/zh-Hans/aliases`，解决界面中文 ↔ 内部 ID。
- **静态事实优先 v18**（客户端挖掘），**赛季与使用率以 index 为准**；版本号随快照登记进 manifest。
- **不接入**：Android OCR/悬浮窗/截图服务、打包后的 `damage-engine.js`；契约字段与设计文档仅作参照。

### 数据源优先级（查询回退链的人工口径）

1. 截图 / 游戏内实测（最高仲裁）；
2. v18 客户端挖掘快照（静态种族值/可学池/道具白名单，注意其 2026-09-09 版本）；
3. championsbattledata index（赛季维度、最新使用率、形态全集，与 v18 互相校验静态事实）；
4. community 数据集（结构化招式/特性/道具/性格，独立 learnset 校验）；
5. Showdown 底座（招式静态信息、相性表、主系列骨架）；
6. 百科 / 第三方计算器（仅作旁证与反推校验）。

## 6. 待办

- [ ] 解析 `items.ts` / `abilities.ts`，把底座效果数值接入 L0；冠军专属条目进 L2。
- [ ] 写 `fetch` 脚本：批量下载 metadata CSV、community 数据集并归档到 `raw/`。
- [ ] 写 `build` 脚本：从 v18 快照 + index + community 派生各赛季 species/items/abilities/usage 快照并做赛季 diff；种族值优先取 v18 `baseStats`。
- [ ] 招式补丁表录入（威力/属性/分类/PP 改动、`inChampions` 标记）。
- [x] 中英文名映射接入 `catalog.py`（复用 `data/ref/v18/zh-Hans.json`，1313 条）；index 兜底条目（如 Aegislash 盾/剑形态）中文名待补前缀匹配。
- [ ] 仲裁 learnset 差集：community 80 只出入 + v18 5 只 `Slash` 出入，逐条核对标注以哪一源为准。
- [ ] 核对地区形态 slug 错配（Arcanine-Hisui 等），按 `基础名 + form` 归并条目。
