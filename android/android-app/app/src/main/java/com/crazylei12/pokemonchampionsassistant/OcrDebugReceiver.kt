package com.crazylei12.pokemonchampionsassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log

/** 调试用：adb 广播指定图片路径，跑一遍 PaddleOCR 并把每行结果写进日志。 */
class OcrDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val path = intent.getStringExtra("path") ?: return
        val target = context.applicationContext
        Thread {
            runCatching {
                val bitmap = BitmapFactory.decodeFile(path) ?: error("decode failed: $path")
                val engine = PaddleOcrEngine(target)
                val started = System.currentTimeMillis()
                val lines = engine.recognize(bitmap)
                Log.i(TAG, "image=${bitmap.width}x${bitmap.height} lines=${lines.size} cost=${System.currentTimeMillis() - started}ms")
                lines.take(40).forEach { line ->
                    Log.i(TAG, "box=${line.left},${line.top},${line.right},${line.bottom} text=${line.text.take(40).replace("\r", "")}")
                }
                engine.close()
            }.onFailure { Log.e(TAG, "Paddle debug run failed", it) }
        }.start()
    }

    private companion object {
        const val TAG = "PaddleOcrDebug"
    }
}
