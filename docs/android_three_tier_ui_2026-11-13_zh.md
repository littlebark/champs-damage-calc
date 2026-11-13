# 计算器首页 + 图鉴 tab + 三档伤害 + 悬浮窗逐招红框（2026-11-13）

本轮把「手机端主界面」和「三档伤害口径」落到 Android 工程里，并编译通过。

## 1. 本轮改动

### 1.1 Tab 结构与首页
- `MainActivity.kt` 的 `AppTab` 改为：`计算器 / 图鉴 / 队伍 / 对局 / 设置`，默认进入 `计算器`（此前默认是队伍库）。
- 计算器标题改为「伤害计算器」；原有上下堆叠 / 左右并排的自适应布局保留（手机竖屏为上下堆叠）。

### 1.2 计算器三档估算
- `计算方向` 增加「对手配置」二选一：`未知（三档估算）`（默认）与 `已知精确配置`。
- `tierProfiles = true` 时 `buildDamageRequest` 使用 `tierDefenderProfileSet()`，一次请求内放四个防守档位：
  1. `tier.hp2`：HP +2，其余 0，性格中性；
  2. `tier.hp32`：HP +32，其余 0，性格中性；
  3. `tier.hp32-def32`：HP +32、防御 +32、`Bold`（大胆，防御 ×1.1）；
  4. `tier.hp32-spd32`：HP +32、特防 +32、`Calm`（温和，特防 ×1.1）。
- 展示时按招式 `moveCategory` 自动选择第 3 档还是第 4 档：物理招取防御档，特殊招取特防档。变化招显示「无直接伤害」。
- 结果卡 `TierCalculationCard` 每招一行三列：`① HP+2` / `② HP+32` / `③ 32点+性格`，显示百分比区间与伤害区间。

### 1.3 图鉴 tab
- 新增 `PokedexScreen.kt`：数据来自 `champions-presets.json` 的 `speciesForms`（359 个形态，比 396 个实体的名册少了部分无配置形态），支持中英文名搜索。
- 列表行展示：中文名、属性徽章、种族值总和、可用特性（最多 2 个）。
- 详情页展示：属性、六项种族值条形图、种族值合计、特性列表、可学招式列表（按物理→特殊→变化排序，可按分类筛选，显示类型徽章与威力）。
- 数据接口在 `BattleModels.kt` 新增：`allForms`、`formFor()`、`learnableMovesFor()`、`moveCategoryFor()`、`typeDisplayName()`；`MoveValue` 增加 `category` 字段，解析时用 `moveMetadata` 补齐。

### 1.4 悬浮窗逐招红框
- `BattleDirectHudElement.DAMAGE` 拆成 `DAMAGE_1..4` 四个独立小窗，默认锚点按游戏 16:9 viewport 计算：`x=0.462`、宽 `0.212`，四行 `y=0.500 / 0.595 / 0.690 / 0.785`，对应游戏选招界面四个招式左侧红框位置。
- 每个小窗只显示该招的三档伤害，格式 `min-max｜min-max｜min-max%`，例如 `42-50｜30-35｜20-24%`；空槽与变化招留空。
- `BattleOverlayController` 的对战 HUD 请求改用 `tierDefenderProfile = true`，`parseBattleDirectDamageValues` 按 `profileRanges[].defenderProfileId` 取数。
- 布局编辑模式下四个红框标签可单独拖动/缩放，保存到原有 layout 存储。

### 1.5 测试
- 新增 `TierDamageProfileTest`：校验四个档位的 id、等级 50、加点与性格（Bold / Calm）。
- 更新 `BattleDirectOverlayTest`：`DAMAGE` 改为 `DAMAGE_1`，伤害文案断言改为三档格式。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 均通过。

## 2. 验证结果

- 工具链：本机原本没有 Android 工具链，本轮用 `android\tools\android\bootstrap-android.ps1` 装到 `android\.android-tools`（JDK 17、SDK 36、Gradle 9.4.1）。`downloads.gradle.org` 在本机不可达，Gradle 包改用腾讯镜像下载；编译用 `.android-tools\gradle-9.4.1\bin\gradle.bat`，不走 wrapper 下载。
- 编译：`BUILD SUCCESSFUL`，产物 `android/android-app/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`（82.7MB）、`app-x86_64-debug.apk`（118.6MB）。
- 单元测试：`BUILD SUCCESSFUL`，含新增档位结构测试。
- 引擎数值抽查（Node + `assets/damage-engine.js`，我方大嘴娃 → 对手红莲铠骑，`ALL_ATTACKER_MOVES`）：
  - 嬉闹（物理）：`32-38 (19.8-23.5%)` / `32-38 (16.7-19.8%)` / `23-27 (12.0-14.1%)` / `32-38 (16.7-19.8%)`；
  - 突袭（物理）：`68-80 (42.0-49.4%)` / `68-80 (35.4-41.7%)` / `48-58 (25.0-30.2%)` / `68-80 (35.4-41.7%)`；
  - 变化招（守住）四档均为 0。
  - 结论：物理招第三档取防御 32 + Bold，第四档（特防）确实无效；特殊招会走第四档，逻辑一致。
- **未做真机验证**：本机 `adb devices` 无连接设备；图鉴滚动性能、三档数值、悬浮窗红框位置均需在手机上实测校准。

## 3. 下一步

1. **选招画面识别**：新增「对战画面 OCR」——截取四个招式名区域 → ML Kit 文本识别 → 与 `moveMetadata` 中文名模糊匹配；同时识别我方当前上场宝可梦与对手，自动填 HUD 的 own/opponent 槽位（目前仍需在 HUD 里手动选槽）。
2. **红框位置校准**：用真机 16:9 / 20:9 截图核对四个 `DAMAGE_*` 锚点；必要时按 `battleDirectGameViewport` 的实际黑边偏移修正。
3. **我方队伍录入复用**：队伍查看界面 OCR（`OwnTeamOcrEngine`）识别后写入临时队伍；未录入时计算器默认使用图鉴 0 加点配置（当前已如此）。
4. **裁剪**：包名、队伍码/Integrity/UpdateManager 等参考工程残留按 `docs/android-port-plan.md` 逐组删除，每删一组编译一次。
