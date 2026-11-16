# 宝冠军伤害助手（champs-damage-calc）

《Pokémon Champions》（宝冠军）对战用的 Android 伤害助手：游戏内常驻悬浮十字键，选招页一键读招、按对方三种未知加点出三档伤害区间；另有图鉴与手动计算器两个 tab。

> 非官方工具，与任天堂 / 株式会社宝可梦 / The Pokémon Company 无关联。数据仅用于个人对战参考。

## 功能

| 模块 | 说明 |
| --- | --- |
| 计算器 tab | 手动选我方 / 对方、招式、能力点数，本地离线引擎出伤害区间 |
| 图鉴 tab | 本地数据集的物种、属性、种族值、可学招式查询 |
| 伤害助手 tab | 启动悬浮层后，游戏内常驻十字键：计算伤害 / 关闭伤害 / 识别队伍 / 识别当前 |
| 计算伤害 | 读选招页我方、对方与四个招式，按对方三种未知加点（`tier.hp2` / `tier.hp32` / `tier.hp32-def32` / `tier.hp32-spd32`）出三档红框百分比 |
| 识别队伍 | 读队伍状态页六只宝可梦的六项数值与加点，合并进「我的宝可梦」列表 |
| 识别当前 | 停在「状态」页读六项数值 + 加点；停在「能力」页读招式 / 特性 / 持有物（不编造未读到的字段） |
| 我的宝可梦 | 按物种 upsert 的一份持续增长的记录列表，重复遇到以新识别为准 |

伤害计算走本地 WebView + `assets/damage-engine.js`，完全离线；OCR 用 PaddleOCR（PP-OCRv6 det + rec）与 ML Kit 兜底，全部在设备本地完成，不上传截图与数据。

## 目录

```
android/android-app/          Android 工程（Kotlin + Compose）
  app/src/main/java/com/bark/championsassistant/   应用源码
  app/src/main/assets/        伤害引擎、OCR 模型与词库
android/src/data/             APK 打包用的离线数据集（预设、名单、中文词库）
web/                          早期本地网页校验原型（冻结，仅作数据正确性回归）
docs/                         交接文档、规格、QA 记录
```

## 构建

```powershell
. .\android\tools\android\android-env.ps1 -Quiet
cd android\android-app
& ..\.android-tools\gradle-9.4.1\bin\gradle.bat :app:assembleDebug
```

产物：`android/android-app/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`。
包名 `com.bark.championsassistant`，应用名「宝冠军伤害助手」。

## 第三方来源与许可（重要）

本项目的 **Android 应用层**（UI、悬浮窗、OCR 业务流程、识别与纠错逻辑、数据存储）为自研代码；
**伤害计算内核、宝可梦数据与 OCR 模型**来自第三方，版权与许可归各自权利人。逐项如下：

### 1. 伤害计算内核

- `android/android-app/app/src/main/assets/damage-engine.js`（约 700 KB，打包产物）
- 上游：<https://github.com/smogon/damage-calc>（MIT），经由 `pokemon-showdown` / `@pkmn/dex`、`@pkmn/mods` 构建
- 构建标识：`pokemon-champions-smogon-0.11.0`
- 许可副本：`android/third_party/licenses/smogon-damage-calc-MIT.txt`、`pokemon-showdown-MIT.txt`、`pkmn-ps-MIT.txt`

### 2. 宝可梦数据与中文词库

- `android/src/data/damage/champions-presets.json`（约 9.4 MB，种族值 / 招式 / 特性 / 道具 / 可学招式池）
- `android/src/data/damage/champions-roster.v18.json`（宝冠军名单与形态）
- `android/src/data/localization/zh-Hans.json`（约 1300 条中文名词映射）
- 上游：smogon/damage-calc 基础数据 + `@pkmn/dex` 数据；物种与招式原始事实数据可追溯至
  <https://github.com/smogon/pokemon-showdown>（MIT）与 <https://pokeapi.co>（BSD-3-Clause）；
  宝冠军自身的名单、种族值调整与可学招式限制由本项目按游戏内实测与社区结构化数据整理覆盖
- 许可副本：`android/third_party/licenses/` 下对应文件；汇总见 `android/THIRD_PARTY_NOTICES.md`

### 3. OCR 模型

- `android/android-app/app/src/main/assets/ocr/det.onnx`（约 9.5 MB，文本检测）、`rec.onnx`（约 20 MB，文本识别）、`keys.txt`（字典）
- 上游：<https://github.com/PaddlePaddle/PaddleOCR>（Apache-2.0）
- 许可副本：`android/third_party/licenses/APACHE-2.0.txt`
- 另有 Google ML Kit 文本识别作为兜底，条款见 `android/third_party/licenses/ml-kit-TERMS.txt`

### 4. 运行时依赖

AndroidX / Jetpack Compose / ONNX Runtime / ML Kit 等，均为各自开源或商业许可，随构建产物附带许可声明。

### 5. 项目起点

工程骨架最初 fork 自 <https://github.com/crazylei12/Pokemon-Champions-dmg_cal>（MIT）。
原作者提供的队伍码导入、Pokémon Showdown 文本导入导出、对局阵容识别、更新检查、数据备份等模块
已在本项目中移除，仅保留其 MIT 许可与版权声明所要求的归属说明。

---

历史调研、公式推导与早期网页阶段记录见 `docs/HANDOFF.md`；赛季与数据源调研见 `docs/` 下相关文档。
