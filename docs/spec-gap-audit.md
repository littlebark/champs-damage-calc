# 需求口径 vs 代码现状 审计 v2（2026-11-15）

> 对照对象：`main` @ `1a11331`（PR #1 合并，源提交 `d0f76b8 feat(android): 接入 PaddleOCR(PP-OCRv6) 并修复真机乱码`）。
> 上一版（v1）审计针对 `b9f4249` baseline 副本，结论全部作废——那一版看不到这批改动，因为提交当时只存在于远程 `master` 分支，尚未合并进 `main`。

## 0. 版本事实

- `main`：`1a11331`（Merge pull request #1 from littlebark/master）。
- `master`：`d0f76b8`（同一内容，PR 已合并）。
- 本次相比 `b9f4249`：26 个文件、+21538 / -404。**包含新增，不含任何文件删除**（`git diff --name-status` 无 `D` 项）。
- 新增关键件：`PaddleOcrEngine.kt`、`PokedexScreen.kt`、`OcrDebugReceiver.kt`、`assets/ocr/{det.onnx 9.9MB, rec.onnx 21.2MB, keys.txt}`、`TierDamageProfileTest.kt`、`docs/android_three_tier_ui_2026-11-13_zh.md`、`docs/ocr-paddle-2026-11-13_zh.md`、`tools/paddle_check.py`、`samples/*.png` 5 张。

## 1. 逐条对照（v2）

| # | 用户口径 | 代码现状（带证据） | 判定 |
| --- | --- | --- | --- |
| 1 | 最终形态是手机 APK | `android/android-app` Kotlin + Compose，`assembleDebug` 出包（82.7MB arm64 / 118.6MB x86_64，见三档文档 §2） | ✅ |
| 2 | 悬浮球 + 自动截屏 + OCR | `OverlayCaptureService`（MediaProjection 前台服务）、`BattleDirectOverlay`、`BattleOverlayController`、`OverlayWindowState/SafeArea/FocusController` | ✅ |
| 3 | OCR 依赖包替换 | 新增 `PaddleOcrEngine.kt`（PP-OCRv6，det/rec onnx + 1.8 万行 keys），`build.gradle.kts:299` 加 `onnxruntime-android:1.20.0`；ML Kit（`:297-298`）与 OpenCV（`:300`）保留作回退 | ⚠️ 部分：Paddle 已接入，旧依赖未摘（体积未瘦） |
| 4 | 招式选择页逐招出伤害、结果放招式左侧红框 | `BattleDirectHudElement.DAMAGE_1..4` 四个独立小窗，锚点 x=0.462 / 宽 0.212 / y=0.500·0.595·0.690·0.785；`OverlayCaptureService:1202` 选招页一键计算（识别我方+对手+四招→出三档） | ✅ |
| 5 | 三档假设：HP2 / HP32 / HP32+防或特防32+性格×1.1 | `TeamModels.kt:565-588 tierDefenderProfileSet()` 一次放 4 档：`tier.hp2`、`tier.hp32`、`tier.hp32-def32`(Bold)、`tier.hp32-spd32`(Calm)；`BattleOverlayController:902` 与计算器走 `tierDefenderProfile=true`；`TierDamageProfileTest` 覆盖 | ✅ |
| 6 | 物理取防御、特殊取特防 | 展示层按 `moveCategory` 在 3/4 档中取一（三档文档 §1.2；§2 抽查：物理招第 4 档无效、特殊招走第 4 档） | ✅ |
| 7 | 对方道具/特性未知→不计入 | `tierProfile()`（`TeamModels.kt:595-607`）只写 `level`/`statPoints`/`statAlignment`，不带 `ability`/`item` | ✅ |
| 8 | 例外：对方只有唯一特性时要代入 | 无任何"唯一特性"判定（全仓搜 `唯一特性 / abilities.size==1` 无命中） | ❌ 未实现 |
| 9 | 我方未录入加点→按 0 点由种族值推 | `BattleModels.kt:1060` 生成 `generated.default「无加点」`（`StatFields()` 全 0），`calculateStats(baseStats, points, nature)`（`:1183`）推导；计算器与我方承伤路径都用它与 `statsWithNatureMultiplier` | ✅ |
| 10 | 两入口最近原则（队伍页整队 vs 对战状态页单只） | `BattleOverlayController.recordOwnStats()`（`:175-197`）把状态页读到的 `actualStats/statPoints` 直接 `TeamRepository.updatePokemon` 覆盖该槽位，随后清缓存重算；`MainActivity:313/319` 已向用户明示该口径 | ✅ |
| 11 | 首页就是伤害计算器 | `AppTab` = `CALC / DEX / TEAM / BATTLE / SETTINGS`（`MainActivity:149`），默认 `AppTab.CALC`，标题「伤害计算器」 | ✅ |
| 12 | 第二 tab 宝可梦列表 + 详情（种族值/特性/招式） | `PokedexScreen.kt`：359 个形态，中英文搜索；列表显中文名/属性/种族值和/特性；详情含六维条形图、特性表、可学招式（分类筛选）；接口 `allForms/formFor/learnableMovesFor/moveCategoryFor` | ✅ |
| 13 | 删清原作者残留 | 文件全在：`OfficialTeamCodeProtocol.kt`、`ShowdownTeamCodec.kt`、`ShowdownTeamUi.kt`、`TeamCodeImport.kt`、`TeamCodeClientVersion.kt`、`TeamCodeIntegrity.kt`、`OpponentPresetTransfer.kt`、`UpdateManager.kt`；`build.gradle.kts:304` 仍有 `play:integrity:1.6.0`；`MainActivity:363/610/2247/2295` 仍引用队伍码/传输；构建仍有 `validateTeamCodeEntityMap`、`syncTeamCodeAssets` | ❌ 未动 |
| 14 | 包名改成自己的 bark | `build.gradle.kts:100/104` 仍 `com.crazylei12.pokemonchampionsassistant` | ❌ 未改 |

## 2. 与 v1 的差异（重要）

v1 判"未实现"的 #5/#6/#9/#10/#11/#12 六项，在 `1a11331` 上**全部已实现且已编译验证**（三档文档记录 `assembleDebug` + `testDebugUnitTest` 双通过）。v1 之所以看不到，是因为当时只查了 `refs/heads/main`，而提交在 `master` 分支上尚未合并。

仍未落地的只剩四项：**#8 唯一特性代入、#13 残留清理、#14 包名、#3 旧 OCR 依赖摘除**。

## 3. 剩余工作清单（按依赖顺序）

1. 改包身份：`namespace` / `applicationId` / 包目录 / Manifest 引用 / 应用 label；改完跑一次 `assembleDebug`。
2. 删残留（分三批，每批编译一次）：① `TeamCodeIntegrity.kt` + `OfficialTeamCodeProtocol.kt` 内 `TeamCodeIntegrityProvider` + `build.gradle` 的 `play:integrity`；② 队伍码 UI/编解码（`TeamCode*` + `ShowdownTeamCodec/Ui` + `MainActivity` 四处入口 + 构建 `validateTeamCodeEntityMap`/`syncTeamCodeAssets` + 4 个单测）；③ `UpdateManager` + `OpponentPresetTransfer`。
3. 唯一特性代入：在 `tierProfile` 生成时，若该物种特性集合只有 1 个，则写入 `ability`（四档保持一致）。
4. 体积优化（可选）：Paddle 稳定后摘 ML Kit 中文模型与 OpenCV。
5. 真机待验（三档文档明确未做）：图鉴滚动性能、三档数值、红框锚点在 16:9 / 20:9 实机校准。
