package com.bark.championsassistant

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 图鉴高级搜索条件。
 *
 * 口径（与玩家约定一致）：
 * - 属性：选 1 个 = 只要带这个属性就算（纯属性优先排序）；选 2 个 = 属性完全一致，前后顺序无关。
 * - 特性：选 1 个 = 带这个特性就算；选 2 个 = 同一形态同时带这两个。
 * - 招式：`且` = 同一形态同时学会全部；`或` = 学会任意一个。
 * - 属性 / 特性 / 招式 三类之间一律是「且」。
 */
internal data class DexFilter(
    val types: List<String> = emptyList(),
    val abilities: List<String> = emptyList(),
    val moves: List<String> = emptyList(),
    val moveMode: MatchMode = MatchMode.AND,
) {
    val activeCount: Int
        get() = types.size + abilities.size + moves.size + if (moveMode == MatchMode.OR) 1 else 0

    val isEmpty: Boolean
        get() = types.isEmpty() && abilities.isEmpty() && moves.isEmpty()
}

internal enum class MatchMode(val label: String) { AND("且"), OR("或") }

/** 命中结果：命中的那个形态（决定排序的「纯属性优先」）以及命中说明。 */
internal data class DexFilterHit(
    val form: SpeciesFormOption,
    val typeCount: Int,
)

internal object DexFilterEngine {
    private fun normalized(value: String) = normalizeShowdownId(value)

    fun matchesType(formTypes: List<String>, wanted: List<String>): Boolean = when (wanted.size) {
        0 -> true
        1 -> formTypes.any { it == wanted[0] }
        else -> formTypes.size == wanted.size && formTypes.toSet() == wanted.toSet()
    }

    private fun satisfies(form: SpeciesFormOption, filter: DexFilter): Boolean {
        if (!matchesType(form.types, filter.types)) return false

        if (filter.abilities.isNotEmpty()) {
            val wanted = filter.abilities.map(::normalized).toSet()
            val owned = form.abilities.map { normalized(it.showdownId) }.toSet()
            if (!wanted.all { it in owned }) return false
        }

        if (filter.moves.isNotEmpty()) {
            val wanted = filter.moves.map(::normalized).toSet()
            val learnset = form.learnableMoves.map { normalized(it.entity.showdownId) }.toSet()
            val hit = when (filter.moveMode) {
                MatchMode.AND -> wanted.all { it in learnset }
                MatchMode.OR -> wanted.any { it in learnset }
            }
            if (!hit) return false
        }
        return true
    }

    /**
     * 返回家族里所有命中的形态（帕底亚肯泰罗的斗战种/火炽种/水澜种都是格斗，三个形态都要各占一行）。
     * 完全不匹配返回空列表。
     */
    fun hits(family: List<SpeciesFormOption>, filter: DexFilter): List<DexFilterHit> {
        if (filter.isEmpty) {
            val base = family.firstOrNull() ?: return emptyList()
            return listOf(DexFilterHit(base, base.types.size))
        }
        return family.filter { satisfies(it, filter) }
            .map { DexFilterHit(it, it.types.size) }
    }

    /** 单个形态是否命中，供测试与复用。 */
    fun match(family: List<SpeciesFormOption>, filter: DexFilter): DexFilterHit? =
        hits(family, filter).firstOrNull()
}

/** 高级搜索里可选的一条招式 / 特性。 */
internal data class FilterOption(
    val showdownId: String,
    val label: String,
    val type: String? = null,
    val subtitle: String? = null,
    val searchKeys: List<String> = emptyList(),
) {
    fun matches(rawQuery: String): Boolean {
        val query = rawQuery.trim().lowercase()
        if (query.isEmpty()) return true
        if (label.lowercase().contains(query)) return true
        if (showdownId.lowercase().contains(query)) return true
        return searchKeys.any { it.lowercase().contains(query) }
    }
}

/**
 * 图鉴的派生数据（按编号去重后的行、招式候选项、特性候选项）。
 *
 * 这些数据只依赖仓库，不依赖界面状态；放在进程级缓存里，切换 tab 再回来时直接复用，
 * 不用每次进图鉴都重新遍历 359 个形态的学会表。
 */
internal class DexSnapshot(
    val entries: List<DexEntry>,
    val moveOptions: List<FilterOption>,
    val abilityOptions: List<FilterOption>,
) {
    companion object {
        fun build(context: android.content.Context, repository: OpponentPresetRepository): DexSnapshot {
            val forms = repository.allForms
            val entries = buildDexEntries(context, forms)
            val moveOptions = buildMoveOptions(forms)
            val abilityOptions = buildAbilityOptions(forms)
            return DexSnapshot(entries, moveOptions, abilityOptions)
        }

        // 招式和特性候选项：按规范化 id 去重，中文名做显示，英文名与 id 也能搜。
        private fun buildMoveOptions(forms: List<SpeciesFormOption>): List<FilterOption> {
            val seen = HashSet<String>()
            val result = ArrayList<FilterOption>(600)
            forms.forEach { form ->
                form.learnableMoves.forEach { move ->
                    val id = normalizeShowdownId(move.entity.showdownId)
                    if (seen.add(id)) {
                        result += FilterOption(
                            showdownId = move.entity.showdownId,
                            label = move.entity.displayName,
                            type = move.type,
                            subtitle = move.category?.let { categoryName(it) },
                            searchKeys = listOf(move.entity.canonicalId),
                        )
                    }
                }
            }
            return result.sortedBy { it.label }
        }

        private fun buildAbilityOptions(forms: List<SpeciesFormOption>): List<FilterOption> {
            val seen = HashSet<String>()
            val result = ArrayList<FilterOption>(300)
            forms.forEach { form ->
                form.abilities.forEach { ability ->
                    val id = normalizeShowdownId(ability.showdownId)
                    if (seen.add(id)) {
                        result += FilterOption(
                            showdownId = ability.showdownId,
                            label = ability.displayName,
                            searchKeys = listOf(ability.canonicalId),
                        )
                    }
                }
            }
            return result.sortedBy { it.label }
        }

        private fun categoryName(category: String): String = when (category) {
            "Physical" -> "物理"
            "Special" -> "特殊"
            else -> "变化"
        }
    }
}

internal object DexSnapshotCache {
    private var cachedRepository: OpponentPresetRepository? = null
    private var cachedSnapshot: DexSnapshot? = null

    fun of(context: android.content.Context, repository: OpponentPresetRepository): DexSnapshot {
        synchronized(this) {
            cachedSnapshot?.let { if (cachedRepository === repository) return it }
            val started = android.os.SystemClock.elapsedRealtime()
            val snapshot = DexSnapshot.build(context.applicationContext, repository)
            android.util.Log.i(
                "DexPerf",
                "snapshot built ${android.os.SystemClock.elapsedRealtime() - started}ms " +
                    "rows=${snapshot.entries.size} moves=${snapshot.moveOptions.size} abilities=${snapshot.abilityOptions.size}",
            )
            cachedRepository = repository
            cachedSnapshot = snapshot
            return snapshot
        }
    }
}

@Composable
internal fun DexFilterPage(
    filter: DexFilter,
    moveOptions: List<FilterOption>,
    abilityOptions: List<FilterOption>,
    matchCount: Int,
    onFilterChange: (DexFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    val scroll = rememberScrollState()
    BackHandler(enabled = true) { onDismiss() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("高级搜索", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "命中 $matchCount 种",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onFilterChange(DexFilter()) }) { Text("重置") }
                TextButton(onClick = onDismiss) { Text("完成") }
            }
            HorizontalDivider()

            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // ---- 属性 ----
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterSectionTitle("属性")
                    Text(
                        "选 1 个 = 带这个属性就算（纯属性排前面）；选 2 个 = 属性完全一致，顺序无关。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TYPE_NAMES_CN.keys.chunked(3).forEach { rowTypes ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowTypes.forEach { type ->
                                val selected = type in filter.types
                                TypeChip(
                                    type = type,
                                    selected = selected,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        val next = if (selected) {
                                            filter.types - type
                                        } else if (filter.types.size >= 2) {
                                            filter.types
                                        } else {
                                            filter.types + type
                                        }
                                        onFilterChange(filter.copy(types = next))
                                    },
                                )
                            }
                            repeat(3 - rowTypes.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }

                // ---- 招式 ----
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterSectionTitle("招式")
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "命中条件",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilterChip(
                            selected = filter.moveMode == MatchMode.AND,
                            onClick = { onFilterChange(filter.copy(moveMode = MatchMode.AND)) },
                            label = { Text("同时学会（且）") },
                        )
                        FilterChip(
                            selected = filter.moveMode == MatchMode.OR,
                            onClick = { onFilterChange(filter.copy(moveMode = MatchMode.OR)) },
                            label = { Text("任一（或）") },
                        )
                    }
                    SelectionList(
                        options = moveOptions,
                        selectedIds = filter.moves,
                        maxSelection = 6,
                        searchHint = "搜索招式（中文 / 英文）",
                        onToggle = { id ->
                            val next = if (id in filter.moves) filter.moves - id else filter.moves + id
                            onFilterChange(filter.copy(moves = next))
                        },
                    )
                }

                // ---- 特性 ----
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterSectionTitle("特性")
                    Text(
                        "选 1 个 = 带这个特性就算；选 2 个 = 同一形态同时带这两个。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionList(
                        options = abilityOptions,
                        selectedIds = filter.abilities,
                        maxSelection = 2,
                        searchHint = "搜索特性（中文 / 英文）",
                        onToggle = { id ->
                            val next = if (id in filter.abilities) filter.abilities - id else filter.abilities + id
                            onFilterChange(filter.copy(abilities = next))
                        },
                    )
                }
                Text(
                    "条件之间都是「且」：属性、特性、招式同时满足才会命中。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun FilterSectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
}

@Composable
private fun TypeChip(type: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color = TYPE_COLORS[type] ?: Color(0xFF9FA19F)
    Box(
        modifier
            .height(34.dp)
            .background(if (selected) color else color.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            TYPE_NAMES_CN[type] ?: type,
            color = if (selected) Color.White else color,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** 已选项 + 搜索候选列表（最多展示 12 条，靠输入收窄）。 */
@Composable
private fun SelectionList(
    options: List<FilterOption>,
    selectedIds: List<String>,
    maxSelection: Int,
    searchHint: String,
    onToggle: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val selectedOptions = remember(options, selectedIds) {
        selectedIds.mapNotNull { id -> options.firstOrNull { it.showdownId == id } }
    }
    val candidates = remember(options, query, selectedIds) {
        options.filter { it.matches(query) && it.showdownId !in selectedIds }.take(12)
    }

    if (selectedOptions.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            selectedOptions.forEach { option ->
                Card(
                    Modifier.clickable { onToggle(option.showdownId) },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F2EC)),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        option.type?.let {
                            Box(
                                Modifier.size(8.dp).background(TYPE_COLORS[it] ?: Color.Gray, RoundedCornerShape(4.dp)),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(option.label, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(6.dp))
                        Text("×", style = MaterialTheme.typography.bodySmall, color = Color(0xFF7A7F86))
                    }
                }
            }
        }
    }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text(searchHint) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (selectedIds.size >= maxSelection) {
        Text(
            "最多选 $maxSelection 个，先取消已选项再继续。",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFB26A00),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        candidates.forEach { option ->
            Card(
                Modifier.fillMaxWidth().clickable {
                    if (selectedIds.size < maxSelection) onToggle(option.showdownId)
                },
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
                shape = RoundedCornerShape(10.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    option.type?.let {
                        TypeBadge(it, modifier = Modifier.width(46.dp).height(20.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        option.label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    option.subtitle?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
