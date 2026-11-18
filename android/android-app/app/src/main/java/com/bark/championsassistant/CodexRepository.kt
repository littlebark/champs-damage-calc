package com.bark.championsassistant

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 冠军百科层：招式 / 特性 / 持有物的说明文字，来自 gamewith 宝可梦冠军工具离线页。
 * 只补充展示信息（描述、命中、PP、对象、性质标签），伤害计算仍走 champions-presets.json。
 */
internal data class MoveCodex(
    val showdownId: String,
    val name: String,
    val type: String,
    val category: String,
    val power: Int,
    val accuracy: Int,
    val pp: Int,
    val flags: List<String>,
    val target: String,
    val description: String,
)

internal data class AbilityCodex(
    val showdownId: String,
    val name: String,
    val description: String,
)

internal data class ItemCodex(
    val showdownId: String,
    val name: String,
    val description: String,
    val tags: List<String>,
    val icon: String,
)

internal class CodexRepository(context: Context) {

    private val appContext = context.applicationContext

    private val movesById: Map<String, MoveCodex> by lazy {
        val root = load() ?: return@lazy emptyMap()
        val moves = root.optJSONObject("moves") ?: JSONObject()
        val result = HashMap<String, MoveCodex>(moves.length() + 8)
        moves.keys().forEach { key ->
            val entry = moves.optJSONObject(key) ?: return@forEach
            result[key] = MoveCodex(
                showdownId = entry.optString("showdownId"),
                name = entry.optString("name"),
                type = entry.optString("type"),
                category = entry.optString("category"),
                power = entry.optInt("power", 0),
                accuracy = entry.optInt("accuracy", 0),
                pp = entry.optInt("pp", 0),
                flags = entry.optJSONArray("flags").toStringList(),
                target = entry.optString("target"),
                description = entry.optString("description"),
            )
        }
        result
    }

    private val abilitiesById: Map<String, AbilityCodex> by lazy {
        val root = load() ?: return@lazy emptyMap()
        val abilities = root.optJSONObject("abilities") ?: JSONObject()
        val result = HashMap<String, AbilityCodex>(abilities.length() + 8)
        abilities.keys().forEach { key ->
            val entry = abilities.optJSONObject(key) ?: return@forEach
            result[key] = AbilityCodex(
                showdownId = entry.optString("showdownId"),
                name = entry.optString("name"),
                description = entry.optString("description"),
            )
        }
        result
    }

    private val itemsById: Map<String, ItemCodex> by lazy {
        val root = load() ?: return@lazy emptyMap()
        val items = root.optJSONObject("items") ?: JSONObject()
        val result = HashMap<String, ItemCodex>(items.length() + 8)
        items.keys().forEach { key ->
            val entry = items.optJSONObject(key) ?: return@forEach
            result[key] = ItemCodex(
                showdownId = entry.optString("showdownId"),
                name = entry.optString("name"),
                description = entry.optString("description"),
                tags = entry.optJSONArray("tags").toStringList(),
                icon = entry.optString("icon"),
            )
        }
        result
    }

    val items: List<ItemCodex> by lazy {
        itemsById.values.sortedWith(compareBy<ItemCodex> { itemOrder(it.tags) }.thenBy { it.name })
    }

    fun moveFor(showdownId: String): MoveCodex? = movesById[normalizeShowdownId(showdownId)]

    fun abilityFor(showdownId: String): AbilityCodex? = abilitiesById[normalizeShowdownId(showdownId)]

    fun itemFor(showdownId: String): ItemCodex? = itemsById[normalizeShowdownId(showdownId)]

    private fun load(): JSONObject? = rootCache.getOrPut(ASSET_PATH) {
        runCatching {
            val text = appContext.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
            JSONObject(text)
        }.getOrNull()
    }

    private fun itemOrder(tags: List<String>): Int = when {
        "初始持有" in tags -> 0
        "可购买" in tags -> 1
        else -> 2
    }

    private companion object {
        const val ASSET_PATH = "codex/champions-codex.zh-Hans.json"
        val rootCache = ConcurrentHashMap<String, JSONObject?>()
    }
}

private fun org.json.JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val list = ArrayList<String>(length())
    for (index in 0 until length()) {
        optString(index).takeIf { it.isNotBlank() }?.let(list::add)
    }
    return list
}
