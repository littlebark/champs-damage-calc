package com.bark.championsassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log

/** 调试用：adb 广播指定图片路径，跑一遍 PaddleOCR 并把每行结果写进日志。 */
class OcrDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra("path") ?: return
        val limit = intent.getIntExtra("limit", 1024)
        val target = context.applicationContext
        Thread {
            runCatching {
                val bitmap = BitmapFactory.decodeFile(path) ?: error("decode failed: $path")
                val engine = PaddleOcrEngine(target)
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
                engine.close()
            }.onFailure { Log.e(TAG, "Paddle debug run failed", it) }
        }.start()
    }

    private companion object {
        const val TAG = "PaddleOcrDebug"
    }
}
