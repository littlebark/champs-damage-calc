package com.bark.championsassistant

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PaddleOCR（PP-OCRv6 小模型）离线识别：DB 检测 + CTC 识别，模型放在 assets/ocr。
 * 全应用唯一的 OCR 引擎，中文小字、描边字的识别都由它负责。
 */
class PaddleOcrEngine(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val detSession: OrtSession
    private val recSession: OrtSession
    private val characters: List<String>
    private val detInput: String
    private val recInput: String

    init {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(OC_RUNTIME_THREADS)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val detBytes = appContext.assets.open("ocr/det.onnx").use { it.readBytes() }
        val recBytes = appContext.assets.open("ocr/rec.onnx").use { it.readBytes() }
        detSession = env.createSession(detBytes, options)
        recSession = env.createSession(recBytes, options)
        detInput = detSession.inputNames.first()
        recInput = recSession.inputNames.first()
        characters = appContext.assets.open("ocr/keys.txt").use { stream ->
            // 字典文件是 CRLF 换行，必须去掉行尾的 \r，否则每个字后面都会带回车。
            stream.readBytes().toString(Charsets.UTF_8)
                .split("\n")
                .map { it.trimEnd('\r') }
        }
        Log.i(LOG_TAG, "PaddleOCR ready, characters=${characters.size}")
    }

    /** 调试用：只跑识别网络，返回“文本|类别数|步数”，用于定位乱码问题。 */
    fun debugRecText(bitmap: Bitmap): String {
        val (text, score) = runRec(bitmap)
        return "${text.take(20)}|score=$score|boxes=${'x'}"
    }

    fun recognize(bitmap: Bitmap, limit: Int = DEFAULT_DET_LIMIT, logTiming: Boolean = false): List<SnapshotTextLine> {
        val started = if (logTiming) System.currentTimeMillis() else 0L
        val scale = min(1f, limit.toFloat() / max(bitmap.width, bitmap.height))
        val rw = max(32, ((bitmap.width * scale) / 32f).roundToInt() * 32)
        val rh = max(32, ((bitmap.height * scale) / 32f).roundToInt() * 32)
        val scaled = Bitmap.createScaledBitmap(bitmap, rw, rh, true)
        val pixels = IntArray(rw * rh)
        scaled.getPixels(pixels, 0, rw, 0, 0, rw, rh)
        scaled.recycle()
        val input = FloatArray(3 * rw * rh)
        val plane = rw * rh
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val r = ((pixel shr 16) and 0xFF) / 255f
            val g = ((pixel shr 8) and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f
            // 与电脑端 Python 对照脚本完全一致：cv2 读图为 BGR，通道顺序 B、G、R。
            input[index] = (b - MEAN_R) / STD_R
            input[plane + index] = (g - MEAN_G) / STD_G
            input[2 * plane + index] = (r - MEAN_B) / STD_B
        }
        val detStarted = if (logTiming) System.currentTimeMillis() else 0L
        val detMap = runDet(input, rw, rh)
        val detCost = if (logTiming) System.currentTimeMillis() - detStarted else 0L
        val scaleX = rw / bitmap.width.toFloat()
        val scaleY = rh / bitmap.height.toFloat()
        // det 的概率图是缩放后的坐标系，这里统一换算回原图像素，后续裁剪/上报都用原图坐标。
        val boxes = mergeIntoLines(findBoxes(detMap, rw, rh)).map { box ->
            Box(
                left = (box.left / scaleX).roundToInt(),
                top = (box.top / scaleY).roundToInt(),
                right = (box.right / scaleX).roundToInt(),
                bottom = (box.bottom / scaleY).roundToInt(),
            )
        }
        val result = ArrayList<SnapshotTextLine>(boxes.size)
        boxes.forEach { box ->
            val crop = cropBox(bitmap, box) ?: return@forEach
            val text = runCatching { runRec(crop) }.getOrNull()?.first
            crop.recycle()
            if (text.isNullOrBlank()) return@forEach
            result += SnapshotTextLine(
                text = text,
                left = box.left,
                top = box.top,
                right = box.right,
                bottom = box.bottom,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
            )
        }
        if (logTiming) {
            Log.i(
                LOG_TAG,
                "recognize ${bitmap.width}x${bitmap.height} det@${rw}x$rh detCost=${detCost}ms " +
                    "total=${System.currentTimeMillis() - started}ms lines=${result.size}",
            )
        }
        return result.sortedWith(compareBy({ it.top }, { it.left }))
    }

    private fun runDet(input: FloatArray, width: Int, height: Int): FloatArray {
        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, height.toLong(), width.toLong()),
        )
        tensor.use {
            detSession.run(mapOf(detInput to tensor)).use { output ->
                val out = output.get(0) as OnnxTensor
                val buffer = out.floatBuffer
                val map = FloatArray(width * height)
                buffer.get(map)
                return map
            }
        }
    }

    private fun findBoxes(probability: FloatArray, width: Int, height: Int): List<Box> {
        val mask = BooleanArray(width * height)
        for (index in probability.indices) mask[index] = probability[index] > DET_THRESHOLD
        val visited = BooleanArray(width * height)
        val boxes = ArrayList<Box>()
        val stack = IntArray(width * height)
        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var top = Int.MAX_VALUE
            var left = Int.MAX_VALUE
            var bottom = 0
            var right = 0
            var count = 0
            var scoreSum = 0f
            var stackSize = 0
            stack[stackSize++] = start
            visited[start] = true
            while (stackSize > 0) {
                val index = stack[--stackSize]
                val x = index % width
                val y = index / width
                scoreSum += probability[index]
                count++
                if (y < top) top = y
                if (y > bottom) bottom = y
                if (x < left) left = x
                if (x > right) right = x
                if (x > 0) {
                    val next = index - 1
                    if (mask[next] && !visited[next]) {
                        visited[next] = true
                        stack[stackSize++] = next
                    }
                }
                if (x + 1 < width) {
                    val next = index + 1
                    if (mask[next] && !visited[next]) {
                        visited[next] = true
                        stack[stackSize++] = next
                    }
                }
                // 8 邻通：斜向相邻的笔画也要并进同一个连通域，否则中文会被切成单字。
                if (y > 0) {
                    val next = index - width
                    if (mask[next] && !visited[next]) {
                        visited[next] = true
                        stack[stackSize++] = next
                    }
                    if (x > 0) {
                        val diagonal = next - 1
                        if (mask[diagonal] && !visited[diagonal]) {
                            visited[diagonal] = true
                            stack[stackSize++] = diagonal
                        }
                    }
                    if (x + 1 < width) {
                        val diagonal = next + 1
                        if (mask[diagonal] && !visited[diagonal]) {
                            visited[diagonal] = true
                            stack[stackSize++] = diagonal
                        }
                    }
                }
                if (y + 1 < height) {
                    val next = index + width
                    if (mask[next] && !visited[next]) {
                        visited[next] = true
                        stack[stackSize++] = next
                    }
                    if (x > 0) {
                        val diagonal = next - 1
                        if (mask[diagonal] && !visited[diagonal]) {
                            visited[diagonal] = true
                            stack[stackSize++] = diagonal
                        }
                    }
                    if (x + 1 < width) {
                        val diagonal = next + 1
                        if (mask[diagonal] && !visited[diagonal]) {
                            visited[diagonal] = true
                            stack[stackSize++] = diagonal
                        }
                    }
                }
            }
            if (count < 24) continue
            val score = scoreSum / count
            if (score < BOX_THRESHOLD) continue
            val boxWidth = right - left + 1
            val boxHeight = bottom - top + 1
            if (min(boxWidth, boxHeight) < 6) continue
            // 图标类近方形的碎片不是文字，直接丢掉，避免识别出一堆单字噪声。
            if (boxWidth < boxHeight * 1.2f && boxWidth < 60) continue
            val padX = max(2, (boxWidth * 0.06f).roundToInt())
            val padY = max(2, (boxHeight * 0.18f).roundToInt())
            boxes += Box(left - padX, top - padY, right + padX, bottom + padY)
        }
        return boxes
    }

    private fun cropBox(bitmap: Bitmap, box: Box): Bitmap? {
        val left = max(0, box.left)
        val top = max(0, box.top)
        val right = min(bitmap.width, box.right)
        val bottom = min(bitmap.height, box.bottom)
        if (right - left < 8 || bottom - top < 8) return null
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    /** DB 输出按字符连通域切开，这里把同一行的相邻框合并成整行，识别才稳定。 */
    private fun mergeIntoLines(boxes: List<Box>): List<Box> {
        if (boxes.isEmpty()) return boxes
        val sorted = boxes.sortedBy { it.top }
        val lines = ArrayList<MutableList<Box>>()
        for (box in sorted) {
            val height = box.bottom - box.top
            val centerY = (box.top + box.bottom) / 2
            var target: MutableList<Box>? = null
            for (line in lines) {
                val first = line.first()
                val lineHeight = max(first.bottom, box.bottom) - min(first.top, box.top)
                val lineCenter = line.sumOf { (it.top + it.bottom) / 2 } / line.size
                val gap = box.left - line.maxOf { it.right }
                val sameRow = kotlin.math.abs(lineCenter - centerY) <= lineHeight * 0.4f
                if (sameRow && gap >= 0 && gap <= max(height, 4) * 0.8f + 8) {
                    target = line
                    break
                }
            }
            if (target != null) {
                target += box
            } else {
                lines += mutableListOf(box)
            }
        }
        return lines.map { line ->
            Box(
                left = line.minOf { it.left },
                top = line.minOf { it.top },
                right = line.maxOf { it.right },
                bottom = line.maxOf { it.bottom },
            )
        }
    }

    private fun runRec(bitmap: Bitmap): Pair<String, Float> {
        val height = 48
        val width = max(16, (bitmap.width * height.toFloat() / bitmap.height).roundToInt())
            .coerceAtMost(1600)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        scaled.recycle()
        val input = FloatArray(3 * width * height)
        val plane = width * height
        for (index in pixels.indices) {
            val pixel = pixels[index]
            input[index] = ((pixel and 0xFF) / 255f - 0.5f) / 0.5f
            input[plane + index] = (((pixel shr 8) and 0xFF) / 255f - 0.5f) / 0.5f
            input[2 * plane + index] = (((pixel shr 16) and 0xFF) / 255f - 0.5f) / 0.5f
        }
        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, height.toLong(), width.toLong()),
        )
        tensor.use {
            recSession.run(mapOf(recInput to tensor)).use { output ->
                val out = output.get(0) as OnnxTensor
                val buffer = out.floatBuffer
                val classes = (out.info.shape.lastOrNull()?.toInt() ?: characters.size + 2)
                val steps = buffer.remaining() / classes
                if (DEBUG_LOGS) {
                    val sample = StringBuilder()
                    for (step in 0 until min(steps, 40)) {
                        var best = 0
                        var bestScore = Float.NEGATIVE_INFINITY
                        for (classIndex in 0 until classes) {
                            val value = buffer.get(step * classes + classIndex)
                            if (value > bestScore) {
                                bestScore = value
                                best = classIndex
                            }
                        }
                        sample.append(best).append('/').append(String.format("%.2f", bestScore)).append(' ')
                    }
                    Log.i(LOG_TAG, "rec crop=${bitmap.width}x${bitmap.height} remaining=${buffer.remaining()} classes=$classes steps=$steps top=$sample")
                }
                val text = StringBuilder()
                var previous = -1
                var score = 0f
                var scored = 0
                for (step in 0 until steps) {
                    var best = 0
                    var bestScore = Float.NEGATIVE_INFINITY
                    for (classIndex in 0 until classes) {
                        val value = buffer.get(step * classes + classIndex)
                        if (value > bestScore) {
                            bestScore = value
                            best = classIndex
                        }
                    }
                    if (best != 0 && best != previous) {
                        val character = characters.getOrNull(best - 1)
                        if (character != null) {
                            text.append(character)
                            score += bestScore
                            scored++
                        }
                    }
                    previous = best
                }
                return text.toString() to if (scored == 0) 0f else score / scored
            }
        }
    }

    override fun close() {
        runCatching { detSession.close() }
        runCatching { recSession.close() }
    }

    private data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private companion object {
        const val LOG_TAG = "PaddleOcr"
        /**
         * 检测网络的输入长边。状态页的小字对分辨率敏感（1024 会把「爱管侍」读成「爱管」），
         * 所以默认保持 1280；选招页文字大、只要四招和名字，调用方会显式传 1024。
         */
        const val DEFAULT_DET_LIMIT = 1280
        const val REC_HEIGHT = 48
        const val REC_MAX_WIDTH = 1600
        /** 一批最多几行、桶内宽度最多差多少像素（差太多补零会拖慢整批）。 */
        const val REC_BATCH_SIZE = 8
        const val BATCH_WIDTH_SLACK = 80
        /** 线程数：实测 4 比 6 快（6 会跑到小核上，检测反而从 331ms 变 780ms）。 */
        const val OC_RUNTIME_THREADS = 4
        const val DEBUG_LOGS = false
        const val DET_THRESHOLD = 0.3f
        const val BOX_THRESHOLD = 0.45f
        const val MEAN_R = 0.485f
        const val MEAN_G = 0.456f
        const val MEAN_B = 0.406f
        const val STD_R = 0.229f
        const val STD_G = 0.224f
        const val STD_B = 0.225f
    }
}
