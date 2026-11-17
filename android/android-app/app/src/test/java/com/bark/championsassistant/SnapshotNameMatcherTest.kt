package com.bark.championsassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 选招页名字归一：同长度、只差一个字的名字必须让位给完全同名。
 * 真机事故：屏幕上是「喷火龙♀」，OCR 读对了，但匹配层按名字长度挑，结果认成 3 个字的「喷火驼」，
 * 于是对方被当成没有 Mega Y 的喷火驼，点 M-Y 后伤害一分不变。
 */
class SnapshotNameMatcherTest {
    private val species = listOf("喷火龙", "喷火驼", "死神棺", "死神板", "魔幻假面喵", "爱管侍")
    private val moves = listOf("戏法", "戏法空间", "广域战力", "魔法火焰")

    @Test
    fun `完全同名压过只差一个字`() {
        assertEquals("喷火龙", SnapshotNameMatcher.bestMatch("喷火龙♀", species, { it }, false))
        assertEquals("喷火驼", SnapshotNameMatcher.bestMatch("喷火驼", species, { it }, false))
    }

    @Test
    fun `只差一个字仍然能纠正个别字读错`() {
        assertEquals("喷火龙", SnapshotNameMatcher.bestMatch("喷大龙", species, { it }, false))
        assertEquals("死神棺", SnapshotNameMatcher.bestMatch("死神枢", species, { it }, false))
    }

    @Test
    fun `招式短名不会被长名抢走`() {
        assertEquals("戏法", SnapshotNameMatcher.bestMatch("戏法", moves, { it }, true))
        assertEquals("戏法空间", SnapshotNameMatcher.bestMatch("戏法空间", moves, { it }, true))
    }

    @Test
    fun `招式允许吃掉首字的残片`() {
        assertEquals("广域战力", SnapshotNameMatcher.bestMatch("域战力", moves, { it }, true))
    }

    @Test
    fun `物种名不允许残片匹配`() {
        assertNull(SnapshotNameMatcher.bestMatch("火驼", species, { it }, false))
    }

    @Test
    fun `打分表本身`() {
        assertEquals(100, SnapshotNameMatcher.score("喷火龙♀", "喷火龙", false))
        assertEquals(40, SnapshotNameMatcher.score("喷火龙", "喷火驼", false))
        assertEquals(100, SnapshotNameMatcher.score("戏法", "戏法", true))
        assertEquals(60, SnapshotNameMatcher.score("戏法", "戏法空间", true))
        assertEquals(0, SnapshotNameMatcher.score("", "戏法", true))
    }
}
