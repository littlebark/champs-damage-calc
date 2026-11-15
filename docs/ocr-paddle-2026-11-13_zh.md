# PaddleOCR 接入与真机乱码修复记录（2026-11-13）

## 1. 目标

选招页「按画面计算」要一次读出：我方宝可梦、对手宝可梦、四个招式。
ML Kit 中文通用模型在游戏小字/描边字上会错一个字（实测 `玛カ露丽`、`破売`、`吧/近身战`），
因此换成离线 PaddleOCR（PP-OCRv6 小模型 + ONNX Runtime），词库模糊匹配只作兜底。

## 2. 组成

| 环节 | 实现 |
| --- | --- |
| 模型 | `assets/ocr/det.onnx`（9,929,594 B，PP-OCRv6_det_small）、`assets/ocr/rec.onnx`（21,234,383 B，PP-OCRv6_rec_small） |
| 字典 | `assets/ocr/keys.txt`（18,708 行，从 rec 模型 metadata 导出） |
| 运行时 | `com.microsoft.onnxruntime:onnxruntime-android:1.20.0` |
| 引擎 | `PaddleOcrEngine.kt`：det 缩放长边 1280（宽高取 32 倍数）→ DB 概率图阈值 0.3 → 8 邻通连通域 → 行合并 → rec（高 48）→ CTC greedy |
| 集成 | `OverlayCaptureService`：选招页整屏优先 Paddle，失败回退 ML Kit；`OwnTeamOcrEngine` 单只状态页同样 Paddle 优先 |
| 词库纠正 | `EntityCatalog.correctToOfficialName()`：精确 / 包含 / 编辑距离 ≤1，置信度 ≥0.70 才采纳；查库前做繁体转简体 |

模型来源（可复现）：`pip download rapidocr --no-deps -d %TEMP%\roc2 -i https://mirrors.tencent.com/pypi/simple/`，
wheel 内即 `rapidocr/models/PP-OCRv6_det_small.onnx` 与 `PP-OCRv6_rec_small.onnx`。
HF 直链 404、GitHub release 无 assets，此路径是唯一可用来源。

## 3. 真机乱码的三个根因

首版装机后现象：`image=2772x1280 lines=39 cost=1204ms`，检测框数量正常，
但每行文本是 `len=24486 text="?d!?d%?d#..."` 这类超长重复串。

排查方法：新增 `OcrDebugReceiver`，adb 广播指定截图，在手机上跑一遍并打印
`crop 尺寸 / 输出类别数 / 步数 / 每步 argmax id`；同时用电脑端同模型同字典的
`tools/paddle_check.py` 在同一张图上跑同一裁剪框做逐值比对。

### 3.1 输出张量形状算错（第一次乱码）

`classes` 被写成 `buffer.remaining() / shape.last`，等于把时间步数当成了类别数：
真机上 `classes=11 / steps=18710`，于是解码出 18,710 个字符、字符全落在前 11 个类别。
正确写法是 `classes = shape.last`（18710）、`steps = remaining / classes`。
日志随即变成 `classes=18710 steps=26`，长度恢复正常。

### 3.2 检测框坐标系错位（识别出单字/噪声）

det 在缩小图上跑（2772→1280，scale≈0.4618），但 `findBoxes` 的结果被当成原图坐标直接裁剪。
表现：所有行文本只有 1~2 个字符，且文字常在画面另一侧。
电脑端同一张图的框是 `2245,75,2371,93`，手机端是 `1033,32,1099,44`——正好差 0.46 倍。
修复：det 结果先按 `scaleX/scaleY` 换算回原图坐标，再裁剪与上报。

### 3.3 字典文件 CRLF（每个字后面带回车）

`keys.txt` 从模型 metadata 导出时是 CRLF，`split("\n")` 后每项尾部残留 `\r`。
解码出的文本实际是 `西\r狮\r海\r壬`，logcat 在 `\r` 处截断，看起来像只识别出一个字或乱码。
修复：`split("\n").map { it.trimEnd('\r') }`。

### 3.4 顺带修的两处

- DB 连通域从 4 邻通改成 **8 邻通**，中文笔画不再被切成单字。
- 行合并阈值收紧（同一行要求垂直中心差 ≤ 0.4 行高，水平间隙 ≤ 0.8 字高 + 8），
  避免把整块画面并成一条线；并过滤近方形小碎片（图标）不做识别。

## 4. 修复后的真机实测

同一张失败截图（暴鲤龙 vs 西狮海壬，2772x1280）：

```
image=2772x1280 lines=32 cost=1494ms
box=2237,71,2380,98      text=西狮海壬
box=2395,149,2516,191    text=100%
box=866,220,1904,249     text=没有认出我方、对手或招式，请在选招页（能看到四
box=905,276,1213,304     text=个招式）时重试
box=2367,420,2495,449    text=查看状态
box=2365,513,2495,540    text=招式说明
box=2122,649,2248,678    text=强力鞭打
box=2096,707,2246,733    text=效果绝佳
box=2122,800,2185,831    text=地震
box=2098,860,2215,889    text=有效果
box=2124,953,2215,984    text=冰冻牙
box=2124,1107,2218,1138  text=龙之舞
box=344,1160,446,1187    text=暴鯉龙
box=455,1240,626,1273    text=175/175
```

电脑端对照（`tools/paddle_check.py` 同一张图）：同裁剪框 `2237,71,2380,98` →
`西狮海壬` 置信度 0.97，argmax 序列 `[0,0,0,0,0,13324,0,...,8951,...,7978,...,4157]`
与手机端完全一致，可确认两端已对齐。

繁体字形（`暴鯉龙`）由 `Transliterator("Traditional-Simplified")` 归一后再查词库。

## 5. 调试手段（交付前需移除）

- `OcrDebugReceiver`：`exported=true`，action `com.crazylei12.pokemonchampionsassistant.DEBUG_OCR`，extra `path`。
  必须先启动 MainActivity，再广播：

```
adb shell am broadcast --include-stopped-packages \
  -a com.crazylei12.pokemonchampionsassistant.DEBUG_OCR \
  --es path /data/local/tmp/ocr_test.jpg \
  -p com.crazylei12.pokemonchampionsassistant.debug
adb logcat -d -v brief | Select-String "PaddleOcrDebug"
```

- `PaddleOcrEngine.DEBUG_LOGS`（当前为 `false`）：打开后打印每行 `crop 尺寸 / classes / steps / 每步 argmax`，
  并把第一块裁剪存到私有目录 `files/debug_crop.png`，可用
  `adb exec-out run-as <pkg> cat files/debug_crop.png > crop.png` 取出核对。

## 6. 编译与安装

```
. "C:\code-hub\champs-damage-calc\android\tools\android\android-env.ps1" -Quiet
Set-Location android\android-app
C:\code-hub\champs-damage-calc\android\.android-tools\gradle-9.4.1\bin\gradle.bat :app:assembleDebug
adb -s <device> install -r app\build\outputs\apk\debug\app-arm64-v8a-debug.apk
```

禁用 `gradlew.bat`（`downloads.gradle.org` / `services.gradle.org` 不可达，Gradle 走腾讯镜像）。

## 7. 尚未完成 / 待验收

1. 选招页「按画面计算」端到端验收（OCR 已正常，但按钮链路未在真机点过）。
2. 四个红框（`DAMAGE_1..4`）坐标在新 HUD 下未校准。
3. Mega 形态切换（HUD「变更」）未验收；对方改名后仍显原名，需要「名字优先 + 图案/招式反查兜底」。
4. 单只状态页 Paddle 路径（`statsFromLines`）未真机验证。
5. 「按区域裁剪 + 2 倍放大」尚未实现（当前整屏长边 1280）。
6. `OcrDebugReceiver` 上线前删除或 release 关闭。
7. APK 体积约 127 MB（两个模型共 31 MB），是否接受未定。
