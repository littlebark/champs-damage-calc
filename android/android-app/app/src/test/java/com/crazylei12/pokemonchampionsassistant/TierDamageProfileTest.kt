package com.crazylei12.pokemonchampionsassistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TierDamageProfileTest {
    private val species = JSONObject()
        .put("entityType", "species")
        .put("canonicalId", "species.armarouge")
        .put("showdownId", "Armarouge")
        .put("displayName", "红莲铠骑")

    @Test
    fun `three-tier defender set exposes four profiles covering physical and special walls`() {
        val set = tierDefenderProfileSet(species)
        val profiles = set.getJSONArray("profiles")

        assertEquals(4, profiles.length())
        assertEquals(species.toString(), set.getJSONObject("defenderSpecies").toString())
        assertEquals(listOf(TIER_PROFILE_HP2, TIER_PROFILE_HP32, TIER_PROFILE_PHYSICAL, TIER_PROFILE_SPECIAL), profileIds(profiles))
        for (index in 0 until profiles.length()) {
            val profile = profiles.getJSONObject(index)
            assertEquals(50, profile.getInt("level"))
            assertTrue(profile.optString("profileName").isNotBlank())
        }

        val hp2 = profiles.getJSONObject(0)
        assertEquals(2, hp2.getJSONObject("statPoints").getInt("hp"))
        assertEquals(0, hp2.optJSONObject("statAlignment")?.length() ?: 0)

        val hp32 = profiles.getJSONObject(1)
        assertEquals(32, hp32.getJSONObject("statPoints").getInt("hp"))
        assertEquals(0, hp32.optJSONObject("statAlignment")?.length() ?: 0)

        val physical = profiles.getJSONObject(2)
        assertEquals(32, physical.getJSONObject("statPoints").getInt("hp"))
        assertEquals(32, physical.getJSONObject("statPoints").getInt("def"))
        assertEquals("Bold", physical.getJSONObject("statAlignment").getString("showdownId"))

        val special = profiles.getJSONObject(3)
        assertEquals(32, special.getJSONObject("statPoints").getInt("hp"))
        assertEquals(32, special.getJSONObject("statPoints").getInt("spd"))
        assertEquals("Calm", special.getJSONObject("statAlignment").getString("showdownId"))
    }

    private fun profileIds(profiles: org.json.JSONArray): List<String> =
        (0 until profiles.length()).map { profiles.getJSONObject(it).getString("profileId") }
}
