package com.bark.championsassistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var engineRuntime: DamageEngineRuntime
    private var pendingAssistantMode = BattleAssistantMode.STANDARD
    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            OverlayCaptureService.start(this, result.resultCode, data, pendingAssistantMode)
        } else {
            CaptureUiState.message.value = "已取消屏幕共享"
        }
    }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LegacyRecognitionStorageMigration.runInBackground(this)
        engineRuntime = DamageEngineRuntime(this)
        setContent { ChampionsDamageApp(engineRuntime, this) }
    }

    override fun onDestroy() {
        engineRuntime.destroy()
        super.onDestroy()
    }

    fun requestOverlayPermission() {
        // MIUI / HyperOS 会忽略 ACTION_MANAGE_OVERLAY_PERMISSION 里的 package 参数，
        // 直接把「显示在其他应用的上层」的全量列表铺出来，只能靠用户自己滚动查找。
        // 先试 MIUI 自带的应用权限编辑页（直接落在本应用），失败再退回标准入口。
        val miuiEditor = Intent("miui.intent.action.APP_PERM_EDITOR")
            .putExtra("extra_pkgname", packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val opened = runCatching { startActivity(miuiEditor) }.isSuccess
        if (opened) return
        val standard = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(standard) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
        }
    }

    fun startOwnTeamCapture(mode: BattleAssistantMode = BattleAssistantMode.STANDARD) {
        pendingAssistantMode = mode
        if (!Settings.canDrawOverlays(this)) {
            CaptureUiState.message.value = "请先授予悬浮窗权限"
            requestOverlayPermission()
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
        } else {
            manager.createScreenCaptureIntent()
        }
        projectionLauncher.launch(captureIntent)
    }

}

private val AppColors = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF001936),
    secondary = Color(0xFF00897B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD5EFEA),
    onSecondaryContainer = Color(0xFF00201B),
    tertiary = Color(0xFFE8710A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFE0C7),
    onTertiaryContainer = Color(0xFF331300),
    background = Color(0xFFF2F3F7),
    onBackground = Color(0xFF1B1C1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1C1F),
    surfaceVariant = Color(0xFFF4F5F8),
    onSurfaceVariant = Color(0xFF44474D),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFAFBFC),
    surfaceContainerHigh = Color(0xFFF3F4F7),
    surfaceContainerHighest = Color(0xFFEDEFF3),
    outline = Color(0xFFC4C7CC),
    outlineVariant = Color(0xFFDDE0E5),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
)

private enum class AppTab(val label: String, val glyph: String) {
    CALC("计算器", "⚔"),
    DEX("图鉴", "◈"),
    BATTLE("对局", "◉"),
}

private enum class AppPanel { ABOUT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChampionsDamageApp(runtime: DamageEngineRuntime, activity: MainActivity) {
    var selectedTabName by rememberSaveable { mutableStateOf(AppTab.CALC.name) }
    var activePanelName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedTab = AppTab.valueOf(selectedTabName)
    val activePanel = activePanelName?.let { AppPanel.valueOf(it) }
    val context = LocalContext.current
    val presetRepository = remember(context) { OpponentPresetRepository(context) }
    val teamLibraryRevision by CaptureUiState.teamLibraryRevision
    val teamsResult = remember(teamLibraryRevision) { runCatching { TeamRepository.load(context) } }

    MaterialTheme(colorScheme = AppColors) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            AndroidView(
                factory = { runtime.webView },
                modifier = Modifier.size(1.dp).alpha(0f),
            )
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    if (activePanel == null) {
                        var menuOpen by remember { mutableStateOf(false) }
                        TopAppBar(
                            title = { Text(selectedTab.label, fontWeight = FontWeight.Bold) },
                            actions = {
                                Box {
                                    IconButton(onClick = { menuOpen = true }) { Text("⋮") }
                                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                        DropdownMenuItem(
                                            text = { Text("关于与更新") },
                                            onClick = {
                                                menuOpen = false
                                                activePanelName = AppPanel.ABOUT.name
                                            },
                                        )
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                        )
                    } else {
                        TopAppBar(
                            title = {
                                Text("关于与更新", fontWeight = FontWeight.Bold)
                            },
                            navigationIcon = {
                                IconButton(onClick = { activePanelName = null }) { Text("←") }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                        )
                    }
                },
                bottomBar = {
                    if (activePanel == null) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            AppTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = tab == selectedTab,
                                    onClick = { selectedTabName = tab.name },
                                    icon = { Text(tab.glyph) },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Surface(
                    Modifier.fillMaxSize().padding(padding),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    teamsResult.fold(
                        onSuccess = { teams ->
                            when (activePanel) {
                                AppPanel.ABOUT -> SettingsScreen(runtime)
                                null -> when (selectedTab) {
                                    AppTab.CALC -> ManualCalculatorScreen(runtime, presetRepository)
                                    AppTab.DEX -> PokedexScreen(repository = presetRepository)
                                    AppTab.BATTLE -> OwnTeamCaptureScreen(activity, teams, presetRepository)
                                }
                            }
                        },
                        onFailure = { error -> PlaceholderScreen("队伍资源加载失败", error.message.orEmpty()) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OwnTeamCaptureScreen(
    activity: MainActivity,
    teams: List<SavedTeam>,
    presetRepository: OpponentPresetRepository,
) {
    val message by CaptureUiState.message
    val running by CaptureUiState.running
    val draftRevision by CaptureUiState.ownTeamDraftRevision
    val hasCorrectionDraft = remember(draftRevision) {
        OwnTeamImportRepository(activity).hasCorrectionDraft()
    }
    val overlayAllowed = Settings.canDrawOverlays(activity)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("伤害助手", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        val myPokemon = remember(teams) {
            teams.firstOrNull { it.id == TeamRepository.MY_POKEMON_TEAM_ID }?.pokemon.orEmpty()
        }
        var detail by remember { mutableStateOf<PokemonConfig?>(null) }
        SectionCard("启动伤害助手") {
            Text("只需要两项系统授权：悬浮窗权限（长期有效）和“共享屏幕”（每次启动时由系统确认一次）。")
            Text(if (overlayAllowed) "悬浮窗权限：已授予" else "悬浮窗权限：尚未授予")
            if (!overlayAllowed) {
                Button(onClick = activity::requestOverlayPermission) { Text("授予悬浮窗权限") }
                Text(
                    "系统列表按拼音排序，本应用叫「宝冠军伤害助手」，排在 B 区；找不到就往下滚。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = { activity.startOwnTeamCapture() }, enabled = !running) {
                Text(if (running) "伤害助手运行中" else "启动伤害助手")
            }
            OutlinedButton(onClick = { OverlayCaptureService.stop(activity) }, enabled = running) {
                Text("结束伤害助手")
            }
            Text("启动后常驻十字菜单：上=计算伤害／下=关闭伤害／左=识别队伍／右=识别当前；中心球可拖动。")
        }
        SectionCard("我的宝可梦（${myPokemon.size} 只）") {
            if (myPokemon.isEmpty()) {
                Text("还没有记录：启动后停在队伍信息页用「识别队伍」，或在状态／能力页用「识别当前」。")
            } else {
                myPokemon.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { mon ->
                            Card(
                                Modifier.weight(1f).clickable { detail = mon },
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF2F4F8)),
                            ) {
                                Column(
                                    Modifier.padding(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        mon.species.displayName,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                    )
                                    Text(
                                        "HP ${mon.actualStats.hp}｜攻 ${mon.actualStats.atk}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        detail?.let { mon ->
            AlertDialog(
                onDismissRequest = { detail = null },
                confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } },
                title = { Text(mon.species.displayName) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        MyPokemonStatRow("HP", mon.actualStats.hp, mon.statPoints.hp)
                        MyPokemonStatRow("攻击", mon.actualStats.atk, mon.statPoints.atk)
                        MyPokemonStatRow("防御", mon.actualStats.def, mon.statPoints.def)
                        MyPokemonStatRow("特攻", mon.actualStats.spa, mon.statPoints.spa)
                        MyPokemonStatRow("特防", mon.actualStats.spd, mon.statPoints.spd)
                        MyPokemonStatRow("速度", mon.actualStats.spe, mon.statPoints.spe)
                        Text("特性：" + (mon.ability?.displayName ?: "未记录"))
                        Text("道具：" + (mon.item?.displayName ?: "未记录"))
                        Text("招式：" + mon.moves.joinToString("／") { it.entity.displayName })
                    }
                },
            )
        }
    }
}

@Composable
private fun MyPokemonStatRow(label: String, value: String, points: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f))
        Text(value.ifBlank { "—" }, fontWeight = FontWeight.Bold)
        Text("+${points.ifBlank { "0" }}", style = MaterialTheme.typography.bodySmall)
    }
}

private data class CalculationUiResult(
    val direction: String,
    val attacker: String,
    val defender: String,
    val moves: List<MoveResultUi>,
    val warnings: List<String>,
)

private data class TierCalculationUiResult(
    val attacker: String,
    val defender: String,
    val moves: List<TierMoveResultUi>,
    val warnings: List<String>,
)

private data class TierMoveResultUi(
    val name: String,
    val category: String,
    val tiers: List<TierRangeUi>,
)

private data class TierRangeUi(
    val minPercent: Double,
    val maxPercent: Double,
    val minDamage: Int,
    val maxDamage: Int,
)

private data class MoveResultUi(
    val name: String,
    val minPercent: Double,
    val maxPercent: Double,
    val minDamage: Int,
    val maxDamage: Int,
    val koText: String,
    val assumptions: List<String>,
)

private data class CalculatorPokemonState(
    val config: PokemonConfig,
    val sourceLabel: String,
    val profileId: String? = null,
)

private data class SavedPokemonOption(
    val teamName: String,
    val slot: Int,
    val config: PokemonConfig,
)

@Composable
private fun ManualCalculatorScreen(
    runtime: DamageEngineRuntime,
    repository: OpponentPresetRepository,
) {
    val catalog = repository.speciesCatalog

    fun defaultState(species: EntityValue, label: String = "图鉴默认（0 加点）"): CalculatorPokemonState {
        val profile = repository.profilesFor(species).firstOrNull { it.profileId == "generated.default" }
            ?: repository.recommendedProfile(species)
        return CalculatorPokemonState(repository.configFor(species, profile), label, profile.profileId)
    }

    fun pickSpecies(id: String): EntityValue = catalog.firstOrNull { it.showdownId == id } ?: catalog.first()

    var ownSelection by remember {
        mutableStateOf(defaultState(pickSpecies("Gardevoir")))
    }
    var opponentSelection by remember { mutableStateOf(defaultState(pickSpecies("Garchomp"))) }
    var selectedMove by rememberSaveable { mutableStateOf("") }
    var battleType by rememberSaveable { mutableStateOf("SINGLE") }
    var weather by rememberSaveable { mutableStateOf("") }
    var terrain by rememberSaveable { mutableStateOf("") }
    var opponentReflect by rememberSaveable { mutableStateOf(false) }
    var opponentLightScreen by rememberSaveable { mutableStateOf(false) }
    var attackerPoints by rememberSaveable { mutableStateOf(0) }
    var attackerStage by rememberSaveable { mutableStateOf(0) }
    var attackerNature by rememberSaveable { mutableStateOf(1.0) }
    var defenderHpPoints by rememberSaveable { mutableStateOf(0) }
    var defenderPoints by rememberSaveable { mutableStateOf(0) }
    var defenderStage by rememberSaveable { mutableStateOf(0) }
    var defenderNature by rememberSaveable { mutableStateOf(1.0) }
    var speedPoints by rememberSaveable { mutableStateOf(0) }
    var calculating by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<CalculationUiResult?>(null) }
    var errorMessage by remember { mutableStateOf("") }
    var pickerRequest by remember { mutableStateOf<PickerRequest?>(null) }

    val learnableMoves = remember(ownSelection.config.species.showdownId) {
        sortMovesForPicker(
            repository.learnableMovesFor(ownSelection.config.species),
            repository.formFor(ownSelection.config.species)?.types.orEmpty(),
            repository::moveTypeFor,
        )
    }
    LaunchedEffect(learnableMoves.map { it.entity.showdownId }) {
        if (learnableMoves.none { it.entity.showdownId == selectedMove }) {
            val damaging = learnableMoves.filter { (it.basePower ?: 0) > 0 }
            selectedMove = damaging.firstOrNull { normalizeShowdownId(it.entity.showdownId) !in DELAYED_DAMAGE_MOVES }?.entity?.showdownId
                ?: damaging.firstOrNull()?.entity?.showdownId
                ?: learnableMoves.firstOrNull()?.entity?.showdownId.orEmpty()
        }
    }

    val move = learnableMoves.firstOrNull { it.entity.showdownId == selectedMove }
    val isSpecial = move?.category == "Special"
    val offenseStat = if (isSpecial) "spa" else "atk"
    val defenseStat = if (isSpecial) "spd" else "def"
    val needsSpeed = move?.entity?.showdownId == "ElectroBall"
    val needsGender = ownSelection.config.ability?.showdownId == "Rivalry"

    fun ownConfig(): PokemonConfig {
        val points = if (isSpecial) StatFields(spa = attackerPoints.toString()) else StatFields(atk = attackerPoints.toString())
        val stats = repository.statsWithNatureMultiplier(
            ownSelection.config.species,
            points,
            offenseStat,
            attackerNature,
        )
        // 手动计算器里用户选中的那一招就是本次要算的招式，
        // 必须写进攻击方 build 的 moves，否则引擎匹配不到 moveId，会判成 NO_ATTACKER_MOVES。
        val moves = move?.let { listOf(it) } ?: ownSelection.config.moves
        return ownSelection.config.copy(statPoints = points, actualStats = stats, statAlignment = null, moves = moves)
    }

    fun opponentConfig(): PokemonConfig {
        val points = if (isSpecial) {
            StatFields(hp = defenderHpPoints.toString(), spd = defenderPoints.toString())
        } else {
            StatFields(hp = defenderHpPoints.toString(), def = defenderPoints.toString())
        }
        val stats = repository.statsWithNatureMultiplier(
            opponentSelection.config.species,
            points,
            defenseStat,
            defenderNature,
        )
        return opponentSelection.config.copy(statPoints = points, actualStats = stats, statAlignment = null)
    }

    fun recalculate() {
        if (selectedMove.isBlank() || !runtime.status.contains("已就绪")) return
        calculating = true
        errorMessage = ""
        val request = buildDamageRequest(
            direction = "OWN_TO_OPPONENT",
            own = PokemonEditorState.from(ownConfig()),
            opponent = PokemonEditorState.from(opponentConfig()),
            selectedMove = selectedMove,
            battleType = battleType,
            weather = weather,
            terrain = terrain,
            ownReflect = false,
            ownLightScreen = false,
            opponentReflect = opponentReflect,
            opponentLightScreen = opponentLightScreen,
            critical = false,
            spread = false,
            tierProfiles = false,
            attackerStages = mapOf(offenseStat to attackerStage),
            defenderStages = mapOf(defenseStat to defenderStage),
        )
        runtime.calculate(request) { calculation ->
            calculating = false
            calculation.fold(
                onSuccess = { raw ->
                    runCatching { result = parseCalculation(raw) }
                        .onFailure { errorMessage = localizeEngineError(it.message) }
                },
                onFailure = { errorMessage = localizeEngineError(it.message) },
            )
        }
    }

    val signature = buildString {
        append(ownSelection.config.toString()).append(opponentSelection.config.toString())
        append('|').append(selectedMove).append(battleType)
        append('|').append(weather).append(terrain).append(opponentReflect).append(opponentLightScreen)
        append('|').append(attackerPoints).append(attackerStage).append(attackerNature)
        append('|').append(defenderHpPoints).append(defenderPoints).append(defenderStage).append(defenderNature)
        append('|').append(speedPoints)
    }
    LaunchedEffect(signature) {
        kotlinx.coroutines.delay(300)
        recalculate()
    }

    val currentPicker = pickerRequest
    if (currentPicker != null) {
        val entries: List<PickerEntry> = when (currentPicker.kind) {
            PickerKind.SPECIES -> repository.speciesCatalog.map { it.asPickerEntry() }
            PickerKind.ITEM -> repository.itemCatalog.map { it.asPickerEntry() }
            PickerKind.MOVE -> learnableMoves.map { move ->
                PickerEntry(
                    id = move.entity.showdownId,
                    label = "[${repository.moveTypeLabel(move)}] ${move.entity.displayName}",
                    searchKeys = listOf(move.entity.displayName, move.entity.showdownId, move.entity.canonicalId),
                )
            }
        }
        SelectionPage(
            title = when (currentPicker.kind) {
                PickerKind.SPECIES -> if (currentPicker.attacker) "选择攻击方宝可梦" else "选择防御方宝可梦"
                PickerKind.ITEM -> "选择持有物"
                PickerKind.MOVE -> "选择招式"
            },
            hint = "输入中文名或英文名，边打边筛",
            entries = entries,
            onDismiss = { pickerRequest = null },
            onSelect = { id ->
                when (currentPicker.kind) {
                    PickerKind.SPECIES -> catalog.firstOrNull { it.showdownId == id }?.let { species ->
                        val next = defaultState(species)
                        if (currentPicker.attacker) ownSelection = next else opponentSelection = next
                    }
                    PickerKind.ITEM -> repository.itemCatalog.firstOrNull { it.showdownId == id }?.let { item ->
                        val base = if (currentPicker.attacker) ownSelection else opponentSelection
                        val next = base.copy(
                            config = base.config.copy(item = item),
                            sourceLabel = "手动配置",
                            profileId = null,
                        )
                        if (currentPicker.attacker) ownSelection = next else opponentSelection = next
                    }
                    PickerKind.MOVE -> selectedMove = id
                }
                pickerRequest = null
            },
        )
        return
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = battleType == "SINGLE", onClick = { battleType = "SINGLE" }, label = { Text("单打") })
            FilterChip(selected = battleType == "DOUBLE", onClick = { battleType = "DOUBLE" }, label = { Text("双打") })
            TextButton(onClick = {
                val previousOwn = ownSelection
                ownSelection = opponentSelection
                opponentSelection = previousOwn
                val previousPoints = attackerPoints
                attackerPoints = defenderPoints
                defenderPoints = previousPoints
                selectedMove = ""
            }) { Text("攻守互换") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CompactPicker(
                label = "天气",
                options = listOf("" to "无", "Sun" to "晴天", "Rain" to "雨天", "Sand" to "沙暴", "Snow" to "雪天"),
                selected = weather,
                modifier = Modifier.weight(1f),
                onSelect = { weather = it },
            )
            CompactPicker(
                label = "场地",
                options = listOf(
                    "" to "无",
                    "Electric" to "电气",
                    "Grassy" to "青草",
                    "Psychic" to "精神",
                    "Misty" to "薄雾",
                ),
                selected = terrain,
                modifier = Modifier.weight(1f),
                onSelect = { terrain = it },
            )
        }

        CalculatorSideCard(
            title = "攻击方",
            state = ownSelection,
            repository = repository,
            isAttacker = true,
            learnableMoves = learnableMoves,
            selectedMove = selectedMove,
            onSelectMove = { selectedMove = it },
            statLabel = if (isSpecial) "特攻" else "攻击",
            primaryPoints = attackerPoints,
            onPrimaryPoints = { attackerPoints = it },
            stage = attackerStage,
            onStageChange = { attackerStage = it },
            nature = attackerNature,
            onNatureChange = { attackerNature = it },
            showHpPoints = false,
            hpPoints = 0,
            onHpPoints = {},
            showSpeedPoints = needsSpeed,
            speedPoints = speedPoints,
            onSpeedPoints = { speedPoints = it },
            showGender = needsGender,
            showScreens = false,
            reflect = false,
            onReflect = {},
            lightScreen = false,
            onLightScreen = {},
            onStateChange = { ownSelection = it },
            onRequestPicker = { pickerRequest = PickerRequest(it, true) },
        )

        CalculatorSideCard(
            title = "防御方",
            state = opponentSelection,
            repository = repository,
            isAttacker = false,
            learnableMoves = emptyList(),
            selectedMove = "",
            onSelectMove = {},
            statLabel = if (isSpecial) "特防" else "防御",
            primaryPoints = defenderPoints,
            onPrimaryPoints = { defenderPoints = it },
            stage = defenderStage,
            onStageChange = { defenderStage = it },
            nature = defenderNature,
            onNatureChange = { defenderNature = it },
            showHpPoints = true,
            hpPoints = defenderHpPoints,
            onHpPoints = { defenderHpPoints = it },
            showSpeedPoints = needsSpeed,
            speedPoints = speedPoints,
            onSpeedPoints = { speedPoints = it },
            showGender = false,
            showScreens = true,
            reflect = opponentReflect,
            onReflect = { opponentReflect = it },
            lightScreen = opponentLightScreen,
            onLightScreen = { opponentLightScreen = it },
            onStateChange = { opponentSelection = it },
            onRequestPicker = { pickerRequest = PickerRequest(it, false) },
        )

        if (errorMessage.isNotBlank()) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF5B2525))) {
                Text(errorMessage, Modifier.padding(14.dp))
            }
        }

        result?.let { CalculationResultCard(it) }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CalculatorSideCard(
    title: String,
    state: CalculatorPokemonState,
    repository: OpponentPresetRepository,
    isAttacker: Boolean,
    learnableMoves: List<MoveValue>,
    selectedMove: String,
    onSelectMove: (String) -> Unit,
    statLabel: String,
    primaryPoints: Int,
    onPrimaryPoints: (Int) -> Unit,
    stage: Int,
    onStageChange: (Int) -> Unit,
    nature: Double,
    onNatureChange: (Double) -> Unit,
    showHpPoints: Boolean,
    hpPoints: Int,
    onHpPoints: (Int) -> Unit,
    showSpeedPoints: Boolean,
    speedPoints: Int,
    onSpeedPoints: (Int) -> Unit,
    showGender: Boolean,
    showScreens: Boolean,
    reflect: Boolean,
    onReflect: (Boolean) -> Unit,
    lightScreen: Boolean,
    onLightScreen: (Boolean) -> Unit,
    onStateChange: (CalculatorPokemonState) -> Unit,
    onRequestPicker: (PickerKind) -> Unit,
) {
    var screensExpanded by remember { mutableStateOf(false) }
    val config = state.config
    val speciesAbilities = repository.abilitiesFor(config.species)
    val abilities = (listOfNotNull(config.ability) + speciesAbilities)
        .distinctBy { it.showdownId.lowercase() }
    val selectedMoveName = learnableMoves.firstOrNull { it.entity.showdownId == selectedMove }?.entity?.displayName
        ?: "未选择招式"

    fun updateConfig(next: PokemonConfig) {
        onStateChange(state.copy(config = next, sourceLabel = "手动配置", profileId = null))
    }

    // 特性不再有「无」这个选项：默认落到这只宝可梦的第一个特性。
    LaunchedEffect(config.species.showdownId, abilities.size) {
        if (config.ability == null && abilities.isNotEmpty()) {
            updateConfig(config.copy(ability = abilities.first()))
        }
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            OutlinedButton(onClick = { onRequestPicker(PickerKind.SPECIES) }, modifier = Modifier.fillMaxWidth()) {
                Text(config.species.displayName, modifier = Modifier.weight(1f))
            }

            if (isAttacker) {
                OutlinedButton(onClick = { onRequestPicker(PickerKind.MOVE) }, modifier = Modifier.fillMaxWidth()) {
                    Text("招式：$selectedMoveName", modifier = Modifier.weight(1f))
                }
            }

            // 左侧「性格 ×0.9 ×1.0 ×1.1」整体左对齐，右侧「能力等级 − 0 +」整体右对齐
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "性格",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                listOf(0.9 to "×0.9", 1.0 to "×1.0", 1.1 to "×1.1").forEach { (value, label) ->
                    CompactChoiceChip(
                        label = label,
                        selected = kotlin.math.abs(nature - value) < 0.001,
                        onClick = { onNatureChange(value) },
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "能力等级",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CompactStepper(stage, -6, 6, onStageChange)
            }

            if (showHpPoints) {
                StepperField("生命点数", hpPoints, 0, 32, Modifier.fillMaxWidth(), onHpPoints)
                StepperField("$statLabel 点数", primaryPoints, 0, 32, Modifier.fillMaxWidth(), onPrimaryPoints)
            } else {
                StepperField("$statLabel 点数", primaryPoints, 0, 32, Modifier.fillMaxWidth(), onPrimaryPoints)
            }
            if (showSpeedPoints) {
                StepperField("速度点数（电球用）", speedPoints, 0, 32, Modifier.fillMaxWidth(), onSpeedPoints)
            }

            // 特性与持有物并列一行：特性在左、持有物在右
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SimplePicker<EntityValue?>(
                    label = "特性",
                    options = abilities,
                    selected = config.ability,
                    display = { it?.displayName ?: "—" },
                    onSelect = { ability -> updateConfig(config.copy(ability = ability)) },
                    modifier = Modifier.weight(1f),
                )
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("持有物", style = MaterialTheme.typography.labelMedium)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { onRequestPicker(PickerKind.ITEM) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                config.item?.displayName ?: "无",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text("▼")
                        }
                        if (config.item != null) {
                            CompactTextButton("清除") { updateConfig(config.copy(item = null)) }
                        }
                    }
                }
            }

            if (showGender) {
                Text("性别（斗争心用）", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to "未知", "M" to "雄", "F" to "雌").forEach { (gender, label) ->
                        FilterChip(
                            selected = config.gender == gender,
                            onClick = { updateConfig(config.copy(gender = gender)) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            if (showScreens) {
                TextButton(onClick = { screensExpanded = !screensExpanded }) {
                    Text(if (screensExpanded) "对战条件（反射壁 / 光墙）▴" else "对战条件（反射壁 / 光墙）▾")
                }
                if (screensExpanded) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = reflect,
                            onClick = { onReflect(!reflect) },
                            label = { Text("反射壁（物理减半）") },
                        )
                        FilterChip(
                            selected = lightScreen,
                            onClick = { onLightScreen(!lightScreen) },
                            label = { Text("光墙（特殊减半）") },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepperField(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(value.toString()) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun commitDraft() {
        val parsed = draft.toIntOrNull() ?: value
        onChange(parsed.coerceIn(min, max))
        editing = false
        keyboard?.hide()
    }

    LaunchedEffect(editing) {
        if (editing) focusRequester.requestFocus()
    }

    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        // 两端提供「最小 / 最大」一键落位，中间数字可直接点进去输入
        CompactTextButton("最小", enabled = value > min) { onChange(min) }
        CompactRoundButton("−", enabled = value > min) { onChange((value - 1).coerceAtLeast(min)) }
        NumericValueBox(
            value = value,
            editing = editing,
            draft = draft,
            onDraftChange = { draft = it },
            onBeginEdit = {
                draft = value.toString()
                editing = true
            },
            onCommit = { if (editing) commitDraft() },
            focusRequester = focusRequester,
        )
        CompactRoundButton("+", enabled = value < max) { onChange((value + 1).coerceAtMost(max)) }
        CompactTextButton("最大", enabled = value < max) { onChange(max) }
    }
}

@Composable
private fun NumericValueBox(
    value: Int,
    editing: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onBeginEdit: () -> Unit,
    onCommit: () -> Unit,
    focusRequester: FocusRequester,
) {
    var hadFocus by remember { mutableStateOf(false) }
    Box(
        Modifier
            .width(52.dp)
            .height(32.dp)
            .background(
                if (editing) Color(0xFFFFFFFF) else Color(0xFFF1F2F6),
                RoundedCornerShape(50),
            )
            .then(
                if (editing) {
                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                } else {
                    Modifier
                },
            )
            .clickable(enabled = !editing, onClick = onBeginEdit),
        contentAlignment = Alignment.Center,
    ) {
        if (editing) {
            BasicTextField(
                value = draft,
                onValueChange = { input -> onDraftChange(input.filter { it.isDigit() }.take(3)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onCommit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            hadFocus = true
                        } else if (hadFocus) {
                            hadFocus = false
                            onCommit()
                        }
                    },
            )
        } else {
            Text(
                if (value > 0) "+$value" else value.toString(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun CompactStepper(
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        CompactRoundButton("−", enabled = value > min) { onChange((value - 1).coerceAtLeast(min)) }
        Text(
            if (value > 0) "+$value" else value.toString(),
            modifier = Modifier.widthIn(min = 30.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        CompactRoundButton("+", enabled = value < max) { onChange((value + 1).coerceAtMost(max)) }
    }
}

@Composable
private fun CompactRoundButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(32.dp)
            .background(
                if (enabled) Color(0xFFF1F2F6) else Color(0xFFF7F8FA),
                RoundedCornerShape(50),
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun CompactTextButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .background(
                if (enabled) Color(0xFFF1F2F6) else Color(0xFFF7F8FA),
                RoundedCornerShape(50),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun CompactChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color(0xFFF1F2F6),
                RoundedCornerShape(50),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PokemonConfigEditor(
    state: PokemonConfig,
    repository: OpponentPresetRepository,
    onChange: (PokemonConfig) -> Unit,
) {
    var levelText by remember(state.species.showdownId, state.level) { mutableStateOf(state.level.toString()) }
    var chooseSpecies by remember { mutableStateOf(false) }
    var chooseItem by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableStateOf<Int?>(null) }
    val abilities = (listOfNotNull(state.ability) + repository.abilitiesFor(state.species))
        .distinctBy { it.showdownId.lowercase() }
    val moveOptions = repository.configuredMoveOptionsFor(state.species, state.moves)

    if (chooseSpecies) {
        EntitySearchDialog(
            title = "选择宝可梦",
            entities = repository.speciesCatalog,
            onDismiss = { chooseSpecies = false },
            onSelect = { species ->
                onChange(repository.configFor(species))
                chooseSpecies = false
            },
        )
    }
    if (chooseItem) {
        EntitySearchDialog(
            title = "选择道具",
            entities = repository.itemCatalog,
            onDismiss = { chooseItem = false },
            onSelect = { item -> onChange(state.copy(item = item)); chooseItem = false },
        )
    }
    moveTarget?.let { target ->
        MoveSearchDialog(
            title = if (target < state.moves.size) "更换招式" else "添加招式",
            moves = moveOptions,
            repository = repository,
            onDismiss = { moveTarget = null },
            onSelect = { move ->
                val updated = state.moves.toMutableList()
                if (target < updated.size) updated[target] = move else updated.add(move)
                onChange(state.copy(moves = updated.distinctBy { it.entity.showdownId }.take(4)))
                moveTarget = null
            },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { chooseSpecies = true }, modifier = Modifier.fillMaxWidth()) {
            Text("宝可梦：${state.species.displayName}", modifier = Modifier.weight(1f))
            Text("选择")
        }
        OutlinedTextField(
            value = levelText,
            onValueChange = { value ->
                levelText = value.filter(Char::isDigit).take(3)
                levelText.toIntOrNull()?.let { onChange(state.copy(level = it.coerceIn(1, 100))) }
            },
            label = { Text("等级") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = dismissingKeyboardActions(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SimplePicker<EntityValue?>(
            label = "性格数值修正",
            options = listOf(null) + repository.natures.map(NatureOption::entity),
            selected = state.statAlignment,
            display = { it?.displayName ?: "中性或未指定" },
            onSelect = { onChange(state.copy(statAlignment = it)) },
        )
        SimplePicker<EntityValue?>(
            label = "特性",
            options = listOf(null) + abilities,
            selected = state.ability,
            display = { it?.displayName ?: "未设置" },
            onSelect = { onChange(state.copy(ability = it)) },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { chooseItem = true }, modifier = Modifier.weight(1f)) {
                Text("道具：${state.item?.displayName ?: "无"}")
            }
            if (state.item != null) TextButton(onClick = { onChange(state.copy(item = null)) }) { Text("清除") }
        }
        Text("招式", fontWeight = FontWeight.SemiBold)
        state.moves.forEachIndexed { index, move ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { moveTarget = index }, modifier = Modifier.weight(1f)) {
                    Text("${index + 1}. ${move.entity.displayName}")
                }
                TextButton(onClick = {
                    onChange(state.copy(moves = state.moves.toMutableList().apply { removeAt(index) }))
                }) { Text("移除") }
            }
        }
        if (state.moves.size < 4) {
            OutlinedButton(onClick = { moveTarget = state.moves.size }, modifier = Modifier.fillMaxWidth()) {
                Text("添加招式")
            }
        }
        state.moveSlotReminder()?.let { reminder ->
            Text(reminder, color = Color(0xFFB26A00), style = MaterialTheme.typography.bodySmall)
        }
        Text("实际能力值", fontWeight = FontWeight.SemiBold)
        StatEditor(state.actualStats) { onChange(state.copy(actualStats = it)) }
        Text("能力加点（可留空）", fontWeight = FontWeight.SemiBold)
        StatEditor(state.statPoints) { onChange(state.copy(statPoints = it)) }
    }
}

private enum class PickerKind { SPECIES, ITEM, MOVE }

private data class PickerRequest(val kind: PickerKind, val attacker: Boolean)

private data class PickerEntry(
    val id: String,
    val label: String,
    val searchKeys: List<String>,
) {
    fun matchesQuery(rawQuery: String): Boolean {
        val query = normalizeSearchText(rawQuery)
        if (query.isBlank()) return true
        return searchKeys.any { normalizeSearchText(it).contains(query) }
    }
}

private fun EntityValue.asPickerEntry(): PickerEntry = PickerEntry(
    id = showdownId,
    label = displayName,
    searchKeys = listOf(displayName, showdownId, canonicalId),
)

@Composable
private fun SelectionPage(
    title: String,
    hint: String,
    entries: List<PickerEntry>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(entries, query) { entries.filter { it.matchesQuery(query) } }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("取消") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(hint) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = dismissingKeyboardActions(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
            Text(
                if (query.isBlank()) "共 ${entries.size} 项" else "匹配 ${filtered.size} 项",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (filtered.isEmpty()) {
                Text("没有找到匹配项", color = Color(0xFFB26A00))
            }
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(filtered.take(300)) { _, entry ->
                    TextButton(onClick = { onSelect(entry.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            entry.label,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Start,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun MoveSearchDialog(
    title: String,
    moves: List<MoveValue>,
    repository: OpponentPresetRepository,
    onDismiss: () -> Unit,
    onSelect: (MoveValue) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(120)
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }
    val filtered = remember(moves, query) { moves.filter { it.matchesSearch(query) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索招式中文名") },
                    supportingText = { Text("也支持英文名称") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = dismissingKeyboardActions(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(filtered.take(100)) { _, move ->
                        TextButton(onClick = { onSelect(move) }, modifier = Modifier.fillMaxWidth()) {
                            Text("[${repository.moveTypeLabel(move)}] ${move.entity.displayName}", modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    Text("没有找到匹配项", color = Color(0xFFB26A00))
                } else if (filtered.size > 100) {
                    Text("结果较多，请继续输入关键词。", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun EntitySearchDialog(
    title: String,
    entities: List<EntityValue>,
    onDismiss: () -> Unit,
    onSelect: (EntityValue) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(120)
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }
    val filtered = remember(entities, query) { entities.filter { it.matchesSearch(query) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索中文名") },
                    supportingText = { Text("也支持英文名称") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = dismissingKeyboardActions(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(filtered.take(100)) { _, entity ->
                        TextButton(onClick = { onSelect(entity) }, modifier = Modifier.fillMaxWidth()) {
                            Text(entity.displayName, modifier = Modifier.weight(1f))
                        }
                    }
                }
                if (filtered.isEmpty()) {
                    Text("没有找到匹配项", color = Color(0xFFB26A00))
                } else if (filtered.size > 100) {
                    Text("结果较多，请继续输入关键词。", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun StatEditor(value: StatFields, onChange: (StatFields) -> Unit) {
    StatRow(
        listOf("生命" to value.hp, "攻击" to value.atk, "防御" to value.def),
    ) { index, text ->
        onChange(
            when (index) {
                0 -> value.copy(hp = text)
                1 -> value.copy(atk = text)
                else -> value.copy(def = text)
            }
        )
    }
    StatRow(
        listOf("特攻" to value.spa, "特防" to value.spd, "速度" to value.spe),
    ) { index, text ->
        onChange(
            when (index) {
                0 -> value.copy(spa = text)
                1 -> value.copy(spd = text)
                else -> value.copy(spe = text)
            }
        )
    }
}

@Composable
private fun StatRow(values: List<Pair<String, String>>, onChange: (Int, String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEachIndexed { index, (label, value) ->
            OutlinedTextField(
                value = value,
                onValueChange = { onChange(index, it.filter(Char::isDigit).take(3)) },
                label = { Text(label) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = dismissingKeyboardActions(),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun dismissingKeyboardActions(): KeyboardActions {
    val focusManager = LocalFocusManager.current
    return KeyboardActions(
        onDone = { focusManager.clearFocus() },
        onSearch = { focusManager.clearFocus() },
    )
}

@Composable
private fun CalculationResultCard(result: CalculationUiResult) {
    SectionCard(if (result.direction == "OWN_TO_OPPONENT") "结果：我方输出" else "结果：我方承伤") {
        Text("${result.attacker}  →  ${result.defender}", fontWeight = FontWeight.Bold)
        result.moves.forEachIndexed { index, move ->
            if (index > 0) HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(move.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "${formatPercent(move.minPercent)} – ${formatPercent(move.maxPercent)}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("${move.minDamage} – ${move.maxDamage} 点生命值")
                Text(move.koText)
                move.assumptions.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (result.moves.isEmpty()) Text("没有可计算的招式，请重新选择。", color = Color(0xFFB26A00))
        if (result.warnings.isNotEmpty()) {
            HorizontalDivider()
            Text("计算提示", fontWeight = FontWeight.Bold, color = Color(0xFFB26A00))
            result.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun parseCalculation(raw: String): CalculationUiResult {
    val envelope = JSONObject(raw)
    if (!envelope.optBoolean("ok")) {
        val error = envelope.optJSONObject("error")
        throw IllegalArgumentException(localizeEngineError(error?.optString("message")))
    }
    val result = envelope.getJSONObject("result")
    val moveResults = result.getJSONArray("moveResults")
    val warnings = result.getJSONArray("warnings")
    return CalculationUiResult(
        direction = result.getString("calculationDirection"),
        attacker = result.getJSONObject("attackerSummary").getString("speciesName"),
        defender = result.getJSONObject("defenderIdentity").getJSONObject("species")
            .optString("displayName", "未知目标"),
        moves = (0 until moveResults.length()).map { index ->
            val move = moveResults.getJSONObject(index)
            val range = move.getJSONObject("selectedProfileRange")
            val assumptions = move.getJSONArray("assumptions")
            MoveResultUi(
                name = move.getString("moveName"),
                minPercent = range.getDouble("minPercent"),
                maxPercent = range.getDouble("maxPercent"),
                minDamage = range.getInt("minDamage"),
                maxDamage = range.getInt("maxDamage"),
                koText = localizeKoText(move.getJSONObject("koSummary").optString("text", "")),
                assumptions = (0 until assumptions.length())
                    .map(assumptions::getString)
                    .map(::localizeAssumption),
            )
        },
        warnings = (0 until warnings.length()).map { index ->
            val warning = warnings.getJSONObject(index)
            localizeWarning(warning.getString("code"), warning.getString("message"))
        }.distinct(),
    )
}

@Composable
private fun CompactPicker(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: options.firstOrNull()?.second.orEmpty()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(selectedLabel, modifier = Modifier.weight(1f), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                Text("▾")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            onSelect(value)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> SimplePicker(
    label: String,
    options: List<T>,
    selected: T,
    display: (T) -> String = { it.toString() },
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(display(selected), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("▼")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(display(option)) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun StatusCard(message: String, ready: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (ready) Color(0xFFDCF2EC) else Color(0xFFFFF1D9),
        ),
    ) {
        Text(message, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
private fun SettingsScreen(@Suppress("UNUSED_PARAMETER") runtime: DamageEngineRuntime) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置与诊断", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        SectionCard("更新") {
            Text("更新功能暂未接入，此页先留空。", color = Color(0xFF6B6B6B))
        }
    }
}


@Composable
private fun PlaceholderScreen(title: String, message: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(message)
    }
}

/** 延迟生效的伤害招（本回合不结算），不设为默认选中招式（已归一化 id）。 */
private val DELAYED_DAMAGE_MOVES = setOf("futuresight", "doomdesire")

/**
 * 选招式列表的排序规则：
 * 1) 本系招式（属性与宝可梦自身属性相同）排在最前，并按其属性在宝可梦属性中的顺序排列；
 * 2) 其余招式按属性分组，属性顺序按类型名固定排序，保证同属性相邻；
 * 3) 同一属性内按招式威力从高到低，威力相同再按名称。
 */
private fun sortMovesForPicker(
    moves: List<MoveValue>,
    speciesTypes: List<String>,
    typeOf: (MoveValue) -> String?,
): List<MoveValue> {
    val normalizedSpeciesTypes = speciesTypes.map { normalizeShowdownId(it) }
    fun typeKey(move: MoveValue) = normalizeShowdownId(typeOf(move).orEmpty())
    fun stabRank(move: MoveValue): Int {
        val index = normalizedSpeciesTypes.indexOf(typeKey(move))
        return if (index >= 0) index else normalizedSpeciesTypes.size.coerceAtLeast(1)
    }
    return moves.sortedWith(
        compareBy<MoveValue> { stabRank(it) }
            .thenBy { typeKey(it) }
            .thenByDescending { it.basePower ?: -1 }
            .thenBy { it.entity.displayName },
    )
}

private fun weatherLabel(value: String) = when (value) {
    "NONE" -> "无天气"
    "Sun" -> "晴天"
    "Rain" -> "雨天"
    "Sand" -> "沙暴"
    "Snow" -> "下雪"
    else -> value
}

private fun terrainLabel(value: String) = when (value) {
    "NONE" -> "无场地"
    "Electric" -> "电气场地"
    "Grassy" -> "青草场地"
    "Psychic" -> "精神场地"
    "Misty" -> "薄雾场地"
    else -> value
}

private fun formatPercent(value: Double): String = "%.1f%%".format(value)

private fun localizeKoText(text: String): String {
    if (text == "No direct damage.") return "无直接伤害"
    return text
        .replace(Regex("guaranteed OHKO", RegexOption.IGNORE_CASE), "必定一击击倒")
        .replace(Regex("possible OHKO", RegexOption.IGNORE_CASE), "可能一击击倒")
        .replace(Regex("(\\d+(?:\\.\\d+)?)% chance to OHKO", RegexOption.IGNORE_CASE), "$1% 概率一击击倒")
        .replace(Regex("guaranteed (\\d+)HKO", RegexOption.IGNORE_CASE), "必定 $1 次击倒")
        .replace(Regex("possible (\\d+)HKO", RegexOption.IGNORE_CASE), "可能 $1 次击倒")
}

private fun localizeAssumption(text: String): String = text
    .replace("Defender profile:", "防守配置：")
    .replace("Attacker profile:", "攻击配置：")
    .replace("Defender ability is unspecified.", "防守方特性未指定。")
    .replace("Defender item is unspecified.", "防守方道具未指定。")
    .replace("Defender Stat Points use calculator defaults.", "防守方能力加点使用计算器默认值。")

private fun localizeEngineError(message: String?): String {
    val raw = message.orEmpty()
    return when {
        raw.isBlank() -> "计算失败，请检查双方配置和招式。"
        raw.contains("damage[damage.length - 1] === 0") -> "该招式对目标无直接伤害。"
        raw.contains("Unknown species", ignoreCase = true) -> "找不到所选宝可梦或形态，请重新选择。"
        raw.contains("Unknown move", ignoreCase = true) -> "找不到所选招式，请重新选择。"
        raw.any { it.code > 127 } -> raw
        else -> "计算失败，请检查双方配置和招式。"
    }
}

private fun localizeWarning(code: String, message: String): String = when (code) {
    "ACTUAL_STATS_APPROXIMATED" -> "已使用输入的实际能力值进行本次计算。"
    "LEGAL_MOVE_POOL_MISSING" -> "缺少对方合法招式池。"
    "NO_OPPONENT_MOVE_SELECTED" -> "请选择一个对方招式。"
    "ILLEGAL_OPPONENT_MOVE" -> "所选招式不在当前对方合法招式池中。"
    "MOVE_NOT_FOUND" -> "找不到所选招式，请重新选择。"
    "SPECIES_NOT_FOUND" -> "找不到所选宝可梦或形态，请重新选择。"
    "NO_ATTACKER_MOVES" -> "攻击方还没有可用于计算的招式。"
    "NO_ATTACKER_PROFILE", "NO_SELECTED_ATTACKER_PROFILE" -> "没有可用的攻击方配置假设。"
    "NO_DEFENDER_PROFILES", "NO_SELECTED_PROFILE" -> "没有可用的防守方配置假设。"
    "CUSTOM_FLAGS_NOT_APPLIED" -> "部分自定义高级条件暂未应用。"
    "EMPTY_ENVELOPE" -> "没有勾选用于范围计算的配置，已改用全部配置。"
    "MOVE_REDIRECTED_BY_ACTIVE_ABILITY" -> "招式被场上的避雷针或引水改换了目标，因此当前目标不受伤害。"
    "MOVE_BLOCKED_BY_ACTIVE_ABILITY" -> "招式被场上的特性阻止，因此没有直接伤害。"
    "ABILITIES_SUPPRESSED_BY_NEUTRALIZING_GAS" -> "场上化学变化气体正在压制未受保护的特性。"
    "WEATHER_SUPPRESSED_BY_ACTIVE_ABILITY" -> "天气效果被场上的无关天气或气闸压制。"
    else -> message
}

private fun parseTierCalculation(raw: String): TierCalculationUiResult {
    val envelope = JSONObject(raw)
    if (!envelope.optBoolean("ok")) {
        throw IllegalArgumentException(
            localizeEngineError(envelope.optJSONObject("error")?.optString("message")),
        )
    }
    val result = envelope.getJSONObject("result")
    val moveResults = result.getJSONArray("moveResults")
    val warnings = result.getJSONArray("warnings")
    val attacker = result.getJSONObject("attackerSummary").optString("speciesName", "我方")
    val defender = result.getJSONObject("defenderIdentity").getJSONObject("species")
        .optString("displayName", "对手")
    val moves = (0 until moveResults.length()).map { index ->
        val move = moveResults.getJSONObject(index)
        val isSpecial = move.optString("moveCategory") == "Special"
        val thirdId = if (isSpecial) TIER_PROFILE_SPECIAL else TIER_PROFILE_PHYSICAL
        val rangesJson = move.optJSONArray("profileRanges")
        val ranges = if (rangesJson == null) emptyMap() else (0 until rangesJson.length())
            .mapNotNull { i -> rangesJson.optJSONObject(i) }
            .associateBy { it.optString("defenderProfileId") }
        TierMoveResultUi(
            name = move.optString("moveName", "招式"),
            category = if (isSpecial) "特殊" else if (move.optString("moveCategory") == "Physical") "物理" else "变化",
            tiers = listOf(TIER_PROFILE_HP2, TIER_PROFILE_HP32, thirdId).map { id ->
                val range = ranges[id]?.optJSONObject("damageRange")
                TierRangeUi(
                    minPercent = range?.optDouble("minPercent", 0.0) ?: 0.0,
                    maxPercent = range?.optDouble("maxPercent", 0.0) ?: 0.0,
                    minDamage = range?.optInt("minDamage", 0) ?: 0,
                    maxDamage = range?.optInt("maxDamage", 0) ?: 0,
                )
            },
        )
    }
    return TierCalculationUiResult(
        attacker = attacker,
        defender = defender,
        moves = moves,
        warnings = (0 until warnings.length()).map { index ->
            val warning = warnings.getJSONObject(index)
            localizeWarning(warning.getString("code"), warning.getString("message"))
        }.distinct(),
    )
}

@Composable
private fun TierCalculationCard(result: TierCalculationUiResult) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFFFF)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("三档伤害估算", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "${result.attacker} → ${result.defender}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            result.moves.forEach { move ->
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(move.name, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Text(move.category, style = MaterialTheme.typography.labelSmall)
                }
                if (move.category == "变化") {
                    Text("变化招式无直接伤害", style = MaterialTheme.typography.bodySmall)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val labels = listOf("① HP+2", "② HP+32", "③ 32点+性格")
                        move.tiers.forEachIndexed { index, tier ->
                            Column(
                                Modifier
                                    .weight(1f)
                                    .background(Color(0xFFF1F2F6), RoundedCornerShape(8.dp))
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(labels[index], style = MaterialTheme.typography.labelSmall)
                                Text(
                                    if (tier.maxPercent <= 0.0) "—" else
                                        "${formatPercent(tier.minPercent)}–${formatPercent(tier.maxPercent)}",
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    if (tier.maxDamage <= 0) "" else "${tier.minDamage}–${tier.maxDamage} 伤害",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (result.warnings.isNotEmpty()) {
                HorizontalDivider()
                result.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

