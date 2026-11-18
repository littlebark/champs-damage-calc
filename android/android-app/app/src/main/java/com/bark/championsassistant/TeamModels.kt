package com.bark.championsassistant

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

data class EntityValue(
    val canonicalId: String,
    val showdownId: String,
    val displayName: String,
    val entityType: String,
    /** "M" / "F"；从画面读到性别符号时才有值，用于雌雄不同形态与按性别判定的特性。 */
    val gender: String? = null,
)

data class MoveValue(
    val entity: EntityValue,
    val basePower: Int? = null,
    val type: String? = null,
    val category: String? = null,
)

data class StatFields(
    val hp: String = "",
    val atk: String = "",
    val def: String = "",
    val spa: String = "",
    val spd: String = "",
    val spe: String = "",
) {
    fun asMap(): Map<String, String> = linkedMapOf(
        "hp" to hp,
        "atk" to atk,
        "def" to def,
        "spa" to spa,
        "spd" to spd,
        "spe" to spe,
    )

    fun toJson(includeZero: Boolean = false): JSONObject {
        val result = JSONObject()
        listOf(
            "hp" to hp,
            "atk" to atk,
            "def" to def,
            "spa" to spa,
            "spd" to spd,
            "spe" to spe,
        ).forEach { (key, raw) ->
            raw.toIntOrNull()?.let { value ->
                if (includeZero || value > 0) result.put(key, value)
            }
        }
        return result
    }

    companion object {
        fun fromMap(values: Map<String, String>) = StatFields(
            hp = values["hp"].orEmpty(),
            atk = values["atk"].orEmpty(),
            def = values["def"].orEmpty(),
            spa = values["spa"].orEmpty(),
            spd = values["spd"].orEmpty(),
            spe = values["spe"].orEmpty(),
        )
    }
}

internal fun showdownGender(raw: String?): String? = when (raw?.trim()?.lowercase()) {
    "m", "male" -> "M"
    "f", "female" -> "F"
    else -> null
}

data class PokemonConfig(
    val species: EntityValue,
    val level: Int,
    val actualStats: StatFields,
    val statPoints: StatFields,
    val ability: EntityValue?,
    val item: EntityValue?,
    val moves: List<MoveValue>,
    val statAlignment: EntityValue? = null,
    val gender: String? = null,
) {
    fun toSavedJson(): JSONObject = JSONObject().apply {
        showdownGender(gender)?.let { put("gender", it) }
        put("species", species.toJson())
        put("level", level.coerceIn(1, 100))
        put("actualStats", actualStats.toJson())
        put("statPoints", statPoints.toJson(includeZero = true))
        statAlignment?.let { put("statAlignment", it.toJson()) }
        ability?.let { put("ability", it.toJson()) }
        item?.let { put("item", it.toJson()) }
        put("moves", JSONArray().apply {
            moves.take(4).forEach { move ->
                put(JSONObject().put("move", move.entity.toJson()).put("source", "MANUAL_CURRENT"))
            }
        })
    }

    fun hasCompleteActualStats(): Boolean = actualStats.asMap().values.all { (it.toIntOrNull() ?: 0) > 0 }

    fun damageReadinessIssues(): List<String> = buildList {
        if (!hasCompleteActualStats()) add("六项实际能力值未完整填写")
        if (ability == null) add("未设置特性")
        if (moves.isEmpty()) add("至少需要一个招式")
    }

    fun isDamageReady(): Boolean = damageReadinessIssues().isEmpty()

    fun moveSlotReminder(): String? = when (moves.size) {
        0 -> "尚未设置招式；至少添加一个招式后才能用于计算。"
        in 1..3 -> "已设置 ${moves.size}/4 个招式；空技能槽是允许的，不影响保存和计算。"
        else -> null
    }
}

data class SavedTeam(
    val id: String,
    val name: String,
    val status: String,
    val damageReady: Boolean,
    val speciesSummary: String,
    val pokemon: List<PokemonConfig>,
    val userSaved: Boolean,
    val readinessIssues: List<String> = emptyList(),
)

data class PokemonEditorState(
    val speciesId: String,
    val speciesDisplay: String,
    val level: String,
    val abilityId: String,
    val itemId: String,
    val movesText: String,
    val actualStats: StatFields,
    val statPoints: StatFields,
    val knownSpecies: EntityValue,
    val knownAbility: EntityValue?,
    val knownItem: EntityValue?,
    val knownMoves: List<MoveValue>,
    val knownStatAlignment: EntityValue?,
    val gender: String? = null,
) {
    companion object {
        fun from(config: PokemonConfig) = PokemonEditorState(
            speciesId = config.species.showdownId,
            speciesDisplay = config.species.displayName,
            level = config.level.toString(),
            abilityId = config.ability?.showdownId.orEmpty(),
            itemId = config.item?.showdownId.orEmpty(),
            movesText = config.moves.joinToString(", ") { it.entity.showdownId },
            actualStats = config.actualStats,
            statPoints = config.statPoints,
            knownSpecies = config.species,
            knownAbility = config.ability,
            knownItem = config.item,
            knownMoves = config.moves,
            knownStatAlignment = config.statAlignment,
            // 记录里没单独填性别时，用识别物种时读到的性别，避免伤害计算丢掉这个信息。
            gender = config.gender ?: config.species.gender,
        )
    }

    fun toBuildJson(moveSource: String): JSONObject {
        val speciesEntity = entityForInput("species", speciesId, knownSpecies)
        return JSONObject().apply {
            put("species", speciesEntity)
            put("level", level.toIntOrNull()?.coerceIn(1, 100) ?: 50)
            put("actualStats", actualStats.toJson())
            put("statPoints", statPoints.toJson(includeZero = true))
            knownStatAlignment?.let { put("statAlignment", it.toJson()) }
            showdownGender(gender)?.let { put("gender", it) }
            if (abilityId.isNotBlank()) {
                put("ability", entityForInput("ability", abilityId, knownAbility))
            }
            if (itemId.isNotBlank()) {
                put("item", entityForInput("item", itemId, knownItem))
            }
            put("moves", JSONArray().apply {
                parsedMoveIds().forEach { moveId ->
                    val known = knownMoves.firstOrNull {
                        it.entity.showdownId.equals(moveId, ignoreCase = true)
                    }?.entity
                    put(JSONObject().apply {
                        put("move", entityForInput("move", moveId, known))
                        put("source", moveSource)
                    })
                }
            })
        }
    }

    fun parsedMoveIds(): List<String> = movesText
        .split(',', '\n', '，')
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy(String::lowercase)
}

object TeamRepository {
    /** 「我的宝可梦」是一个不断增长的列表（不是队伍）：按物种 upsert，重复的以最新一次识别为准。 */
    const val MY_POKEMON_TEAM_ID = "my-pokemon"

    fun loadMyPokemon(context: Context): SavedTeam? =
        load(context).firstOrNull { it.id == MY_POKEMON_TEAM_ID }

    fun upsertMyPokemon(context: Context, incoming: List<PokemonConfig>): SavedTeam {
        val merged = loadMyPokemon(context)?.pokemon.orEmpty().toMutableList()
        for (config in incoming) {
            val index = merged.indexOfFirst {
                it.species.showdownId.equals(config.species.showdownId, ignoreCase = true)
            }
            if (index >= 0) merged[index] = config else merged.add(config)
        }
        val members = JSONArray().apply {
            merged.forEach { put(JSONObject().put("build", it.toSavedJson())) }
        }
        val now = Instant.now().toString()
        val json = JSONObject().apply {
            put("schemaVersion", 2)
            put("kind", "SavedTeam")
            put("savedTeamId", MY_POKEMON_TEAM_ID)
            put("teamName", "我的宝可梦")
            put("teamSlotName", "")
            put("status", "CUSTOM")
            put("importStatus", "DAMAGE_READY")
            put("importSource", "MY_POKEMON")
            put("damageReady", merged.all { it.isDamageReady() })
            put("userConfirmed", true)
            put("generatedAt", now)
            put("createdAt", now)
            put("updatedAt", now)
            put("source", JSONObject().put("backend", "my_pokemon"))
            put("members", members)
            put("warnings", JSONArray())
            put("speciesSummary", merged.joinToString(" / ") { it.species.displayName })
        }
        context.filesDir.resolve("saved-teams").resolve("$MY_POKEMON_TEAM_ID.json")
            .writeUtf8Atomically(json.toString(2))
        return parseTeam(json, userSaved = true)
    }

    fun load(context: Context): List<SavedTeam> {
        val privateDirectory = context.filesDir.resolve("saved-teams")
        val saved = privateDirectory.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .sortedByDescending { it.lastModified() }
            .mapNotNull { file ->
                runCatching {
                    parseTeam(JSONObject(file.readText(Charsets.UTF_8)), userSaved = true)
                }.onFailure { error ->
                    Log.w("TeamRepository", "Ignoring unreadable saved team ${file.name}", error)
                }.getOrNull()
            }
        return saved.distinctBy(SavedTeam::id)
    }

    internal fun parseTeam(json: JSONObject, userSaved: Boolean): SavedTeam {
        val members = json.optJSONArray("pokemon") ?: json.getJSONArray("members")
        val pokemon = (0 until members.length()).map { index ->
            val member = members.getJSONObject(index)
            parsePokemon(member.optJSONObject("build") ?: member)
        }
        val name = json.optString("teamSlotName").takeIf(String::isNotBlank)
            ?: json.optString("teamName").takeIf(String::isNotBlank)
            ?: json.getString("savedTeamId")
        val summary = json.optString("speciesSummary").takeIf(String::isNotBlank)
            ?: pokemon.joinToString(" / ") { it.species.displayName }
        val readinessIssues = pokemon.flatMapIndexed { index, config ->
            config.damageReadinessIssues().map { issue -> "${index + 1}. ${config.species.displayName}：$issue" }
        }
        val ready = readinessIssues.isEmpty()
        return SavedTeam(
            id = json.getString("savedTeamId"),
            name = name,
            status = if (ready) "DAMAGE_READY" else "INCOMPLETE",
            // Recompute instead of trusting older persisted flags that only checked stats.
            damageReady = ready,
            speciesSummary = summary,
            pokemon = pokemon,
            userSaved = userSaved,
            readinessIssues = readinessIssues,
        )
    }

    private fun parsePokemon(json: JSONObject): PokemonConfig {
        val moves = json.optJSONArray("moves") ?: JSONArray()
        return PokemonConfig(
            species = parseEntity(json.getJSONObject("species")),
            gender = showdownGender(json.optString("gender")),
            level = json.optInt("level", 50),
            actualStats = parseStats(json.optJSONObject("actualStats")),
            statPoints = parseStats(json.optJSONObject("statPoints")),
            ability = json.optJSONObject("ability")?.let(::parseEntity),
            item = json.optJSONObject("item")?.let(::parseEntity),
            moves = (0 until moves.length()).mapNotNull { index ->
                moves.optJSONObject(index)?.optJSONObject("move")?.let { MoveValue(parseEntity(it)) }
            },
            statAlignment = json.optJSONObject("statAlignment")?.let(::parseEntity),
        )
    }

    private fun parseEntity(json: JSONObject) = EntityValue(
        canonicalId = json.getString("canonicalId"),
        showdownId = json.getString("showdownId"),
        displayName = json.optString("displayName", json.getString("showdownId")),
        entityType = json.optString("entityType", "species"),
    )

    private fun parseStats(json: JSONObject?): StatFields {
        fun value(key: String) = if (json?.has(key) == true) json.optInt(key).toString() else ""
        return StatFields(
            hp = value("hp"),
            atk = value("atk"),
            def = value("def"),
            spa = value("spa"),
            spd = value("spd"),
            spe = value("spe"),
        )
    }
}

fun buildDamageRequest(
    direction: String,
    own: PokemonEditorState,
    opponent: PokemonEditorState,
    selectedMove: String?,
    battleType: String,
    weather: String,
    terrain: String,
    ownReflect: Boolean,
    ownLightScreen: Boolean,
    opponentReflect: Boolean,
    opponentLightScreen: Boolean,
    critical: Boolean,
    spread: Boolean,
    tierProfiles: Boolean = false,
    attackerStages: Map<String, Int> = emptyMap(),
    defenderStages: Map<String, Int> = emptyMap(),
): String {
    val ownBuild = own.toBuildJson("OWN_BUILD").apply { putStages(attackerStages) }
    val opponentBuild = opponent.toBuildJson("PROFILE_PRESET").apply { putStages(defenderStages) }
    val root = JSONObject().apply {
        put("requestId", "android-manual-${System.currentTimeMillis()}")
        put("calculationDirection", direction)
        put("calculationMode", "EXACT")
        put("battle", JSONObject().apply {
            put("battleType", battleType)
            put("weather", weather)
            put("terrain", terrain)
            put("isCritical", critical)
            put("isSpreadMove", spread)
            put("attackerSideConditions", JSONObject())
            put("defenderSideConditions", JSONObject())
        })
    }

    if (direction == "OWN_TO_OPPONENT") {
        root.put("attackerSide", "OWN")
        root.put("defenderSide", "OPPONENT")
        root.put("attacker", ownBuild)
        root.put("defenderIdentity", JSONObject().put("species", opponentBuild.getJSONObject("species")))
        root.put(
            "defenderProfileSet",
            if (tierProfiles) tierDefenderProfileSet(opponentBuild.getJSONObject("species"))
            else exactDefenderProfile(opponentBuild),
        )
        root.put("moveSelection", if (selectedMove.isNullOrBlank()) {
            JSONObject().put("mode", "ALL_ATTACKER_MOVES")
        } else {
            JSONObject().put("mode", "ONE_MOVE").put("moveId", selectedMove)
        })
        root.getJSONObject("battle").apply {
            put("attackerSideConditions", JSONObject())
            put("defenderSideConditions", JSONObject().apply {
                put("reflect", opponentReflect)
                put("lightScreen", opponentLightScreen)
            })
        }
    } else {
        val opponentSpecies = opponentBuild.getJSONObject("species")
        root.put("attackerSide", "OPPONENT")
        root.put("defenderSide", "OWN")
        root.put("attackerIdentity", JSONObject().put("species", opponentSpecies))
        root.put("attackerProfileSet", JSONObject().apply {
            put("attackerSpecies", opponentSpecies)
            put("selectedProfileId", "manual-exact-attacker")
            put("profiles", JSONArray().put(JSONObject().apply {
                put("profileId", "manual-exact-attacker")
                put("profileName", "手动精确配置")
                put("source", "MANUAL_CURRENT")
                put("isSelected", true)
                copyBuildFields(opponentBuild, this, includeMoves = true)
            }))
        })
        root.put("defender", ownBuild)
        root.put("attackerLegalMovePool", JSONObject().apply {
            put("species", opponentSpecies)
            put("rulesetVersion", "provided-team-v1")
            put("source", "USER_PATCH")
            put("learnableMoves", JSONArray().apply {
                val moves = opponentBuild.getJSONArray("moves")
                for (index in 0 until moves.length()) {
                    put(moves.getJSONObject(index).getJSONObject("move"))
                }
            })
        })
        root.put("moveSelection", JSONObject().apply {
            put("mode", "ONE_MOVE")
            if (!selectedMove.isNullOrBlank()) put("moveId", selectedMove)
            put("source", "OPPONENT_LEGAL_MOVE_POOL")
            put("legalMovePoolVersion", "provided-team-v1")
        })
        root.getJSONObject("battle").apply {
            put("attackerSideConditions", JSONObject())
            put("defenderSideConditions", JSONObject().apply {
                put("reflect", ownReflect)
                put("lightScreen", ownLightScreen)
            })
        }
    }
    return root.toString()
}

private fun JSONObject.putStages(stages: Map<String, Int>) {
    val nonZero = stages.filterValues { it != 0 }
    if (nonZero.isEmpty()) return
    put("statStages", JSONObject().apply {
        nonZero.forEach { (stat, stage) -> put(stat, stage.coerceIn(-6, 6)) }
    })
}

private fun exactDefenderProfile(build: JSONObject) = JSONObject().apply {
    put("defenderSpecies", build.getJSONObject("species"))
    put("selectedProfileId", "manual-exact-defender")
    put("profiles", JSONArray().put(JSONObject().apply {
        put("profileId", "manual-exact-defender")
        put("profileName", "手动精确配置")
        put("source", "MANUAL_CURRENT")
        put("isSelected", true)
        put("includedInEnvelope", true)
        copyBuildFields(build, this, includeMoves = false)
    }))
}

/**
 * 对手未知配置的三档包络。四个档位一次算完：
 * 1) HP +2、其余 0、性格中性；2) HP +32、其余 0、中性；
 * 3) HP +32 + 防御 +32 + 防御性格 ×1.1（物理招）；4) HP +32 + 特防 +32 + 特防性格 ×1.1（特殊招）。
 * 展示时按招式物理/特殊在 3、4 档中自动取一。
 */
internal fun tierDefenderProfileSet(species: JSONObject, stages: JSONObject = JSONObject()): JSONObject = JSONObject().apply {
    put("defenderSpecies", species)
    put("selectedProfileId", TIER_PROFILE_HP2)
    put("profiles", JSONArray().apply {
        put(tierProfile(TIER_PROFILE_HP2, "对手 HP+2，其余 0，性格中性", JSONObject().put("hp", 2), null, stages))
        put(tierProfile(TIER_PROFILE_HP32, "对手 HP+32，其余 0，性格中性", JSONObject().put("hp", 32), null, stages))
        put(
            tierProfile(
                TIER_PROFILE_PHYSICAL,
                "对手 HP+32、防御+32、防御性格 ×1.1",
                JSONObject().put("hp", 32).put("def", 32),
                natureEntity("Bold", "大胆"),
                stages,
            ),
        )
        put(
            tierProfile(
                TIER_PROFILE_SPECIAL,
                "对手 HP+32、特防+32、特防性格 ×1.1",
                JSONObject().put("hp", 32).put("spd", 32),
                natureEntity("Calm", "温和"),
                stages,
            ),
        )
    })
}

internal const val TIER_PROFILE_HP2 = "tier.hp2"
internal const val TIER_PROFILE_HP32 = "tier.hp32"
internal const val TIER_PROFILE_PHYSICAL = "tier.hp32-def32"
internal const val TIER_PROFILE_SPECIAL = "tier.hp32-spd32"

private fun tierProfile(
    id: String,
    name: String,
    statPoints: JSONObject,
    nature: JSONObject?,
    stages: JSONObject = JSONObject(),
): JSONObject = JSONObject().apply {
    put("profileId", id)
    put("profileName", name)
    put("source", "GENERATED_TEMPLATE")
    put("level", 50)
    put("statPoints", statPoints)
    nature?.let { put("statAlignment", it) }
    if (stages.length() > 0) put("statStages", stages)
}

private fun natureEntity(showdownId: String, displayName: String): JSONObject = JSONObject().apply {
    put("entityType", "nature")
    put("canonicalId", "nature.${showdownId.lowercase()}")
    put("showdownId", showdownId)
    put("displayName", displayName)
    put("source", "system")
}

private fun copyBuildFields(source: JSONObject, target: JSONObject, includeMoves: Boolean) {
    listOf("level", "actualStats", "statPoints", "ability", "item", "gender", "statAlignment", "statStages").forEach { key ->
        if (source.has(key)) target.put(key, source.get(key))
    }
    if (includeMoves && source.has("moves")) target.put("moves", source.getJSONArray("moves"))
}

private fun entityForInput(type: String, input: String, known: EntityValue?): JSONObject {
    val exactKnown = known?.takeIf { it.showdownId.equals(input, ignoreCase = true) }
    val showdownId = input.trim()
    val normalized = showdownId.lowercase().replace(Regex("[^a-z0-9]+"), "")
    return JSONObject().apply {
        put("entityType", type)
        put("canonicalId", exactKnown?.canonicalId ?: "$type.$normalized")
        put("showdownId", exactKnown?.showdownId ?: showdownId)
        put("displayName", exactKnown?.displayName ?: showdownId)
        exactKnown?.gender?.let { put("gender", it) }
        put("source", "user")
    }
}
