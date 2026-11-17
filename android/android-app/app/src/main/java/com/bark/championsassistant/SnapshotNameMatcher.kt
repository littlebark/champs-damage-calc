package com.bark.championsassistant

/**
 * 选招页固定区域识别的「名字归一」打分器。
 *
 * 名单里有大量只差一个字的名字（喷火龙 / 喷火驼、死神棺 / 死神板），也有长短不同但互相包含的
 * 招式名（戏法 / 戏法空间）。旧实现只判断「能不能模糊命中」，再用 `maxBy { displayName.length }`
 * 挑最长的一个，同长度时按词库顺序取——喷火龙与喷火驼都是 3 个字，于是喷火龙会被认成喷火驼，
 * 戏法会被认成戏法空间。这里改成按分数排序，完全同名永远压过只差一个字。
 */
internal object SnapshotNameMatcher {
    /** 去掉性别符号等非字母数字字符再转小写（「喷火龙♀」→「喷火龙」）。 */
    fun normalize(raw: String): String = raw.filter { it.isLetterOrDigit() }.lowercase()

    /**
     * 分数：
     * 100 = 完全同名；
     * 80+ = 识别文本里含正式名（贴边字被吃掉，如「域战力」对「广域战力」）；
     * 60 = 识别文本是正式名的一段残片（只给招式用，物种名不允许，否则会拆字去撞别的名字）；
     * 40 = 只差一个字（保底纠正 OCR 个别字认错）；
     * 0 = 不匹配。
     */
    fun score(text: String, name: String, allowFragment: Boolean): Int {
        val t = normalize(text)
        val n = normalize(name)
        if (t.isEmpty() || n.isEmpty()) return 0
        if (t == n) return 100
        if (t.length >= n.length && t.contains(n)) return 80 + n.length.coerceAtMost(8)
        if (allowFragment && t.length >= 2 && n.contains(t) && n.length - t.length <= 2) return 60
        if (n.length >= 3 && t.length >= n.length) {
            for (start in 0..(t.length - n.length)) {
                var diff = 0
                for (offset in n.indices) if (t[start + offset] != n[offset]) diff++
                if (diff <= 1) return 40
            }
        }
        return 0
    }

    /** 按分数取最高分；同分取名字更长的，再同分取候选顺序靠前的（保证结果稳定）。 */
    fun <T> bestMatch(
        text: String,
        candidates: List<T>,
        nameOf: (T) -> String,
        allowFragment: Boolean,
    ): T? = candidates
        .filter { nameOf(it).length >= 2 }
        .mapNotNull { candidate ->
            score(text, nameOf(candidate), allowFragment)
                .takeIf { it > 0 }
                ?.let { candidate to it }
        }
        .maxWithOrNull(
            compareBy<Pair<T, Int>> { it.second }.thenBy { nameOf(it.first).length },
        )
        ?.first
}
