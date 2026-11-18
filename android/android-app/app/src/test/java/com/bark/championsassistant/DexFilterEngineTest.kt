package com.bark.championsassistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图鉴高级搜索的匹配口径：
 * - 属性：单属性=带该属性即可；双属性=完全一致且顺序无关。
 * - 特性：单特性=带即可；双特性=同一形态同时带。
 * - 招式：且=同一形态全会；或=任一。
 * - 三类之间是「且」。
 */
class DexFilterEngineTest {

    private fun move(id: String, cn: String) = MoveValue(
        entity = EntityValue("move.$id", id, cn, "move"),
        basePower = 0,
        type = "Psychic",
        category = "Status",
    )

    private fun ability(id: String, cn: String) = EntityValue("ability.$id", id, cn, "ability")

    private fun form(
        showdownId: String,
        types: List<String>,
        moves: List<String> = emptyList(),
        abilities: List<String> = emptyList(),
    ): SpeciesFormOption {
        val species = EntityValue("species.$showdownId", showdownId, showdownId, "species")
        return SpeciesFormOption(
            familyId = showdownId.lowercase(),
            configurationShareGroupId = null,
            species = species,
            types = types,
            baseStats = StatFields(),
            defaultAbility = abilities.firstOrNull()?.let { ability(it, it) },
            abilities = abilities.map { ability(it, it) },
            learnableMoves = moves.map { move(it, it) },
        )
    }

    @Test
    fun singleTypeMatchesPureAndDual() {
        val pure = listOf(form("Mono", listOf("Fairy")))
        val dual = listOf(form("Duo", listOf("Steel", "Fairy")))
        val filter = DexFilter(types = listOf("Fairy"))
        assertNotNull(DexFilterEngine.match(pure, filter))
        assertNotNull(DexFilterEngine.match(dual, filter))
        val miss = listOf(form("Other", listOf("Water")))
        assertNull(DexFilterEngine.match(miss, filter))
    }

    @Test
    fun twoTypesRequireExactMatchRegardlessOfOrder() {
        val filter = DexFilter(types = listOf("Dragon", "Fairy"))
        assertNotNull(DexFilterEngine.match(listOf(form("Altaria-Mega", listOf("Fairy", "Dragon"))), filter))
        assertNull(DexFilterEngine.match(listOf(form("Dragonite", listOf("Dragon", "Flying"))), filter))
        assertNull(DexFilterEngine.match(listOf(form("Xerneas", listOf("Fairy"))), filter))
    }

    @Test
    fun moveAndRequiresBothInSameForm() {
        val filter = DexFilter(moves = listOf("Trick Room", "Healing Wish"), moveMode = MatchMode.AND)
        val indeedee = listOf(
            form("Indeedee", listOf("Psychic", "Normal"), moves = listOf("Trick Room", "Healing Wish")),
        )
        assertNotNull(DexFilterEngine.match(indeedee, filter))
        val onlyTrickRoom = listOf(form("Slowking", listOf("Water", "Psychic"), moves = listOf("Trick Room")))
        assertNull(DexFilterEngine.match(onlyTrickRoom, filter))
    }

    @Test
    fun moveOrAcceptsEither() {
        val filter = DexFilter(moves = listOf("Trick Room", "Tailwind"), moveMode = MatchMode.OR)
        assertNotNull(
            DexFilterEngine.match(
                listOf(form("Whimsicott", listOf("Grass", "Fairy"), moves = listOf("Tailwind"))),
                filter,
            ),
        )
        assertNull(
            DexFilterEngine.match(
                listOf(form("Pikachu", listOf("Electric"), moves = listOf("Thunderbolt"))),
                filter,
            ),
        )
    }

    @Test
    fun idComparisonIgnoresSpacesAndCase() {
        val filter = DexFilter(moves = listOf("trickroom"))
        assertNotNull(
            DexFilterEngine.match(
                listOf(form("Slowking", listOf("Water", "Psychic"), moves = listOf("Trick Room"))),
                filter,
            ),
        )
    }

    @Test
    fun abilityRequiresBothOnSameForm() {
        val both = listOf(form("Scary", listOf("Dark"), abilities = listOf("Intimidate", "Levitate")))
        val twoAbilities = DexFilter(abilities = listOf("Intimidate", "Levitate"))
        assertNotNull(DexFilterEngine.match(both, twoAbilities))

        val single = listOf(form("Scary", listOf("Dark"), abilities = listOf("Intimidate")))
        assertNull(DexFilterEngine.match(single, twoAbilities))
        assertNotNull(DexFilterEngine.match(single, DexFilter(abilities = listOf("Intimidate"))))
    }

    @Test
    fun categoriesCombineWithAnd() {
        val filter = DexFilter(types = listOf("Fairy"), moves = listOf("Trick Room"))
        val hit = listOf(form("Hatterene", listOf("Psychic", "Fairy"), moves = listOf("Trick Room")))
        assertNotNull(DexFilterEngine.match(hit, filter))
        val wrongType = listOf(form("Slowking", listOf("Water", "Psychic"), moves = listOf("Trick Room")))
        assertNull(DexFilterEngine.match(wrongType, filter))
    }

    @Test
    fun singleTypeReturnsEveryMatchingFormInFamily() {
        // 肯泰罗家族：普通种是一般属性，帕底亚三个品种都带格斗，按格斗搜要三个都命中。
        val tauros = listOf(
            form("Tauros", listOf("Normal")),
            form("Tauros-Paldea-Combat", listOf("Fighting")),
            form("Tauros-Paldea-Blaze", listOf("Fighting", "Fire")),
            form("Tauros-Paldea-Aqua", listOf("Fighting", "Water")),
        )
        val hits = DexFilterEngine.hits(tauros, DexFilter(types = listOf("Fighting")))
        assertTrue(hits.map { it.form.species.showdownId }.containsAll(
            listOf("Tauros-Paldea-Combat", "Tauros-Paldea-Blaze", "Tauros-Paldea-Aqua"),
        ))
        assertFalse(hits.any { it.form.species.showdownId == "Tauros" })
    }

    @Test
    fun emptyFilterMatchesEverything() {
        assertNotNull(DexFilterEngine.match(listOf(form("Any", listOf("Normal"))), DexFilter()))
        assertTrue(DexFilter().isEmpty)
        assertFalse(DexFilter(types = listOf("Normal")).isEmpty)
    }
}
