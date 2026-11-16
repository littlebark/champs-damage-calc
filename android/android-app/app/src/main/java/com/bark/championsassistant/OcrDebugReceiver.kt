package com.bark.championsassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlin.math.roundToInt

/**
 * 调试用：adb 广播指定图片路径，跑一遍 PaddleOCR 并把每行结果写进日志。
 * 加 `--ez roi true` 时走「计算伤害」页的固定区域直识别，只打印那几块的结果和总耗时。
 */
class OcrDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra("path") ?: return
        val limit = intent.getIntExtra("limit", 1024)
        val useRegions = intent.getBooleanExtra("roi", false)
        val target = context.applicationContext
        Thread {
            runCatching {
                val bitmap = BitmapFactory.decodeFile(path) ?: error("decode failed: $path")
                val engine = PaddleOcrEngine(target)
                if (useRegions) {
                    runRegionBench(engine, bitmap)
                } else {
                    // 第一次跑包含 models 加载后的首次 warm-up，不计入耗时；后面两次取最小值。
                    engine.recognize(bitmap, limit)
                    var best = Long.MAX_VALUE
                    repeat(2) {
                        val started = System.currentTimeMillis()
                        val lines = runCatching { engine.recognize(bitmap, limit, logTiming = true) }.getOrDefault(emptyList())
                        val cost = System.currentTimeMillis() - started
                        if (cost < best) best = cost
                        Log.i(TAG, "image=${bitmap.width}x${bitmap.height} limit=$limit lines=${lines.size} cost=${cost}ms")
                    }
                    Log.i(TAG, "best=$best" + "ms")
                }
                engine.close()
            }.onFailure { Log.e(TAG, "Paddle debug run failed", it) }
        }.start()
    }

    /** 与 OverlayCaptureService 里 SNAPSHOT_REGIONS 完全一致，用来在真机上单独验证这几块。 */
    private fun runRegionBench(engine: PaddleOcrEngine, bitmap: Bitmap) {
        val regions = REGIONS.map { (leftInset, rightInset, top, bottom) ->
            val left = (leftInset * bitmap.width).roundToInt()
            val right = (bitmap.width - rightInset * bitmap.width).roundToInt()
            PaddleOcrEngine.RegionRect(
                left = left.toFloat() / bitmap.width,
                top = top,
                right = right.toFloat() / bitmap.width,
                bottom = bottom,
            )
        }
        engine.recognizeRegions(bitmap, regions)
        var best = Long.MAX_VALUE
        var bestTexts: List<String> = emptyList()
        repeat(2) {
            val started = System.currentTimeMillis()
            val texts = engine.recognizeRegions(bitmap, regions)
            val cost = System.currentTimeMillis() - started
            if (cost < best) {
                best = cost
                bestTexts = texts
            }
        }
        val names = listOf("对手名牌", "我方名牌", "天气场地", "招式1", "招式2", "招式3", "招式4")
        names.forEachIndexed { index, name -> Log.i(TAG, "$name='${bestTexts.getOrNull(index).orEmpty()}'") }
        Log.i(TAG, "gender own=${readGenderBadge(bitmap, OWN_GENDER_BADGE)} " +
            "opp=${readGenderBadge(bitmap, OPPONENT_GENDER_BADGE)}")
        Log.i(TAG, "roi best=${best}ms regions=${regions.size}")
    }

    /** 只按底色判断性别：蓝底 ♂ / 粉红底 ♀（和 OverlayCaptureService 里同一套算法）。 */
    private fun readGenderBadge(bitmap: Bitmap, region: RegionBox): String? {
        val left = (region.leftInset * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
        val right = (bitmap.width - region.rightInset * bitmap.width).roundToInt().coerceIn(left + 1, bitmap.width)
        val top = (region.top * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
        val bottom = (region.bottom * bitmap.height).roundToInt().coerceIn(top + 1, bitmap.height)
        val width = right - left
        val height = bottom - top
        if (width < 4 || height < 4) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, left, top, width, height)
        var blue = 0
        var pink = 0
        for (pixel in pixels) {
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blueValue = pixel and 0xFF
            if (blueValue > red + 40 && blueValue > green + 20) blue++
            else if (red > blueValue + 40 && red > green + 25) pink++
        }
        if (blue + pink < width * height / 20) return null
        return when {
            blue > pink * 1.3f -> "♂"
            pink > blue * 1.3f -> "♀"
            else -> null
        }
    }

    private data class RegionBox(
        val leftInset: Float,
        val rightInset: Float,
        val top: Float,
        val bottom: Float,
    )

    private companion object {
        const val TAG = "PaddleOcrDebug"
        val REGIONS = listOf(
            listOf(0.772f, 0.110f, 0.042f, 0.092f),
            listOf(0.118f, 0.790f, 0.894f, 0.946f),
            listOf(0.196f, 0.688f, 0.046f, 0.094f),
            listOf(0.752f, 0.124f, 0.492f, 0.548f),
            listOf(0.752f, 0.124f, 0.612f, 0.666f),
            listOf(0.752f, 0.124f, 0.730f, 0.784f),
            listOf(0.752f, 0.124f, 0.848f, 0.906f),
        )
        val OWN_GENDER_BADGE = RegionBox(0.2215f, 0.7605f, 0.899f, 0.934f)
        val OPPONENT_GENDER_BADGE = RegionBox(0.898f, 0.072f, 0.052f, 0.086f)
    }
}
