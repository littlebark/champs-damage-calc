# Pokémon Champions 伤害计算器（本地计算框架）

> **当前方向（2026-11-13 更新）**：最终产物是 Android APK；本仓库早期的本地网页计算器仅保留作数据正确性校验，不再加功能。环境状态、目录地图、构建命令与续作待办见 **`docs/HANDOFF.md`**；移植/裁剪清单见 `docs/android-port-plan.md`。下方历史记录为网页阶段调研与内核说明，口径以 HANDOFF 为准。

先不做 APK，搭一个可离线跑通的计算骨架：数据层 → 能力值推算 → 伤害管线 → 结果区间。
核心公式已用两张参考截图里的真实数值校验通过。

---

## 1. 背景：这是什么游戏

《Pokémon Champions》（宝可梦冠军 / ポケモンチャンピオンズ）是 2026 年发售的官方旁支对战游戏
（Switch / iOS / Android），对战机制沿用主系列第九世代，但有自己的一套养成规则。
它**不是**盗版改版；网上可查的冠军专属数据（名单、种族值、招式调整、可学招式限制）需要在主系列数据之上做一层覆盖。

### 与主系列的关键规则差异（决定了计算器不能直接套主系列公式）

| 项目 | 主系列（Gen9） | Champions |
| --- | --- | --- |
| 等级 | 50 / 100 等 | 全部按 **50 级**计算 |
| 个体值 IV | 0–31 可培养 | **固定 31**，不可改，不进公式 |
| 努力值 EV | 0–252/项，合计 510 | 取消，改为**能力点数 Stat Points**：单项 0–32，六维合计上限 **66** |
| 能力值公式 | 含 EV/IV/等级的长公式 | 见下方「能力值公式」 |
| 招式/特性/道具 | 主系列池 | 部分招式威力/属性调整、部分可学招式被封禁、新增 Mega 与道具 |

### 能力值公式（已与截图数值逐一核对）

```
HP 能力值  = 种族值 + 能力点数 + 75
其他能力值 = ⌊(种族值 + 能力点数 + 20) × 能力调整⌋
```

- 能力调整：性格（或薄荷）提升项 ×1.1，下降项 ×0.9，其余 ×1.0；HP 不受性格修正。
- 校验样例（截图1 炽焰咆哮虎，慎重 +特防 -特攻）：
  HP 95+32+75=**202**；攻击 ⌊(115+0+20)×1.0⌋=**135**；防御 ⌊(90+14+20)⌋=**124**；
  特攻 ⌊(80+0+20)×0.9⌋=**90**；特防 ⌊(90+20+20)×1.1⌋=**143**；速度 ⌊(60+0+20)⌋=**80**。

### 伤害基础公式（Gen9 / Champions 同款管线，等级固定 50）

```
base = ⌊ ⌊ ⌊(2·L/5 + 2) · Power · Atk / Def⌋ / 50 ⌋ + 2 ⌋
```

- 物理招式取 攻击/防御；特殊招式取 特攻/特防。
- 暴击 ×1.5，并无视攻击方负向阶段、防御方正向阶段与墙。
- 随机 16 档：系数 (85+n)/100，n=0..15；伤害下限 1。

### 修正链顺序（参照 @smogon/calc 与 52poke 伤害页）

多目标(双打×0.75) → 帮助(×1.5) → 天气 → 暴击 → 随机档 → STAB(×1.5) → 属性相性 →
烧伤(物理×0.5) → 反射壁/光墙/极光幕(单打×0.5，双打×2/3) → 友谊防守(×0.75) → 场地 → 道具/特性钩子。

- 天气：晴天 火×1.5 / 水×0.5；雨天 水×1.5 / 火×0.5；沙暴/雪为防御侧加成（留扩展）。
- 场地：电气/青草/精神场地对应属性 ×1.3（攻击方站地面）；薄雾场地龙系对地面目标 ×0.5。
- 能力等级倍率：正向 (2+stage)/2，负向 2/(2-stage)，stage ∈ [-6,+6]。

---

## 2. 数据源调研

### 2.1 开源底座（主系列数据，可离线下载，已取到 `data/raw/`）

| 数据 | 来源 | 文件 | 用途 |
| --- | --- | --- | --- |
| 宝可梦种族值/属性/特性 | Pokémon Showdown `data/pokedex.json`（仓库 `data/pokedex.ts`） | `pokedex.json` | 六维种族值、双属性、特性槽 |
| 招式 | Showdown `data/moves.json`（`moves.ts`） | `moves.json` | 属性、物理/特殊、威力、命中、优先度、标志 |
| 道具 | Showdown `data/items.ts` | `items.ts` | 道具效果（属性增倍/树果/生命宝珠等，待解析） |
| 特性 | Showdown `data/abilities.ts` | `abilities.ts` | 特性效果（待解析挂接） |
| 属性相性表 | Showdown `data/typechart.ts` | `typechart.ts` | 18+ 属性相克倍率 |
| 可学招式 | Showdown `data/learnsets.ts` | `learnsets.ts` | 主系列 learnset（待解析；冠军封禁名单另需覆盖） |
| 性格表 | Showdown `data/natures.ts` | （未下载） | ±10% 性格对照，框架内已内置中文表 |

仓库：<https://github.com/smogon/pokemon-showdown>
公开 JSON 直链：`https://play.pokemonshowdown.com/data/pokedex.json`、`/moves.json`
原始 TS：`https://raw.githubusercontent.com/smogon/pokemon-showdown/master/data/<file>.ts`

### 2.2 伤害计算参照实现（成熟、MIT 协议）

- **`smogon/damage-calc`** → npm 包 **`@smogon/calc`**：世代化伤害引擎，
  `calculate(gen, attacker, defender, move, field)` 返回伤害 16 档、区间、描述、反伤/吸血、KO 概率。
  <https://github.com/smogon/damage-calc>
- **`@pkmn/sim` / `@pkmn/data`**：Showdown 模拟器与数据层的轻量 npm 封装，可作为替代数据源。
- 百科核对：神奇宝贝百科 `wiki.52poke.com`（能力 / 伤害 / 天气 / 场地条目）、Bulbapedia `Pokémon Champions` 条目。

### 2.3 Champions 专属数据（已接入 championsbattledata.com 公开 index）

站点 <https://championsbattledata.com/> 提供静态 JSON，已下载到 `data/raw/`：

| 文件 | 内容 | 用途 |
| --- | --- | --- |
| `champions-pokemon-index.json`（16.4MB） | 263 只：`showdownId`、`learnableMoveNames`（可学招式全集）、`summary.forms`（形态/属性/特性/默认能力值）、各赛季 `battleSummary` 使用率推荐 | 招式下拉列表、形态与特性槽、反推种族值、默认配招推荐 |
| `champions-descriptions.json` | `items` / `abilities` 两个英文字典（含 Mega 石 / Z 石、新特性） | 道具/特性描述文本；效果数值仍由底座解析 + 冠军覆盖 |

探查结论：511 个可学招式全部命中 Showdown `moves.json`（冠军无全新招式）；
263 只 `showdownId` 全部命中 Showdown pokedex。index 内 hp/atk/... 是 0 点数、平衡性格的
**默认能力值**，种族值反推：HP 种族 = 默认 HP − 75，其余 = 默认能力值 − 20。

补充采集源（补丁 / 新道具特性 / 对照校验）：

| 内容 | 来源 | 备注 |
| --- | --- | --- |
| 招式威力/属性调整、平衡性补丁 | champsdex.com 补丁表；Bulbapedia Champions 条目 | 如 First Impression 90→100、Apple Acid 80→90、Snap Trap 草→钢 等 |
| 新 Mega 石 / 新道具 / 新特性 | descriptions.json + Bulbapedia、AppMedia 日站 | 随规则集（Regulation Set）分批追加 |
| 现成冠军计算器对照 | `pokestats.top/calc`、op.gg 图鉴内计算、gamewith.ai 计算器 | 用于反向校验我们的输出 |

### 2.4 第二数据源：社区结构化数据集（交叉校验）

仓库 <https://github.com/otterlyclueless/pokemon-champions-data>（CC BY 4.0），
已下载到 `data/raw/community/`：`pokemon/roster.json`、`pokemon/base-stats.json`、
`learnsets/learnsets.json`、`moves/moves.json`（含 `inChampions` 标记、PP、target）、
`abilities/`、`items/`、`natures/`、`type-chart/`、`meta/version.json`。

来源为 Showdown 底座 + Serebii.net Champions 页面抓取（2026-04-16，版本 1.1.0）。
**定位：第二校验源，不单信。** 已完成三源对账（`tests/reconcile_sources.py`）：

- 种族值：可配对的 Base 形态，community 与 index 反推 **0 不一致**，反推公式得到旁证；
- 名单口径：index 263 条（含 `-hisui/-f` 等变体 slug），community 258 条（Mega/地区形态拆成独立条目），
  匹配需按「基础名 + form」归一化；
- learnset：184 只可配对中 80 只招式集合有出入（index 多 `Psychic/Slash` 等，community 多部分岩石招），
  需游戏内截图仲裁，差异入待核对清单；
- 招式池：index 可学 511 vs community 标冠军可用 494；community 标注了 406 个不可用招式与
  12 个威力改动、399 个 PP 改动，可作为 L2 招式补丁层的线索。

注意：该仓库 `mechanics/stat-formula.md` 仍用主系列旧公式模板，且自述「SP→EV 映射、IV 映射未核实」，
**能力值公式以我们截图校验过的冠军公式为准**，不采纳其文档。该仓库无赛季维度，
赛季白名单/使用率仍以 championsbattledata index 为准。

### 2.5 第三数据源：参考助手项目的 v18 客户端挖掘快照

仓库 <https://github.com/crazylei12/Pokemon-Champions-dmg_cal>（MIT），已浅克隆到 `reference/`，
提取的快照在 `data/ref/v18/`，复用边界与对账见 `reference/REUSE_NOTES.md`。

- `champions-roster.v18.json`：396 形态，直接给**权威种族值**、属性、体重、特性编号（215）；
- `champions-moves.v18.json`：512 招编号映射 + 每形态可学编号列表（客户端 waza_learn ∩ available）；
- `champions-items.v18.json`：166 道具白名单；
- `champions-presets.json`：160 物种 / 408 推荐配置（点数/实际能力/性格/特性/道具/招式）、中英性格、招式元数据；
- `zh-Hans.json`：1313 条中→英 ID 映射（species/move/ability/item/nature/type）。

对账（`tests/reconcile_ref_v18.py`）：219 只 Base 种族值与 index 反推 **0 不一致**；
358 形态可学池仅 5 只差一个 `Slash`，待仲裁。版本：客户端 1.2.0 / 主数据 v18 / 2026-09-09。
其 Android OCR、悬浮窗与打包伤害引擎本轮不接入；契约模型与设计文档作为我们界面/数据层的参照。

赛季化数据分层、目录与增量追加流程见 **`DATA_SEASONS.md`**。
覆盖层结构样例见 `data/champions/champions_overrides.example.json`：`species` / `moves` / `learnset` 三块，
查询时优先于 Showdown 底座。

---

## 3. 输入要素清单（对照两张参考截图）

### 攻击方
- 宝可梦（物种、双属性、可选「更改属性」）
- 等级（固定 50）、性格（±10%）、特性（含是否发动，如激流 HP≤1/3 水×1.5）
- 道具（属性增倍 ×1.2、生命宝珠 ×1.3、达人带 ×1.2、减伤果 ×0.5 等）
- 能力点数 0–32（六维，合计 ≤66）、能力等级 -6..+6
- 状态：烧伤（物理 ×0.5）、帮助（×1.5）
- 同伴特性：力量点 / 蓄电池 / 钢之意志 / 正电 / 负电（留接口）
- 招式：选招（威力、属性、物理/特殊、命中、暴击标记）

### 防御方
- 宝可梦（物种、双属性、更改属性）
- 种族值 → HP、防御/特防能力值；能力点数、性格修正、能力等级
- 特性（粗糙皮肤/沙隐等）、道具（减伤果 ×0.5 等）
- 状态：反射壁 / 光墙、友谊防守

### 全局 / 场地
- 单打 / 双打；单体 / 多体（多目标 ×0.75）
- 天气：无 / 晴 / 雨 / 沙 / 雪
- 场地：无 / 电气 / 青草 / 精神 / 薄雾
- 重力（影响地面/飞行判定，留接口）

---

## 4. 框架结构

```
champs-damage-calc/
├── data/
│   ├── raw/                       # 原始下载，只读归档
│   │   ├── pokedex.json           # L0 底座：种族值/属性/特性
│   │   ├── moves.json             # L0 底座：招式
│   │   ├── items.ts / abilities.ts # L0 底座：道具/特性（待解析）
│   │   ├── typechart.ts           # 相性表
│   │   ├── learnsets.ts           # 主系列 learnset（待解析）
│   │   ├── champions-pokemon-index.json   # L1/L2/L3 冠军 index
│   │   ├── champions-descriptions.json   # 道具/特性描述文本
│   │   └── community/             # 第二数据源（otterlyclueless 结构化数据集，交叉校验）
│   └── champions/
│       ├── manifest.json          # 赛季登记表（默认赛季/继承关系/快照指针）
│       ├── species/  moves-overrides/  items/  abilities/  usage/   # 各赛季派生快照
│       └── champions_overrides.example.json
├── src/
│   ├── models.py                  # PokemonInput / Move / Field / Battle 数据模型
│   ├── stats.py                   # 冠军能力值公式、性格表、能力等级倍率
│   ├── typechart.py                # 相性表解析与复合属性倍率
│   ├── dataset.py                  # L0 底座 + 覆盖层合并的数据加载层
│   ├── champions_data.py           # L1 冠军 index 加载（形态/可学池/反推种族/使用率）
│   ├── catalog.py                  # 统一门面：v18 静态事实 + index 兜底 + 底座招式 + 中文映射
│   ├── modifiers.py                # 道具/特性/暴击修正显式注册表
│   ├── i18n.py                     # 天气/场地/分类静态中文表
│   └── damage.py                   # 伤害管线：基础伤害 + 修正链 + 16 档 + KO 概率
├── scripts/
│   └── build_manifest.py           # 从 index 派生赛季 manifest
├── examples/
│   └── quickstart.py              # 最小可跑示例
└── tests/
    ├── verify_screenshot.py        # 截图真实数值校验
    ├── verify_champions_index.py    # 冠军 index 加载与种族值对照
    └── reconcile_sources.py         # 三源对账（community vs index vs Showdown）
```

### 已实现 / 待接入

- [x] 冠军能力值公式（HP / 其余五维，性格 ±10%，点数上限）
- [x] 相性表解析与双属性相乘、免疫判定
- [x] 伤害基础公式、STAB、相性、暴击、烧伤、墙、帮助、友谊防守、天气、场地、多目标
- [x] 16 档随机伤害区间、OHKO 档数占比
- [x] Showdown pokedex / moves 查询链路
- [x] 冠军 index 接入：可学招式全集、多形态、属性/特性槽、反推种族值、赛季使用率推荐
- [x] 第二数据源（社区结构化数据集）下载并三方对账；种族值 0 偏差，learnset/招式池差异入清单
- [x] 赛季化目录与 manifest（`DATA_SEASONS.md`），8 个赛季登记与继承链
- [x] 统一接入层 `catalog.py`：v18 静态事实优先、index 兜底、底座招式信息、zh-Hans 中英文名；搜索/取物种/取招式/可学池/道具/特性接口；端到端闭环复算截图数值通过
- [x] 本地 Web 原型 `web/`：标准库后端 API + 单页前端，手机上下堆叠/宽屏两栏，攻守互换，搜索宝可梦与可学招式，伤害区间/百分比/OHKO 概率；截图样例浏览器内回归通过
- [x] 天气/场地/属性/分类中文显示；能力等级（攻/特攻、防/特防 ±6）输入与管线；`src/modifiers.py` 显式注册表挂接常见道具、攻击/防御特性与暴击特性修正
- [ ] items.ts / abilities.ts 解析与特性/道具修正挂接（大力士、适应力、生命宝珠、达人带、属性果、各类同伴特性等）
- [ ] build 脚本：从 v18 + index/community 派生各赛季 species/items/abilities/usage 快照并做赛季 diff
- [ ] 招式补丁表录入（威力/属性/分类/PP 改动、inChampions 标记）
- [ ] 防御侧沙暴/雪的防御加成、地面/飞行判定（重力、浮游）、守住/穿透、吸血/反伤/回合结束伤害
- [ ] 多招对比、全攻击面遍历、概率 2HKO/3HKO 统计

### 运行

```powershell
# 截图样例校验（应全部 OK）
python tests/verify_screenshot.py

# 接入层 + 端到端闭环校验（Catalog → 内核 → 伤害区间）
python tests/verify_catalog.py

# 道具 / 特性 / 能力等级修正项校验
python tests/verify_modifiers.py

# 特性改属性 / 免疫特性管线校验
python tests/verify_type_change.py

# 三源 / v18 对账
python tests/reconcile_sources.py
python tests/reconcile_ref_v18.py

# 最小示例
python examples/quickstart.py
```

### Web 计算器原型

本地 Python 标准库后端 + 单页前端，复用 Catalog 与伤害内核，无需 Node/第三方依赖：

```powershell
python web/server.py
# 打开 http://127.0.0.1:8000
```

- `web/server.py`：API —— `GET /api/meta`（含天气/场地中文选项）、`GET /api/species?q=`（计算器搜索）、`GET /api/species/all`（列表全量摘要）、`GET /api/species/<key>`（详情：种族值/特性/招式池）、`GET /api/items`、`POST /api/calculate`
- `web/index.html`：app 三视图 tab —— ①伤害计算器：手机上下堆叠（攻击方在上、防御方在下），宽屏两栏；搜索宝可梦与可学招式、性格、点数、能力等级（攻/特攻、防/特防 ±6）、持有道具、特性、天气/场地/墙/帮助/烧伤/暴击，输出伤害区间、百分比与 OHKO 概率；天气、场地、属性、分类均显示中文。②宝可梦列表：搜索过滤 + 卡片网格。③详情：种族值六维、属性、特性、可学招式表（属性/分类/威力/优先度）。
- 已挂接的修正（`src/modifiers.py` 显式注册表，未收录条目标注待接入）：
  - 道具：生命宝珠 ×1.3、讲究头带/眼镜 ×1.15（物理/特殊）、达人带克制 ×1.2、力量头带物理 ×1.1、属性增强道具 ×1.1；抗性浆果（克制或普通系招命中对应属性）×0.5，成熟特性 ×0.25；
  - 攻击特性：大力士/瑜伽之力物理 ×2、活力物理 ×1.5、毅力异常物理 ×1.5、技术高手低威力 ×1.5、铁拳拳招 ×1.2、硬爪接触招 ×1.3、适应力 STAB ×2、狙击手暴击 ×1.5、激流/猛火/茂盛/虫预感残血对应属性 ×1.5；
  - 攻击特性（招式标记/属性/天气）：超级发射器(波)/强壮之颚(咬)/锋锐(切) ×1.5、钢之意志钢系/火焰鬃毛火系/水泡水系 ×1.5、沙之力沙暴岩/地/钢 ×1.3、庞克摇滚声音招 ×1.3、太阳之力晴天特殊 ×1.5、强行(带附加效果招)×1.3；
  - 交互特性：斗争心 同性 ×1.25 / 异性 ×0.75（性别输入，任一未知不修正）；分析 后手 ×1.3（同优先度按双方速度自动判定，可手动「发动 / 未发动」覆盖）；
  - 防御特性：坚硬岩石/过滤克制 ×0.75、多重鳞片满血 ×0.5、厚脂肪/耐热火/洁净之盐幽灵/水泡火 ×0.5、毛皮大衣物理 ×0.5、毛茸茸/波导防护接触招 ×0.5、庞克摇滚声音招 ×0.5、草之毛皮青草场地物理 ×2/3、神奇鳞片异常物理 ×2/3；毛茸茸受火 ×2、干燥皮肤受火 ×1.25；
  - 特性改属性：湿润之声（声音招→水）、妖精/冰冻/飞行/电/龙皮肤（一般招→对应属性并 ×1.2）；
  - 招式改属性：气象球（晴火/雨水/沙岩/冰雪，有天气 BP×2）、场地脉冲（电电/草草/雾妖/灵超，有场地 BP×2）；
  - 免疫特性：飘浮/鳗鳗高升(免地面)、蓄电/电气引擎/避雷针(免电)、储水/引水/干燥皮肤(免水)、引火(耐火)、食草(免草)、食土(免地面)、防弹(免子弹招)、隔音(免声音招)，伤害记 0；
  - 天气：晴火×1.5/水×0.5、雨水×1.5/火×0.5；沙暴岩石系特防 ×1.5（仅特殊招）、雪天冰系物防 ×1.5（仅物理招，对照 champions.ts）；
  - 能力等级：正向 (2+stage)/2、负向 2/(2−stage)，暴击无视攻方负阶与防方正阶；
  - 速度威力招：电球按攻/守速度比 ≥4→150、≥3→120、≥2→80、≥1→60、否则 40；螺旋球 min(150, ⌊25·守/攻⌋+1)；结果区回显双方速度能力值。
- 已用截图样例回归：西狮海壬月亮之力打烈咬陆鲨 200~236，OHKO 9/16（56.2%）；生命宝珠 260~306、特攻 +1 级 300~354、防御 +1 级 134~158。
- 机制参考：参考仓库 `crazylei12/Pokemon-Champions-dmg_cal` 直接复用 smogon 官方 damage-calc 引擎（天气/特性/道具/相性/免疫由引擎内置判定）；本项目为自写 Python 内核 + 显式注册表，规则条目对照 smogon/Showdown 伤害管线归类。未收录条目不臆造倍率，下拉仅展示、不挂数值。
- 暂未挂接：2HKO/3HKO 概率、多招对比/全攻击面遍历、神秘守护（只受克制伤害）、吸收类特性回血；需同伴/残援计数/体重/优先级/重力/特性触发开启的条目——正电/负电、残援霸主、舍身(反冲)、避雷针/引水攻方触发、气球道具免地面、优先招守备(女王的威严/尾甲)、重力地面判定、节拍器计数、轻球皮卡丘、按威力变化的招(报恩/低踢/草结绳/重量技/连签等)；其余未收录特性按待接入处理；悬浮 OCR 暂缓。
- 译名口径：特性/道具中文名一律取 `data/ref/v18/zh-Hans.json` 对照表；对照表未收录的少数条目（引水、电皮肤、气球）在 `catalog.FALLBACK_ZH` 兜底，取主系列通用官方译名，回补对照表后应移除兜底。

---

## 5. 两条实现路线

**路线 A：自写计算内核（当前骨架，Python）**
- 数据用 Showdown 离线文件，公式参照 `@smogon/calc` 的顺序；不依赖 Node，后续易移植到 Web/APK。
- 代价：特性/道具覆盖面要自己逐个补，冷门判定需自行对照百科校验。

**路线 B：直接复用 `@smogon/calc` 作为计算后端（Node/TS）**
- 成熟引擎已校验海量对战伤害、特性道具、KO 概率；数据可接 `@pkmn/data`。
- 适配点：calc 内置主系列 EV/IV 能力值计算，需要在构造 `Pokemon` 时直接传入按冠军公式算好的能力值，
  或实现一个自定义 data layer；冠军差异仍走覆盖层数据。
- 适合后续做正式 Web/APP 时提高准确度、减少自研维护面。

建议：先用当前 Python 骨架把输入结构和冠军公式跑顺（已完成核心闭环）；
等特性/道具清单铺开后，再评估是否把伤害内核切换到 `@smogon/calc`。

---

## 6. 风险与待确认

- **冠军版本随规则集更新**：宝可梦/招式/道具分批追加，覆盖层需要留版本字段，便于按 Regulation Set 筛选。
- **中文命名**：Showdown 数据为英文 slug，截图与游戏为中文；需要一张中→英映射表（可从 op.gg 中文站与 52poke 对齐）。
- **未覆盖机制**：Mega 进化/Omni Ring、太晶/Z/极巨（官方说后续开放）、状态异常副作用、特性触发顺序等，首轮伤害计算可不纳入，但模型要预留字段。
- **取整细节**：引擎同时使用向下取整与五舍六入（half-down），不同节点取整方式不同；`damage.py` 已区分 `floor` 与 `poke_round`，后续补特性道具时需逐项核对取整节点。
