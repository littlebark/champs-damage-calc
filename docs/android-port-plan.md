# Android APK 移植方案与裁剪清单

目标：把现有本地网页计算器沉淀为手机端 APK。网页计算器仅保留作为数据正确性校验，不继续加功能。最终 APK 为本地优先：悬浮球入口、自动截屏 OCR、本地队伍/对手档案、逐招伤害估算。防御按最硬假设（HP32 + 对应防御项32 + 性格×1.1）。

参考工程：`reference/Pokemon-Champions-dmg_cal`（MIT，Kotlin + Jetpack Compose，Android 13+，WebView 跑打包 JS 引擎，MediaProjection 截屏 + ML Kit/OpenCV 识别）。

## 1. 可复用模块（移植到本项目独立 android 工程）

| 能力 | 参考文件 | 说明 |
| --- | --- | --- |
| 权限与授权流 | `MainActivity.kt`（悬浮窗设置跳转、MediaProjection launcher、通知权限） | 悬浮窗 `SYSTEM_ALERT_WINDOW`、截屏 `FOREGROUND_SERVICE_MEDIA_PROJECTION`、`POST_NOTIFICATIONS` |
| 截屏采集服务 | `OverlayCaptureService.kt`、`CaptureBufferSpec.kt`、`OverlayWindowState/SafeArea/FocusController` | 前台服务持有 projection，悬浮窗 + 帧采集 |
| 悬浮球/面板 | `BattleDirectOverlay.kt`、`BattleOverlayController.kt`、`OverlayFocusController.kt` | 可拖动悬浮球、展开面板、HUD 布局 |
| 引擎 bridge | `DamageEngineRuntime.kt`、`assets/engine-host.html`、`assets/damage-engine.js` | WebView 加载本地打包引擎，Kotlin ↔ JS 调用契约 |
| 识别：队伍预览 | `TeamPreviewRecognition.kt`、`TeamPreviewViewportMapping.kt`、`OwnTeamRecognition.kt` | 开局双方阵容（仅物种/属性/性别）与我方队伍识别 |
| 识别：OCR | `src/recognition/ocr.ts`、`tools/recognition/rapidocr-json.py`、ML Kit 中文/拉丁依赖 | 画面文字识别；图标模板匹配走 `templates-v2` |
| 本地存档 | `AppStorage.kt`、`AppDataBackup.kt`、`OpponentUserPresetStore.kt` | 队伍档案、对手预设本地持久化 |
| 数据模型 | `BattleModels.kt`、`TeamModels.kt`、`BattleMoveSelection.kt`、`SpeedLine.kt` | 队伍/招式/结果结构 |
| 识别资源 | `src/data/recognition/**`（ROI、templates-v2.bin/.json）、`src/data/localization/zh-Hans.json` | 打包进 APK assets |

## 2. 裁剪模块（不带入本项目）

| 能力 | 参考文件/依赖 | 原因 |
| --- | --- | --- |
| 队伍码联网同步 | `ShowdownTeamCodec.kt`、`TeamCodeImport.kt`、`OfficialTeamCodeProtocol.kt`、`TeamCodeClientVersion.kt`、`OpponentPresetTransfer.kt`、`tools/team-code-resolver/**` | 不接官方队伍码服务，本地录入即可 |
| Play Integrity / 更新校验 | `com.google.android.play:integrity`、`UpdateManager.kt`、GitHub release 检查 | 本地工具，无远程校验 |
| 发布签名 | `build.gradle.kts` stableSigning*、`config/release-signing-certificate.sha256`、`.clixml` 凭据 | 第一版只出 debug；签名后续单独处理 |
| 变体分支 | `config/android-release-variant.txt`（standard/replay）、HarmonyOS/replay 适配 | 只保留标准单变体 |
| 联网对手预设 | `OpponentUserPresetStore` 中远程同步部分 | 保留本地预设，去同步 |

### 2.1 副本内待删 Kotlin 文件（包目录 `.../pokemonchampionsassistant/`）

队伍码/完整性/更新/远程传输组：
- `OfficialTeamCodeProtocol.kt`、`ShowdownTeamCodec.kt`、`ShowdownTeamUi.kt`、`TeamCodeImport.kt`、`TeamCodeClientVersion.kt`、`TeamCodeIntegrity.kt`
- `OpponentPresetTransfer.kt`（远程同步；`OpponentUserPresetStore.kt` 只留本地预设部分）
- `UpdateManager.kt`（GitHub 更新检查）
- 依赖 `com.google.android.play:integrity:1.6.0`（build.gradle 第 303 行）

保留组：悬浮/截屏 `OverlayCaptureService.kt`、`BattleDirectOverlay.kt`、`BattleOverlayController.kt`、`OverlayFocusController.kt`、`OverlayWindowState.kt`、`OverlaySafeArea.kt`、`CaptureBufferSpec.kt`；识别 `OwnTeamRecognition.kt`、`TeamPreviewRecognition.kt`、`TeamPreviewViewportMapping.kt`、`OwnTeamCorrection*.kt`；引擎 `DamageEngineRuntime.kt`；存档 `AppStorage.kt`、`AppDataBackup.kt`、`OpponentUserPresetStore.kt`；模型 `BattleModels.kt`、`TeamModels.kt`、`BattleMoveSelection.kt`、`SpeedLine.kt`。

裁剪顺序：先去 Integrity/UpdateManager 引用并编译；再解耦队伍码 UI 与编解码；最后剥离远程预设同步。每组删完跑一次 `:app:assembleDebug` 验证。

## 3. 构建耦合资源（自建工程时需拷贝进 assets 或简化 Sync 任务）

旧 `app/build.gradle.kts` 的 preBuild Sync 任务引用仓库根相对路径：

- `../package.json`（version / androidVersionCode）→ 自建工程内改为 `build.gradle` 直接声明版本。
- `../config/android-release-variant.txt`、`../config/release-signing-certificate.sha256` → 裁剪。
- `../src/data/localization/zh-Hans.json` → 拷贝到 `app/src/main/assets/localization/`。
- `../src/data/recognition/team-preview.safe-zone-roi.zh-Hans.v2.json`、`android/team-preview-templates-v2.bin/.json` → 拷贝到 assets。
- `../src/data/damage/champions-presets.json` → 拷贝到 assets（对手常见预设，可选）。
- `../tools/team-code-resolver/data/champions-entity-map.v18.json` → 裁剪；中文名→内部 ID 映射改由本项目 `data/ref/v18/zh-Hans.json` 提供。
- `../THIRD_PARTY_NOTICES.md`、`third_party/licenses/**` → 保留许可证文本。

## 4. 引擎口径

- APK 内不跑 Python 后端。伤害计算走 WebView 本地 JS 引擎（参考 `damage-engine.js`）；本项目 Python 内核仅作离线数值对照。
- 对手未知配置统一按最硬假设模板：HP32、对应防御项（物理招→防御，特殊招→特防）32、该防御性格 ×1.1；其余项 0 投入、中性。
- 我方队伍为完整已知配置（物种/特性/道具/性格/点数/4 招）；对手开局界面仅物种/属性/性别可见。

### 4.1 引擎 bridge 契约（`src/damage/types.ts`）

- 入口：`window.PokemonChampionsDamageEngine.calculateDamage(requestJson)` 返回 JSON；`getEngineInfo()` 返回版本/离线标识。
- 请求 `DamageRequest`：`attacker`/`defender`（`KnownPokemonBuild`：物种 EntityRef、等级、statPoints/actualStats、性格 statAlignment、ability、item、gender、moves、statStages、status、teraType、残余 HP）、`defenderProfileSet`（多档防御模板）、`moveSelection`（mode `ONE_MOVE`/`ALL_ATTACKER_MOVES`、moveId）、`battle`（对战类型、weather、terrain、双方 sideConditions、暴击、重力、气场、ruin）、`calculationMode`（`EXACT`/`TEMPLATE`/`COMPARE_TEMPLATES`/`ENVELOPE`）、`calculationDirection`。
- 响应 `DamageCalculationResult`：`moveResults[]`，每招含 `selectedProfileRange`、`profileRanges[]`（逐防御模板区间，即三档假设结果 1/2/3）、`envelopeRange`、`koSummary`、`assumptions`、`warnings[]`。
- 三档假设落地：在 `defenderProfileSet.profiles` 放三个模板（HP2 防御0中性 / HP32 防御0中性 / HP32+防御项32+防御性格×1.1），读取对应 `profileRanges` 区间展示。

## 5. 落地阶段

1. 工具链：本地 JDK17 + Android SDK 36 + Gradle 9.4.1，doctor 通过。✅ 已完成
2. baseline：旧工程 assembleDebug 出包，验证工具链与资源完整。✅ 已完成（2026-11-13）
   - 产物：`android-app/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`（78.8MB）、`app-x86_64-debug.apk`（113.0MB）。
   - 硬校验全过：entity-map species=396、templates-v2 包哈希/字节、presets、许可证。仅 Kotlin 弃用告警，无编译错误。
   - 体积主要来自 OpenCV .so、ML Kit OCR 模型、13.4MB 模板包、9.4MB presets；后续裁剪可瘦身。
3. 独立骨架：自有包名 Compose 入口，裁掉第 2 节模块，资源按第 3 节就位。（进行中）
4. 悬浮球 + 截屏授权 + 识别接入 + 引擎 bridge + 三档估算。

### 5.1 工程落点（待确认）

- 方案甲（推荐）：复制旧工程为本项目 `android/` 工作副本，在副本内改包名、删第 2 节耦合模块，再逐步重写 UI。构建链路已跑通，起步风险低。
- 方案乙：新建干净 Gradle 工程，逐文件移植悬浮/截屏/OCR/bridge，重配资源与依赖。结构更清爽，但要重新验证整条构建链路。
