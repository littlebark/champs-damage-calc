package com.crazylei12.pokemonchampionsassistant

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val TYPE_COLORS = mapOf(
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

private val STAT_LABELS = listOf(
    "hp" to "HP",
    "atk" to "攻击",
    "def" to "防御",
    "spa" to "特攻",
    "spd" to "特防",
    "spe" to "速度",
)

@Composable
internal fun PokedexScreen(repository: OpponentPresetRepository) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedShowdownId by rememberSaveable { mutableStateOf("") }
    val forms = remember(repository) { repository.allForms }
    val selected = forms.firstOrNull { it.species.showdownId == selectedShowdownId }

    if (selected != null) {
        PokemonDetailScreen(
            form = selected,
            repository = repository,
            onBack = { selectedShowdownId = "" },
        )
        return
    }

    val filtered = remember(forms, query) {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) forms else forms.filter { form ->
            form.species.displayName.lowercase().contains(normalized) ||
                form.species.showdownId.lowercase().contains(normalized) ||
                form.species.canonicalId.lowercase().contains(normalized)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("宝可梦图鉴", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "收录 ${forms.size} 个 Championship 可用形态，点击查看属性、种族值与可学招式。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("搜索名字（中文 / 英文）") },
            )
        }
        HorizontalDivider()
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(filtered, key = { it.species.showdownId }) { form ->
                PokedexRow(form) { selectedShowdownId = form.species.showdownId }
            }
            item {
                Text(
                    if (filtered.isEmpty()) "没有匹配的宝可梦。" else "共 ${filtered.size} 个形态",
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
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(form.species.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    form.types.forEach { type -> TypeBadge(type) }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("种族值 $total", style = MaterialTheme.typography.bodySmall)
                Text(
                    form.abilities.take(2).joinToString(" / ") { it.displayName },
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
private fun PokemonDetailScreen(
    form: SpeciesFormOption,
    repository: OpponentPresetRepository,
    onBack: () -> Unit,
) {
    var moveFilter by rememberSaveable { mutableStateOf("全部") }
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
            Text(form.species.displayName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                form.species.showdownId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                form.types.forEach { type -> TypeBadge(type) }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
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
                                    .background(Color(0xFF263145), RoundedCornerShape(4.dp)),
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
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("特性", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    form.abilities.forEach { ability ->
                        Text(
                            "· ${ability.displayName}" +
                                if (ability.showdownId == form.defaultAbility?.showdownId) "（默认）" else "",
                            style = MaterialTheme.typography.bodyMedium,
                        )
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
            MoveRow(move, repository)
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun MoveRow(move: MoveValue, repository: OpponentPresetRepository) {
    val category = repository.moveCategoryFor(move)
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TypeBadge(repository.moveTypeFor(move) ?: "Normal", compact = true)
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
        }
    }
}

@Composable
private fun TypeBadge(type: String, compact: Boolean = false) {
    Box(
        Modifier
            .background(TYPE_COLORS[type] ?: Color(0xFF555555), RoundedCornerShape(4.dp))
            .padding(horizontal = if (compact) 6.dp else 8.dp, vertical = if (compact) 2.dp else 3.dp),
    ) {
        Text(type, color = Color.White, style = MaterialTheme.typography.labelSmall)
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
