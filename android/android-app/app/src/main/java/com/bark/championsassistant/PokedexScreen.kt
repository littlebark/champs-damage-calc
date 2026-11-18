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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal val TYPE_COLORS = mapOf(
    "Normal" to Color(0xFF9FA19F),
    "Fighting" to Color(0xFFFF7F00),
    "Flying" to Color(0xFF81B9EF),
    "Poison" to Color(0xFF9141CB),
    "Ground" to Color(0xFF915121),
    "Rock" to Color(0xFFAFA981),
    "Bug" to Color(0xFF91A119),
    "Ghost" to Color(0xFF704170),
    "Steel" to Color(0xFF60A1B8),
    "Fire" to Color(0xFFE62829),
    "Water" to Color(0xFF2980EF),
    "Grass" to Color(0xFF3FA129),
    "Electric" to Color(0xFFFAC000),
    "Psychic" to Color(0xFFEF4179),
    "Ice" to Color(0xFF3DCEF3),
    "Dragon" to Color(0xFF5060E1),
    "Dark" to Color(0xFF624D4E),
    "Fairy" to Color(0xFFEF70EF),
)

internal val TYPE_NAMES_CN = mapOf(
    "Normal" to "一般",
    "Fighting" to "格斗",
    "Flying" to "飞行",
    "Poison" to "毒",
    "Ground" to "地面",
    "Rock" to "岩石",
    "Bug" to "虫",
    "Ghost" to "幽灵",
    "Steel" to "钢",
    "Fire" to "火",
    "Water" to "水",
    "Grass" to "草",
    "Electric" to "电",
    "Psychic" to "超能力",
    "Ice" to "冰",
    "Dragon" to "龙",
    "Dark" to "恶",
    "Fairy" to "妖精",
)

private val STAT_LABELS = listOf(
    "hp" to "HP",
    "atk" to "攻击",
    "def" to "防御",
    "spa" to "特攻",
    "spd" to "特防",
    "spe" to "速度",
)

/** 图鉴里的一行：一个图鉴编号 + 它默认展示的形态（坚盾剑怪只显示盾牌形态、谜拟丘只显示普通形态）。 */
internal data class DexEntry(
    val dex: Int?,
    val base: SpeciesFormOption,
    val family: List<SpeciesFormOption>,
)

internal fun buildDexEntries(context: android.content.Context, forms: List<SpeciesFormOption>): List<DexEntry> {
    val byDex = LinkedHashMap<Int, MutableList<SpeciesFormOption>>()
    val withoutDex = ArrayList<SpeciesFormOption>()
    forms.forEach { form ->
        val dex = SpriteAssetCatalog.dex(context, form.species.showdownId)
        if (dex == null) {
            withoutDex += form
        } else {
            byDex.getOrPut(dex) { mutableListOf() } += form
        }
    }
    val entries = byDex.entries.sortedBy { it.key }.map { (dex, group) ->
        DexEntry(dex, baseFormOf(group), group)
    }
    return entries + withoutDex.map { DexEntry(null, it, listOf(it)) }
}

private fun baseFormOf(group: List<SpeciesFormOption>): SpeciesFormOption {
    group.firstOrNull { !it.species.showdownId.contains('-') }?.let { return it }
    group.firstOrNull { it.species.showdownId.endsWith("-Shield", ignoreCase = true) }?.let { return it }
    group.firstOrNull { it.species.showdownId.endsWith("-Eternal", ignoreCase = true) }?.let { return it }
    return group.firstOrNull { !isMegaSpeciesForm(it.species.showdownId) } ?: group.first()
}

@Composable
internal fun PokedexScreen(repository: OpponentPresetRepository, codex: CodexRepository) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedShowdownId by rememberSaveable { mutableStateOf("") }
    var filterOpen by rememberSaveable { mutableStateOf(false) }
    var filter by remember { mutableStateOf(DexFilter()) }
    val forms = remember(repository) { repository.allForms }
    val context = LocalContext.current
    // 派生数据（去重后的图鉴行、招式/特性候选）走进程级缓存，切 tab 回来不再重建。
    val snapshot = remember(context, repository) { DexSnapshotCache.of(context, repository) }
    val entries = snapshot.entries
    val moveOptions = snapshot.moveOptions
    val abilityOptions = snapshot.abilityOptions
    val selected = forms.firstOrNull { it.species.showdownId == selectedShowdownId }
    val selectedFamily = remember(selected, forms) {
        selected?.let { repository.formsFor(it.species) }.orEmpty()
            .ifEmpty { entries.firstOrNull { it.base.species.showdownId == selectedShowdownId }?.family.orEmpty() }
    }

    // 系统返回键在详情页要回图鉴列表，而不是直接退出 app。
    BackHandler(enabled = selected != null) { selectedShowdownId = "" }

    if (selected != null) {
        PokemonDetailScreen(
            initialForm = selected,
            family = selectedFamily,
            repository = repository,
            codex = codex,
            onBack = { selectedShowdownId = "" },
        )
        return
    }

    val matched = remember(entries, query, filter) {
        val started = android.os.SystemClock.elapsedRealtime()
        val normalized = query.trim().lowercase()
        val result = entries.mapNotNull { entry ->
            val nameHit = normalized.isEmpty() || entry.family.any { form ->
                form.species.displayName.lowercase().contains(normalized) ||
                    form.species.showdownId.lowercase().contains(normalized) ||
                    form.species.canonicalId.lowercase().contains(normalized)
            }
            if (!nameHit) return@mapNotNull null
            val hits = DexFilterEngine.hits(entry.family, filter)
            if (hits.isEmpty()) null else entry to hits
        }
        android.util.Log.i("DexPerf", "matched ${android.os.SystemClock.elapsedRealtime() - started}ms entries=${entries.size}")
        result
    }

    // 按属性搜时，同一家族里每个命中的形态各占一行（帕底亚肯泰罗三个形态都是格斗，要都列出来）；
    // 只按招式/特性搜时同家族形态的学会表基本一致，一个家族只列一行，免得刷屏。
    // 排序：纯属性在前，其余按图鉴编号。
    val rows = remember(matched, filter.types, filter.isEmpty) {
        val expanded = matched.flatMap { (entry, hits) ->
            val chosen = if (filter.types.isEmpty() && !filter.isEmpty) hits.take(1) else hits
            chosen.map { hit -> Triple(entry, hit.form, hit.typeCount) }
        }
        if (filter.types.isEmpty()) {
            expanded
        } else {
            expanded.sortedWith(
                compareBy({ it.third }, { it.first.dex ?: Int.MAX_VALUE }),
            )
        }
    }

    val filterSummary = remember(filter, moveOptions, abilityOptions) {
        buildList {
            if (filter.types.isNotEmpty()) {
                add("属性 " + filter.types.joinToString(" / ") { TYPE_NAMES_CN[it] ?: it } +
                    if (filter.types.size == 2) "（完全一致）" else "")
            }
            if (filter.moves.isNotEmpty()) {
                val names = filter.moves.map { id ->
                    moveOptions.firstOrNull { it.showdownId == id }?.label ?: id
                }
                add("招式 " + names.joinToString(if (filter.moveMode == MatchMode.AND) " 且 " else " 或 "))
            }
            if (filter.abilities.isNotEmpty()) {
                val names = filter.abilities.map { id ->
                    abilityOptions.firstOrNull { it.showdownId == id }?.label ?: id
                }
                add("特性 " + names.joinToString(" 且 "))
            }
        }.joinToString(" · ")
    }

    if (filterOpen) {
        DexFilterPage(
            filter = filter,
            moveOptions = moveOptions,
            abilityOptions = abilityOptions,
            matchCount = rows.size,
            onFilterChange = { filter = it },
            onDismiss = { filterOpen = false },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("宝可梦图鉴", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "收录 ${entries.size} 种宝可梦（${forms.size} 个形态），点击查看属性、种族值与可学招式。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("搜索名字（中文 / 英文）") },
                )
                OutlinedButton(
                    onClick = { filterOpen = true },
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(if (filter.activeCount == 0) "筛选" else "筛选 ${filter.activeCount}")
                }
            }
            if (!filter.isEmpty) {
                Text(
                    filterSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF2F6F4E),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider()
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(rows, key = { it.first.base.species.showdownId + "|" + it.second.species.showdownId }) { (_, form, _) ->
                PokedexRow(form) { selectedShowdownId = form.species.showdownId }
            }
            item {
                Text(
                    if (rows.isEmpty()) "没有匹配的宝可梦。" else "共 ${rows.size} 条",
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PokedexRow(form: SpeciesFormOption, onClick: () -> Unit) {
    val total = form.baseStats.asMap().values.sumOf { it.toIntOrNull() ?: 0 }
    val context = LocalContext.current
    val dexLabel = remember(form.species.showdownId) {
        SpriteAssetCatalog.dexLabel(context, form.species.showdownId)
    }
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PokemonAvatar(
                showdownId = form.species.showdownId,
                displayName = form.species.displayName,
                size = 74.dp,
                fallbackColor = TYPE_COLORS[form.types.firstOrNull()] ?: Color(0xFF9FA19F),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (dexLabel != null) DexNumberBadge(dexLabel)
                Text(
                    form.species.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    form.types.forEach { type ->
                        TypeBadge(type, modifier = Modifier.width(64.dp).height(26.dp))
                    }
                }
                Text(
                    "种族值 $total · ${form.abilities.take(2).joinToString(" / ") { it.displayName }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DexNumberBadge(label: String) {
    Box(
        Modifier.background(Color(0xFFEDEFF3), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFF5A5F66))
    }
}

@Composable
private fun PokemonDetailScreen(
    initialForm: SpeciesFormOption,
    family: List<SpeciesFormOption>,
    repository: OpponentPresetRepository,
    codex: CodexRepository,
    onBack: () -> Unit,
) {
    var shapeId by rememberSaveable(initialForm.species.showdownId) { mutableStateOf(initialForm.species.showdownId) }
    val form = family.firstOrNull { it.species.showdownId == shapeId } ?: initialForm
    val shapeOptions = remember(family) {
        family.sortedWith(compareBy<SpeciesFormOption> { isMegaSpeciesForm(it.species.showdownId) })
    }
    var moveFilter by rememberSaveable { mutableStateOf("全部") }
    var detailMove by remember { mutableStateOf<MoveValue?>(null) }
    var detailAbility by remember { mutableStateOf<Pair<EntityValue, AbilityCodex?>?>(null) }
    val moves = remember(form) {
        form.learnableMoves.sortedWith(
            compareBy<MoveValue> { categoryOrder(repository.moveCategoryFor(it)) }
                .thenByDescending { it.basePower ?: 0 }
                .thenBy { it.entity.displayName },
        )
    }
    val filteredMoves = remember(moves, moveFilter) {
        if (moveFilter == "全部") moves else moves.filter {
            categoryLabel(repository.moveCategoryFor(it)) == moveFilter
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TextButton(onClick = onBack) { Text("‹ 返回图鉴") }
            val context = LocalContext.current
            val dexLabel = remember(form.species.showdownId) {
                SpriteAssetCatalog.dexLabel(context, form.species.showdownId)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                PokemonAvatar(
                    showdownId = form.species.showdownId,
                    displayName = form.species.displayName,
                    size = 96.dp,
                    fallbackColor = TYPE_COLORS[form.types.firstOrNull()] ?: Color(0xFF9FA19F),
                )
                Spacer(Modifier.width(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (dexLabel != null) DexNumberBadge(dexLabel)
                    Text(
                        form.species.displayName,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        form.species.showdownId,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        form.types.forEach { type ->
                            TypeBadge(type, modifier = Modifier.width(60.dp).height(26.dp))
                        }
                    }
                }
            }
        }
        if (shapeOptions.size > 1) {
            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "形态（${shapeOptions.size}）",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            shapeOptions.forEach { option ->
                                FilterChip(
                                    selected = option.species.showdownId == form.species.showdownId,
                                    onClick = { shapeId = option.species.showdownId },
                                    label = { Text(option.species.displayName) },
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
        ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("种族值", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    STAT_LABELS.forEach { (key, label) ->
                        val value = form.baseStats.asMap()[key].orEmpty().ifBlank { "—" }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.width(48.dp), style = MaterialTheme.typography.bodyMedium)
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(8.dp)
                                    .background(Color(0xFFE8EAEF), RoundedCornerShape(4.dp)),
                            ) {
                                val bar = ((value.toIntOrNull() ?: 0) / 160f).coerceIn(0f, 1f)
                                Box(
                                    Modifier
                                        .fillMaxWidth(bar)
                                        .height(8.dp)
                                        .background(statColor(value.toIntOrNull() ?: 0), RoundedCornerShape(4.dp)),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(value, Modifier.width(34.dp), fontWeight = FontWeight.Bold)
                        }
                    }
                    val total = form.baseStats.asMap().values.sumOf { it.toIntOrNull() ?: 0 }
                    HorizontalDivider()
                    Text("合计 $total", fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
        ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("特性", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    form.abilities.forEach { ability ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { detailAbility = ability to codex.abilityFor(ability.showdownId) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "· ${ability.displayName}",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text("›", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF9AA0A6))
                        }
                    }
                    if (form.abilities.isEmpty()) Text("无可用特性")
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部", "物理", "特殊", "变化").forEach { label ->
                    FilterChip(
                        selected = moveFilter == label,
                        onClick = { moveFilter = label },
                        label = { Text(label) },
                    )
                }
            }
        }
        items(filteredMoves, key = { it.entity.showdownId }) { move ->
            MoveRow(move, repository, codex) { detailMove = move }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    detailMove?.let { move ->
        MoveDetailDialog(
            move = move,
            type = repository.moveTypeFor(move),
            category = repository.moveCategoryFor(move),
            codex = codex.moveFor(move.entity.showdownId),
            onDismiss = { detailMove = null },
        )
    }
    detailAbility?.let { (ability, entry) ->
        AbilityDetailDialog(ability = ability, codex = entry, onDismiss = { detailAbility = null })
    }
}

@Composable
private fun MoveRow(
    move: MoveValue,
    repository: OpponentPresetRepository,
    codex: CodexRepository,
    onClick: () -> Unit,
) {
    val category = repository.moveCategoryFor(move)
    Card(
            Modifier.fillMaxWidth().clickable(onClick = onClick),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
        ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TypeBadge(repository.moveTypeFor(move) ?: "Normal", modifier = Modifier.width(52.dp).height(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(move.entity.displayName, Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Text(categoryLabel(category), style = MaterialTheme.typography.bodySmall)
            if (category != "Status") {
                Spacer(Modifier.width(10.dp))
                Text(
                    "威力 ${move.basePower ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(6.dp))
            Text("›", style = MaterialTheme.typography.bodySmall, color = Color(0xFF9AA0A6))
        }
    }
}

@Composable
private fun MoveDetailDialog(
    move: MoveValue,
    type: String?,
    category: String,
    codex: MoveCodex?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text(move.entity.displayName, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TypeBadge(type ?: codex?.type ?: "Normal")
                    Text(categoryLabel(category), style = MaterialTheme.typography.bodyMedium)
                }
                val power = move.basePower?.takeIf { it > 0 }
                DetailLine("威力", if (power != null) "$power" else "—")
                DetailLine("命中", codex?.accuracy?.let { if (it > 0) "$it" else "—" } ?: "—")
                DetailLine("PP", codex?.pp?.let { if (it > 0) "$it" else "—" } ?: "—")
                if (!codex?.target.isNullOrBlank()) DetailLine("对象", codex!!.target)
                if (!codex?.flags.isNullOrEmpty()) DetailLine("性质", codex!!.flags.joinToString("、"))
                HorizontalDivider()
                Text(
                    codex?.description?.takeIf { it.isNotBlank() } ?: "冠军资料站暂未收录该招式说明。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
    )
}

@Composable
private fun AbilityDetailDialog(
    ability: EntityValue,
    codex: AbilityCodex?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text(ability.displayName, fontWeight = FontWeight.Bold) },
        text = {
            Text(
                codex?.description?.takeIf { it.isNotBlank() } ?: "冠军资料站暂未收录该特性说明。",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            Modifier.width(52.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun TypeBadge(
    type: String,
    compact: Boolean = false,
    wide: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .then(if (wide) Modifier.height(30.dp) else Modifier)
            .background(TYPE_COLORS[type] ?: Color(0xFF555555), RoundedCornerShape(if (wide) 8.dp else 4.dp))
            .padding(horizontal = if (compact) 6.dp else 10.dp, vertical = if (compact) 2.dp else 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            TYPE_NAMES_CN[type] ?: type,
            color = Color.White,
            style = if (wide) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelSmall,
            fontWeight = if (wide) FontWeight.Bold else null,
        )
    }
}

private fun categoryLabel(category: String): String = when (category) {
    "Physical" -> "物理"
    "Special" -> "特殊"
    else -> "变化"
}

private fun categoryOrder(category: String): Int = when (category) {
    "Physical" -> 0
    "Special" -> 1
    else -> 2
}

private fun statColor(value: Int): Color = when {
    value >= 130 -> Color(0xFF4CAF50)
    value >= 100 -> Color(0xFF8BC34A)
    value >= 70 -> Color(0xFFFFC107)
    value >= 45 -> Color(0xFFFF9800)
    else -> Color(0xFFF44336)
}
