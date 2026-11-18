package com.bark.championsassistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import org.json.JSONObject
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import java.util.ArrayDeque
import kotlin.math.abs

/**
 * 用 Champions 菜单图标（786 张）识别「名牌小头像」是哪一个形态。
 *
 * 只在该物种的家族候选里比对（普通 / Mega X,Y,Z / 地区形态 / 异色）。
 * 实拍头像是这套图标的游戏内渲染版（带光影/抗锯齿），所以模板要先按底板色做一次合成，
 * 再在 0.6~1.5 倍尺度上找最大归一化互相关（NCC）——实测同一形态能到 0.75~0.80，
 * 而近亲形态（喷火龙普通 vs Mega-Y）只有 0.42-0.44，分差足够；ORB 好匹配数只当旁证。
 */
class SpriteRecognition(private val context: Context) {

    private data class Template(
        val rgba: Mat,
        val alpha: Mat,
        val gray: Mat,
        val meanColor: DoubleArray,
        val descriptors: Mat,
    )

    data class Match(
        val showdownId: String,
        val orb: Int,
        val ncc: Double,
        val secondId: String?,
        val secondOrb: Int,
        val secondNcc: Double,
        val colorDistance: Double,
        val applied: Boolean,
        val reason: String,
    )

    private val cache = LruCache<String, Template>(96)
    private var openCvReady: Boolean? = null
    private val index: Map<String, JSONObject> by lazy { loadIndex() }

    /** 词库里的 id 与素材 id 不一致的少数几条（盾形态在游戏里就是基础图标）。 */
    private fun spriteKey(id: String): String = when (id) {
        "Aegislash-Shield", "Aegislash-Both" -> normalized("Aegislash")
        else -> normalized(id)
    }

    private fun normalized(id: String): String =
        id.lowercase().replace(Regex("[^a-z0-9]+"), "")

    private fun loadIndex(): Map<String, JSONObject> = runCatching {
        val text = context.assets.open("sprites/sprite-index.json").bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val map = HashMap<String, JSONObject>(root.length() + 8)
        root.keys().forEach { key -> map[normalized(key)] = root.optJSONObject(key) ?: JSONObject() }
        map
    }.onFailure { Log.e(TAG, "读取 sprites/sprite-index.json 失败", it) }.getOrDefault(emptyMap())

    fun hasIndex(): Boolean = index.isNotEmpty()

    /** 某个形态在素材里的文件名（普通优先，找不到再退异色）。 */
    fun assetFileFor(showdownId: String, shiny: Boolean = false): String? {
        val entry = index[spriteKey(showdownId)] ?: return null
        val normal = entry.optString("normal").takeIf { it.isNotBlank() && it != "null" }
        val shinyFile = entry.optString("shiny").takeIf { it.isNotBlank() && it != "null" }
        return if (shiny) shinyFile ?: normal else normal ?: shinyFile
    }

    /**
     * 识别头像属于哪个候选形态。candidates 由调用方按家族给（含 Mega、地区形态、雌雄）。
     * 返回 null 代表识别不出来或分差不够——调用方应保持基础形态。
     */
    fun recognize(icon: Bitmap, candidates: List<String>): Match? {
        if (candidates.isEmpty() || !ensureOpenCv() || !hasIndex()) return null
        val body = IconBody.from(icon) ?: return null
        val scores = ArrayList<Match>(candidates.size)
        for (id in candidates) {
            val file = assetFileFor(id) ?: continue
            val template = templateFor(file) ?: continue
            val ncc = nccScore(body, template)
            val orb = orbMatches(body, template)
            val color = colorDistance(body, template)
            scores += Match(id, orb, ncc, null, -1, -1.0, color, false, "")
        }
        if (scores.isEmpty()) return null
        val ordered = scores.sortedWith(compareByDescending<Match> { it.ncc }.thenByDescending { it.orb })
        val best = ordered[0]
        val second = ordered.getOrNull(1)
        val marginNcc = if (second == null) best.ncc else best.ncc - second.ncc
        val marginOrb = if (second == null) best.orb else best.orb - second.orb

        var applied = false
        var reason = "NCC ${fmt(best.ncc)} 分差 ${fmt(marginNcc)} 不足，按基础形态算"
        if (best.ncc >= MIN_NCC && marginNcc >= MIN_NCC_MARGIN) {
            applied = true
            reason = "NCC ${fmt(best.ncc)} 领先 ${fmt(marginNcc)}"
        } else if (best.orb >= MIN_ORB && marginOrb >= MIN_ORB_MARGIN) {
            applied = true
            reason = "ORB ${best.orb} 领先 ${marginOrb}"
        }
        return best.copy(
            secondId = second?.showdownId,
            secondOrb = second?.orb ?: -1,
            secondNcc = second?.ncc ?: -1.0,
            applied = applied,
            reason = reason,
        )
    }

    fun describe(match: Match?): String = when {
        match == null -> "无结果"
        else -> "best=${match.showdownId} ncc=${fmt(match.ncc)} orb=${match.orb} " +
            "second=${match.secondId ?: "-"}(${fmt(match.secondNcc)}/${match.secondOrb}) " +
            "色差=${fmt(match.colorDistance)} applied=${match.applied} (${match.reason})"
    }

    // --- 内部实现 ---

    private fun ensureOpenCv(): Boolean {
        openCvReady?.let { return it }
        val ok = runCatching { OpenCVLoader.initLocal() }.getOrElse { false }
        if (!ok) Log.e(TAG, "OpenCV 初始化失败，形态识别不可用")
        openCvReady = ok
        return ok
    }

    private fun templateFor(file: String): Template? {
        cache.get(file)?.let { return it }
        val bitmap = runCatching {
            context.assets.open("sprites/$file").use { stream -> BitmapFactory.decodeStream(stream) }
        }.getOrNull() ?: return null
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val alpha = Mat()
        Core.extractChannel(rgba, alpha, 3)
        val bbox = alphaBoundingBox(alpha)
        if (bbox.width <= 0 || bbox.height <= 0) {
            rgba.release()
            alpha.release()
            return null
        }
        val croppedRgba = Mat(rgba, bbox).clone()
        val croppedAlpha = Mat(alpha, bbox).clone()
        val gray = Mat()
        Imgproc.cvtColor(croppedRgba, gray, Imgproc.COLOR_RGBA2GRAY)
        val meanColor = meanColorOf(croppedRgba, croppedAlpha)

        val orb = ORB.create(ORB_FEATURES, 1.2f, 8, 31, 0, 2, ORB.HARRIS_SCORE, 31, 20)
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        orb.detectAndCompute(gray, Mat(), keypoints, descriptors)

        val template = Template(croppedRgba, croppedAlpha, gray, meanColor, descriptors)
        rgba.release()
        alpha.release()
        cache.put(file, template)
        return template
    }

    private fun alphaBoundingBox(alpha: Mat): org.opencv.core.Rect {
        val w = alpha.cols()
        val h = alpha.rows()
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (alpha.get(y, x)[0] > 8.0) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < 0) return org.opencv.core.Rect(0, 0, w, h)
        return org.opencv.core.Rect(left, top, right - left + 1, bottom - top + 1)
    }

    private fun meanColorOf(rgba: Mat, alpha: Mat): DoubleArray {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var n = 0.0
        for (y in 0 until rgba.rows()) {
            for (x in 0 until rgba.cols()) {
                if (alpha.get(y, x)[0] < 32.0) continue
                val px = rgba.get(y, x)
                r += px[0]
                g += px[1]
                b += px[2]
                n += 1.0
            }
        }
        if (n == 0.0) return doubleArrayOf(0.0, 0.0, 0.0)
        return doubleArrayOf(r / n, g / n, b / n)
    }

    /**
     * 实拍头像：先按底板纯色把本体抠出来（名牌底板是纯洋红/紫），只留最大连通块。
     * 底板色不能取整块 ROI 的边缘（ROI 会带上名牌外的场景），改为取 ROI 里出现最多的亮色。
     */
    private class IconBody(
        val gray: Mat,
        val meanColor: DoubleArray,
        val plate: DoubleArray,
    ) {
        companion object {
            fun from(icon: Bitmap): IconBody? {
                val rgba = Mat()
                Utils.bitmapToMat(icon, rgba)
                val w = rgba.cols()
                val h = rgba.rows()
                if (w < 24 || h < 24) {
                    rgba.release()
                    return null
                }
                val pixels = DoubleArray(w * h * 3)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val px = rgba.get(y, x)
                        val i = (y * w + x) * 3
                        pixels[i] = px[0]
                        pixels[i + 1] = px[1]
                        pixels[i + 2] = px[2]
                    }
                }
                val plate = plateColor(pixels) ?: run {
                    rgba.release()
                    return null
                }
                // 底板像素范围 → 只在这块里面找本体，避免把名牌外的场景当成前景
                var left = w
                var right = -1
                var top = h
                var bottom = -1
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val i = (y * w + x) * 3
                        val d = abs(pixels[i] - plate[0]) + abs(pixels[i + 1] - plate[1]) + abs(pixels[i + 2] - plate[2])
                        if (d < PLATE_TOLERANCE) {
                            if (x < left) left = x
                            if (x > right) right = x
                            if (y < top) top = y
                            if (y > bottom) bottom = y
                        }
                    }
                }
                if (right < left || bottom < top) {
                    rgba.release()
                    return null
                }
                val subW = right - left + 1
                val subH = bottom - top + 1
                val fg = BooleanArray(subW * subH)
                for (y in 0 until subH) {
                    for (x in 0 until subW) {
                        val i = ((y + top) * w + (x + left)) * 3
                        val d = abs(pixels[i] - plate[0]) + abs(pixels[i + 1] - plate[1]) + abs(pixels[i + 2] - plate[2])
                        if (d > BODY_TOLERANCE) fg[y * subW + x] = true
                    }
                }
                val component = largestComponent(fg, subW, subH) ?: run {
                    rgba.release()
                    return null
                }
                var minX = subW
                var maxX = -1
                var minY = subH
                var maxY = -1
                var r = 0.0
                var g = 0.0
                var b = 0.0
                var n = 0.0
                for (idx in component) {
                    val x = idx % subW
                    val y = idx / subW
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    val i = ((y + top) * w + (x + left)) * 3
                    r += pixels[i]
                    g += pixels[i + 1]
                    b += pixels[i + 2]
                    n += 1.0
                }
                val cw = maxX - minX + 1
                val ch = maxY - minY + 1
                if (cw < 8 || ch < 8 || n == 0.0) {
                    rgba.release()
                    return null
                }
                val crop = Bitmap.createBitmap(icon, left + minX, top + minY, cw, ch)
                val cropRgba = Mat()
                Utils.bitmapToMat(crop, cropRgba)
                val gray = Mat()
                Imgproc.cvtColor(cropRgba, gray, Imgproc.COLOR_RGBA2GRAY)
                cropRgba.release()
                rgba.release()
                return IconBody(gray, doubleArrayOf(r / n, g / n, b / n), plate)
            }

            /** ROI 里出现最多、且够亮的量化颜色（名牌底色），再回原像素取它的真实值。 */
            private fun plateColor(pixels: DoubleArray): DoubleArray? {
                val bins = HashMap<Int, Int>()
                val count = pixels.size / 3
                for (i in 0 until count) {
                    val r = (pixels[i * 3] / 24.0).toInt()
                    val g = (pixels[i * 3 + 1] / 24.0).toInt()
                    val b = (pixels[i * 3 + 2] / 24.0).toInt()
                    val key = r * 441 + g * 21 + b
                    bins[key] = (bins[key] ?: 0) + 1
                }
                val dominant = bins.entries
                    .sortedByDescending { it.value }
                    .take(8)
                    .firstOrNull { entry ->
                        val r = entry.key / 441
                        val g = (entry.key / 21) % 21
                        val b = entry.key % 21
                        entry.value >= count * 0.05 && r + g + b >= 6
                    } ?: return null
                val key = dominant.key
                val targetR = (key / 441) * 24.0 + 12.0
                val targetG = ((key / 21) % 21) * 24.0 + 12.0
                val targetB = (key % 21) * 24.0 + 12.0
                var bestDist = Double.MAX_VALUE
                var best = -1
                for (i in 0 until count) {
                    val d = abs(pixels[i * 3] - targetR) +
                        abs(pixels[i * 3 + 1] - targetG) +
                        abs(pixels[i * 3 + 2] - targetB)
                    if (d < bestDist) {
                        bestDist = d
                        best = i
                    }
                }
                if (best < 0) return null
                return doubleArrayOf(pixels[best * 3], pixels[best * 3 + 1], pixels[best * 3 + 2])
            }

            private fun largestComponent(fg: BooleanArray, w: Int, h: Int): List<Int>? {
                val seen = BooleanArray(w * h)
                var best: List<Int>? = null
                var bestSize = 0
                val queue = ArrayDeque<Int>()
                for (start in fg.indices) {
                    if (!fg[start] || seen[start]) continue
                    queue.clear()
                    queue.add(start)
                    seen[start] = true
                    val list = ArrayList<Int>(256)
                    while (queue.isNotEmpty()) {
                        val cur = queue.removeFirst()
                        list += cur
                        val x = cur % w
                        val y = cur / w
                        if (x + 1 < w) {
                            val n = cur + 1
                            if (fg[n] && !seen[n]) { seen[n] = true; queue.add(n) }
                        }
                        if (x > 0) {
                            val n = cur - 1
                            if (fg[n] && !seen[n]) { seen[n] = true; queue.add(n) }
                        }
                        if (y + 1 < h) {
                            val n = cur + w
                            if (fg[n] && !seen[n]) { seen[n] = true; queue.add(n) }
                        }
                        if (y > 0) {
                            val n = cur - w
                            if (fg[n] && !seen[n]) { seen[n] = true; queue.add(n) }
                        }
                    }
                    if (list.size > bestSize) {
                        bestSize = list.size
                        best = list
                    }
                }
                return best
            }
        }
    }

    private fun orbMatches(body: IconBody, template: Template): Int {
        val orb = ORB.create(ORB_FEATURES, 1.2f, 8, 31, 0, 2, ORB.HARRIS_SCORE, 31, 20)
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        orb.detectAndCompute(body.gray, Mat(), keypoints, descriptors)
        if (descriptors.rows() < 4 || template.descriptors.rows() < 4) return 0
        val matcher = BFMatcher.create(Core.NORM_HAMMING, true)
        val matches = MatOfDMatch()
        matcher.match(descriptors, template.descriptors, matches)
        var good = 0
        matches.toArray().forEach { if (it.distance < ORB_MAX_DISTANCE) good++ }
        descriptors.release()
        matches.release()
        return good
    }

    /** 把模板按名牌底色合成成「游戏里那种」灰度图，再在 0.6~1.5 倍尺度上找最高 NCC。 */
    private fun nccScore(body: IconBody, template: Template): Double {
        val composed = Mat(template.gray.rows(), template.gray.cols(), CvType.CV_8UC1)
        for (y in 0 until template.gray.rows()) {
            for (x in 0 until template.gray.cols()) {
                val a = template.alpha.get(y, x)[0] / 255.0
                val px = template.rgba.get(y, x)
                val r = px[0] * a + body.plate[0] * (1 - a)
                val g = px[1] * a + body.plate[1] * (1 - a)
                val b = px[2] * a + body.plate[2] * (1 - a)
                composed.put(y, x, 0.114 * r + 0.587 * g + 0.299 * b)
            }
        }
        var best = -1.0
        var scale = MIN_SCALE
        val result = Mat()
        while (scale <= MAX_SCALE + 0.001) {
            val width = (composed.cols() * scale).toInt()
            val height = (composed.rows() * scale).toInt()
            if (width >= 6 && height >= 6 && width <= body.gray.cols() && height <= body.gray.rows()) {
                val scaled = Mat()
                Imgproc.resize(composed, scaled, Size(width.toDouble(), height.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                Imgproc.matchTemplate(body.gray, scaled, result, Imgproc.TM_CCOEFF_NORMED)
                val score = Core.minMaxLoc(result).maxVal
                if (score > best) best = score
                scaled.release()
            }
            scale += SCALE_STEP
        }
        composed.release()
        result.release()
        return best
    }

    private fun colorDistance(body: IconBody, template: Template): Double {
        val dr = body.meanColor[0] - template.meanColor[0]
        val dg = body.meanColor[1] - template.meanColor[1]
        val db = body.meanColor[2] - template.meanColor[2]
        return kotlin.math.sqrt(dr * dr + dg * dg + db * db) / 255.0
    }

    private fun fmt(value: Double): String = String.format(java.util.Locale.US, "%.3f", value)

    companion object {
        private const val TAG = "SpriteRecognition"
        private const val ORB_FEATURES = 3000
        private const val ORB_MAX_DISTANCE = 35
        private const val MIN_ORB = 12
        private const val MIN_ORB_MARGIN = 8
        /** 实测同一形态 NCC 0.75~0.80，近亲形态 0.42~0.44，这里取 0.72 + 领先 0.10 才算数。 */
        private const val MIN_NCC = 0.72
        private const val MIN_NCC_MARGIN = 0.10
        private const val MIN_SCALE = 0.60
        private const val MAX_SCALE = 1.50
        private const val SCALE_STEP = 0.05
        private const val PLATE_TOLERANCE = 60.0
        private const val BODY_TOLERANCE = 75.0
    }
}
