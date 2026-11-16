# 名字数据来源与刷新规则

## 名字怎么来的
- 唯一来源：`android/src/data/localization/zh-Hans.json`（由 `zh-Hans.overrides.json` + 42arch 数据集合成），本质是 **Pokémon Showdown 中文体系**：简体字形 + 台湾译名。
- `champions-presets.json`、`pokemon-icons/catalog.pokeapi-composite.json` 里的 `displayName` 都是这份表的拷贝，改名字要三处一起改，否则图鉴、预设、识别词典会不一致。
- 每个实体只有一个显示名，`aliases` 用来兜住旧名、官方繁体名、玩家俗写；匹配层（`OwnTeamRecognition`）就是靠 `aliases` 做容错的。

## 2026-11-16 全量核对官方简体名的结果
用 PokeAPI 的 `zh-hans` 官方名逐个比对本项目 359 形态 / 527 招式 / 166 道具 / 216 特性：

| 类别 | 差异数 | 处理 |
|---|---|---|
| 物种形态 | 6 物种 7 形态 | 显示名改官方简体，旧名进 aliases（`21b0e98`） |
| 招式 | 15 | 同上（`fa0bb75`） |
| 道具 | 5（仅全角 X/Y/Z 改半角） | 同上（`fa0bb75`） |
| 特性 | 0 | 无需改 |

物种：仆斩将军→仆刀将军、谜拟Ｑ→谜拟丘、死神棺→迭失棺、死神板→迭失板、流氓熊猫→霸道熊猫、狐大盗→猾大狐。
招式：空气之刃→空气斩、咒术→诅咒、森林咒术→森林诅咒、移花接木→欺诈、极落钳→断头钳、烈火深渊→炼狱、纠缠不休→死缠烂打、汲取→吸血、终焉之歌→灭亡之歌、绝处逢生→起死回生、流沙深渊→流沙地狱、可怕面孔→鬼面、玉石俱碎→自爆、深渊突刺→地狱突刺、磷火→鬼火。

## 抓 PokeAPI 的规矩（务必遵守）
1. **禁止逐条实体请求**。曾经为了比对 900 多个招式/道具名字，逐个调 `/api/v2/move/{id}`，一次性发出 900+ 请求，既慢又可能被当成爬虫封 IP。
2. 批量场景只用 GraphQL：`POST https://graphql.pokeapi.co/v1beta2`，一次就能拿到全表名字，例如
   ```
   { movename(where: {language_id: {_in: [12, 4]}}) { language_id name move { name } } }
   ```
   `zh-hans` = 12、`zh-hant` = 4；道具用 `itemname(... item { name })`，特性用 `abilityname(... ability { name })`。
3. 单实体查详情时也要带 `User-Agent`（不带会 403），并且把结果缓存成 JSON 复用，同一份数据不要重复抓。
4. 只做必要的比对：先看本地库和官方表差异条数，再决定要不要改数据，不要为了"确认没问题"反复拉全量。
