package com.bark.championsassistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BattleAssistantModeTest {
    @Test
    fun `standard is the safe default while hud mode opts into automatic display`() {
        assertEquals(BattleAssistantMode.STANDARD, BattleAssistantMode.fromWireName(null))
        assertEquals(BattleAssistantMode.STANDARD, BattleAssistantMode.fromWireName("unknown"))
        assertFalse(BattleAssistantMode.STANDARD.autoOpenDirectHud)
        assertTrue(BattleAssistantMode.STANDARD.usesFloatingBubble)
        assertTrue(BattleAssistantMode.HUD.autoOpenDirectHud)
        assertFalse(BattleAssistantMode.HUD.usesFloatingBubble)
        assertEquals(BattleAssistantMode.HUD, BattleAssistantMode.fromWireName("hud"))
    }

    @Test
    fun `hud mode is selectable by wire name`() {
        assertEquals(BattleAssistantMode.HUD, BattleAssistantMode.fromWireName("hud"))
    }
}
