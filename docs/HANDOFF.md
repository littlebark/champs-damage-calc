# 工作交接 / 续作备忘（2026-11-13）

> 暂停编码时的状态快照。目标产物是 **Android APK（手机端使用）**；本地网页计算器仅保留作为数据正确性校验，不再加功能。

## 1. 当前方向与已定口径

- 最终形态：本地优先的安卓助手——可拖动悬浮球入口、自动截屏 OCR 识别游戏画面、本地队伍/对手档案、逐招伤害估算。
- 伤害计算在 APK 内走 **WebView 本地打包 JS 引擎**（`assets/damage-engine.js`），不联网算伤害；本项目 Python 内核仅作离线数值对照，不进 APK 运行时。
- 防御方按**最硬假设模板**计算。三档结果（用户口径，APK 内沿用）：
  1. 对方 HP +2 点、其余 0 点、性格中性；
  2. 对方 HP +32 点、其余 0 点、性格中性；
  3. 对方 HP +32 + 对应防御项 32 点 + 该防御性格 ×1.1（物理招用防御，特殊招用特防）。
- 我方队伍为完整已知配置（物种/特性/道具/性格/点数/4 招）；对手开局界面仅可见物种/属性/性别。
- 斗争心保留性别下拉，本轮不改手动触发按钮；其余未接入机制（正电负电、残援霸主、舍身反冲、避雷针/引水攻方触发、气球免地面、优先招守备、重力、节拍器、轻球、报恩/低踢/草结绳/重量技/连签等）继续搁置。

## 2. 目录地图（提交时哪些进仓库）

项目根：`C:\Users\bark\Doubao\chats\2026-11-13\new-chat\champs-damage-calc`

| 路径 | 状态 | 是否提交 | 说明 |
| --- | --- | --- | --- |
| `android/` | 本项目独立工作副本（从参考工程复制裁剪中） | ✅ 提交源码与资源 | 根布局 `android-app/`、`src/`、`tools/`、`config/`、`third_party/`、`docs/`、`test/`、`manual-test-scripts/` |
| `data/` | 离线数据集（raw/community/ref/v18） | ✅ 提交 | `data/ref/v18/` 为 APK 用权威静态事实 + 中文映射 |
| `src/` | Python 校验内核（models/stats/damage/catalog/…） | ✅ 提交 | 仅作数值对照，不进 APK |
| `web/` | 本地网页校验原型（`server.py` 端口 8000 + `index.html`） | ✅ 提交，冻结 | 数据正确性回归基线 |
| `scripts/`、`examples/`、`tests/` | 构建脚本、示例、回归/探针 | ✅ 提交 | `tests/probe_*.py` 为探查脚本 |
| `docs/` | 方案/规格/本文档 | ✅ 提交 | 见第 6 节 |
| `reference/` | 第三方浅克隆（参考工程 + smogon 对照源码） | ❌ 已 gitignore | 体积约 3.6GB（含工具链/产物）；来源 URL 见第 7 节，按需重新克隆 |
| `**/build/`、`**/.gradle/`、`**/.kotlin/`、`local.properties`、`*.apk` | 构建产物/缓存 | ❌ 已 gitignore | 由 Gradle 重新生成 |
| `.android-tools/`、`node_modules/`、`__pycache__/` | 本地工具链/依赖/缓存 | ❌ 已 gitignore | — |

> 注意：`android/android-app/app/build/` 与 `reference/` 体积最大，首次 `git add` 前确认 `.gitignore` 生效，避免把产物和克隆推进私有仓库。

## 3. 已完成里程碑

1. 自写 Python 伤害内核 + 原生 JS 单页前端 + Python 标准库后端（端口 8000），机制挂接、译名对齐、离线/API 回归通过；网页三 tab（计算器/列表/详情）完成。
2. 调研并浅克隆参考工程 `crazylei12/Pokemon-Champions-dmg_cal`（Android 原生 Kotlin + Compose，悬浮窗 + MediaProjection 截屏 + ML Kit 中文/拉丁 OCR + OpenCV 模板匹配，WebView 跑打包 JS 引擎）。
3. 本地工具链就绪：JDK 17.0.19（微软）、Android SDK 36（build-tools 36.0.0、platform-tools 37.0.1）、Gradle 9.4.1、Kotlin 2.3.0；`check-android-env.ps1` 输出 passed。
4. 参考工程一次 `assembleDebug` 出包成功；复制为本项目 `android/` 副本后，**副本在新路径下独立编译成功**（24s，依赖缓存共享）。
   - 产物（不提交）：`android/android-app/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`（78.8MB）、`app-x86_64-debug.apk`（113.0MB）。
   - 硬校验全过：entity-map species=396、templates-v2 包 SHA256/字节数、ROI SHA256、presets、许可证；仅 Kotlin 弃用告警，无编译错误。

## 4. 本地环境与构建命令

- Python（务必用这个，勿用 sandbox 解释器）：`C:\Users\bark\AppData\Local\Programs\Python\Python313\python.exe`
- 工具链实际位于参考工程目录 `reference/Pokemon-Champions-dmg_cal/.android-tools/`（JDK/SDK/Gradle/GRADLE_USER_HOME）。副本 `android/tools/android/android-env.ps1` 已改为优先读环境变量 `ANDROID_TOOL_ROOT`，回退本仓库 `.android-tools`，最后回退该参考工程工具链；`local.properties` 的 `sdk.dir` 同样指向参考 SDK。
- 跑 Gradle（在副本内，脚本会注入 JAVA_HOME/ANDROID_HOME/GRADLE_USER_HOME）：

```powershell
# 环境校验
powershell -NoProfile -ExecutionPolicy Bypass -File android\tools\android\check-android-env.ps1

# 出 debug APK（arm64 + x86_64 双 ABI）
powershell -NoProfile -ExecutionPolicy Bypass -File android\tools\android\run-gradle.ps1 :app:assembleDebug
```

- 网页校验基线（不再加功能，仅回归对照）：

```powershell
& "C:\Users\bark\AppData\Local\Programs\Python\Python313\python.exe" web\server.py
# http://127.0.0.1:8000/
```

- 关键未做项：`npm install` 未在副本执行；`external/smogon-damage-calc` 子模块为空；`damage-engine.js` 已打包进 assets，baseline 编译**不需要**重新打包引擎。`package.json` 的 `android:assets` 脚本依赖 Node 与空 external，副本未跑；首版只走 Gradle assembleDebug。
- Release 构建需要稳定签名密钥（DPAPI clixml + p12），第一版只出 debug，不碰 release 签名。

## 5. Android 副本现状（`android/app/src/main/java/.../pokemonchampionsassistant/`）

- 当前包名/namespace：`com.crazylei12.pokemonchampionsassistant`（**待改自有包名**）；compileSdk 36 / minSdk 33 / targetSdk 36；debug 包名加 `.debug` 后缀。
- Manifest 已声明：`SYSTEM_ALERT_WINDOW`（悬浮）、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PROJECTION`（截屏前台服务）、`POST_NOTIFICATIONS`、`INTERNET`；入口 `.MainActivity`，服务 `.OverlayCaptureService`（foregroundServiceType=mediaProjection）。
- 引擎 bridge：`DamageEngineRuntime.kt` 持有透明 WebView，加载 `file:///android_asset/engine-host.html`（内引 `damage-engine.js`）；全局 `window.PokemonChampionsDamageEngine`，`getEngineInfo()` / `calculateDamage(requestJson)`。
- 契约字段：见 `android/src/damage/types.ts`（`DamageRequest` / `DamageCalculationResult`）与 `android/src/damage/androidBridge.ts`。三档假设落地：在 `defenderProfileSet.profiles` 放三个模板，读取结果里对应 `profileRanges[]` 的区间展示。
- 待裁剪（详细文件清单见 `docs/android-port-plan.md` 第 2、2.1 节）：队伍码编解码/协议/导入/客户端版本/完整性校验、Play Integrity 依赖 `com.google.android.play:integrity:1.6.0`、`UpdateManager` GitHub 更新检查、`OpponentPresetTransfer` 远程同步、replay 变体与 release 签名耦合。`OpponentUserPresetStore` 只保留本地预设部分。
- 保留组：悬浮/截屏（`OverlayCaptureService`、`BattleDirectOverlay`、`BattleOverlayController`、`Overlay*Controller/WindowState/SafeArea`、`CaptureBufferSpec`）、识别（`OwnTeamRecognition`、`TeamPreviewRecognition`、`TeamPreviewViewportMapping`、`OwnTeamCorrection*`）、引擎 runtime、存档（`AppStorage`、`AppDataBackup`）、模型（`BattleModels`、`TeamModels`、`BattleMoveSelection`、`SpeedLine`）。

## 6. 文档索引

- `docs/android-port-plan.md`：移植/裁剪清单、构建耦合资源、引擎口径、落地阶段、副本内待删 Kotlin 文件表。
- `docs/ui-spec.md`：计算器界面规格（手机上下堆叠、少填项、默认值表、结果区）；APK 计算器 UI 沿用此规格。
- `docs/HANDOFF.md`：本文档。
- `README.md`、`DATA_SEASONS.md`：网页阶段调研、数据源、赛季分层（README 顶部「先不做 APK」表述已过时，以本文档方向为准）。

## 7. 参考来源与许可证边界

- 参考助手工程：<https://github.com/crazylei12/Pokemon-Champions-dmg_cal>（MIT）。复用须保留版权声明与许可证文本；许可证副本在 `android/third_party/licenses/` 与 `android/THIRD_PARTY_NOTICES.md`。
- 伤害引擎对照：<https://github.com/smogon/damage-calc>（MIT）。
- 数据底座：Showdown pokedex/moves/items/abilities/learnsets、社区结构化数据集（CC BY 4.0）、championsbattledata index；对账脚本在 `tests/reconcile_*.py`。

## 8. 续作待办（按优先级）

1. **#32 副本收尾**（进行中）
   - 改自有包名：`namespace` / `applicationId` / 目录包名 / Manifest 引用。
   - 按裁剪清单逐组解耦删除：先去 Integrity + UpdateManager 引用并编译；再解耦队伍码 UI/编解码；最后剥离远程预设同步。**每删一组跑一次 `:app:assembleDebug` 验证。**
   - 队伍码存档序列化耦合在 `BattleModels.kt`（`ShowdownTeamCodec.export/parse`），删编码前先确认本地存档格式是否可改为独立 JSON。
2. **计算器首页 + 列表/详情 tab**（用户首要需求）
   - 首页直接是伤害计算器：手机上下双方堆叠、少填项（`docs/ui-spec.md`）；第二 tab 宝可梦列表；点进详情看种族值/特性/属性/招式列表。
   - 组 `DamageRequest` → `DamageEngineRuntime.calculate` → 解析 `moveResults[].profileRanges` 渲染三档区间与 KO 摘要；中文名 ↔ 内部 ID 走 `data/ref/v18/zh-Hans.json`。
3. **#33 悬浮球 + 截屏 + OCR + 三档估算**（后置，UI 稳定后再接）
   - 悬浮窗权限申请与可拖动悬浮球；MediaProjection 截屏授权与前台服务；队伍/选招画面识别；识别结果回填我方队伍档案，逐招在选招界面红框位出三档估算。
4. 数据：APK 离线资源从 `data/ref/v18/` 与 `android/src/data/` 就位；赛季/规则集版本字段预留。

## 9. 已知坑 / 注意事项

- **PowerShell 脚本注释只用 ASCII**：无 BOM 的 UTF-8 中文注释在本机调用链下会被误读，导致 `if/elseif` 解析异常、变量取空（本轮 `android-env.ps1` 踩过，已改英文注释修复）。复杂逻辑写 Python 脚本，显式 `encoding='utf-8'`，不要经 PowerShell 管道传中文。
- 构建硬断言：entity-map 必须 species=396；templates-v2 元数据里的 bytes/sha256 与实际文件必须一致；换识别包要同步更新 `.json` 元数据。
- `validateTeamCodeEntityMap` 与 `syncTeamCodeAssets` 是队伍码耦合任务，裁剪队伍码时一并移除对应 Sync/校验任务与 `generated/teamCodeAssets` srcDir。
- `src/data/localization/sources/42arch-pokemon-dataset-zh/LICENSE` 被 `syncLegalAssets` 引用；若该 LICENSE 后续丢失，需在 legalAssets 任务里去掉这条。
- 出包体积大主要来自 OpenCV `.so`、ML Kit OCR 模型、13.4MB 模板包、9.4MB presets；后续裁剪可评估只保留 arm64。
- 数据冗余待清理：`data/ref/v18/champions-presets.json` 与 `android/src/data/damage/champions-presets.json` 是同一份 9.02MB，后续可让 Android 侧引用单一来源，避免双份进库。
- 首次 `git add` 干跑：317 个文件、约 66.8MB；`reference/`、`build/`、`.gradle/`、`.kotlin/`、`.android-tools/`、`node_modules/`、`*.apk`、`local.properties`、`__pycache__/` 均未进暂存。
- 网页旧基线数值不得漂移：基础 200~236 OHKO 9/16；生命宝珠 260~306；特攻+1 300~354；防御+1 134~158；适应力 268~316；湿润之声精神噪音打水河马 180~212（激流对照 60~71）；飘浮免疫地震 0~0。改内核后跑 `tests/verify_screenshot.py` 等回归核对。

## 10. 私有仓库（已推送）

- 远程：`https://github.com/littlebark/champs-damage-calc.git`，分支 `main`（已跟踪 `origin/main`）。
- 首次提交：`72744d6` — "chore: scaffold Android assistant; keep web calculator as offline validation baseline"，316 个文件。
- 提交前干跑已确认：`reference/`、`build/`、`.gradle/`、`.kotlin/`、`.android-tools/`、`node_modules/`、`*.apk`、`local.properties`、`__pycache__/`、签名私钥均未入库。
- 本地续作时常规流程：`git add -A` → `git commit -m "..."` → `git push`。
- 注：首次提交仍包含待裁剪的队伍码/Integrity/UpdateManager 相关源码与测试，属 baseline 副本；按第 8 节优先级逐组清理，每组编译验证后再提交。
