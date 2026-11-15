package com.crazylei12.pokemonchampionsassistant

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
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
        )
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

private val AppColors = darkColorScheme(
    primary = Color(0xFFFFD54F),
    onPrimary = Color(0xFF281E00),
    secondary = Color(0xFF77D7C4),
    background = Color(0xFF0E1420),
    surface = Color(0xFF171F2E),
    surfaceVariant = Color(0xFF263145),
    onBackground = Color(0xFFF1F4FF),
    onSurface = Color(0xFFF1F4FF),
)

private enum class AppTab(val label: String, val glyph: String) {
    CALC("计算器", "⚔"),
    DEX("图鉴", "◈"),
    TEAM("队伍", "☰"),
    BATTLE("对局", "◉"),
    SETTINGS("设置", "⚙"),
}

@Composable
private fun ChampionsDamageApp(runtime: DamageEngineRuntime, activity: MainActivity) {
    var selectedTabName by rememberSaveable { mutableStateOf(AppTab.CALC.name) }
    var preferredOwnTeamId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedTab = AppTab.valueOf(selectedTabName)
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
                bottomBar = {
                    NavigationBar {
                        AppTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = tab == selectedTab,
                                onClick = { selectedTabName = tab.name },
                                icon = { Text(tab.glyph) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                },
            ) { padding ->
                Surface(Modifier.fillMaxSize().padding(padding)) {
                    teamsResult.fold(
                        onSuccess = { teams ->
                            when (selectedTab) {
                                AppTab.CALC -> ManualCalculatorScreen(teams, runtime, preferredOwnTeamId, presetRepository)
                                AppTab.DEX -> PokedexScreen(repository = presetRepository)
                                AppTab.TEAM -> HomeScreen(teams, runtime) { teamId ->
                                    preferredOwnTeamId = teamId
                                    selectedTabName = AppTab.CALC.name
                                }
                                AppTab.BATTLE -> OwnTeamCaptureScreen(activity, teams, presetRepository)
                                AppTab.SETTINGS -> SettingsScreen(runtime, teams)
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
    var quickTeamId by rememberSaveable { mutableStateOf<String?>(null) }
    var opponentQuery by remember { mutableStateOf("") }
    var opponent by remember { mutableStateOf<EntityValue?>(null) }
    val opponents = remember(opponentQuery) {
        val query = opponentQuery.trim()
        if (query.isEmpty()) {
            emptyList()
        } else {
            presetRepository.speciesCatalog
                .filter {
                    it.displayName.contains(query, ignoreCase = true) ||
                        it.showdownId.contains(query, ignoreCase = true)
                }
                .take(8)
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("对局助手", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        StatusCard(message, running)
        SectionCard("1. 启动对局助手") {
            Text("对局助手只需要两项系统授权：悬浮窗权限（长期有效）和“共享屏幕”（每次启动时由系统确认一次）。")
            Text(if (overlayAllowed) "悬浮窗权限：已授予" else "悬浮窗权限：尚未授予")
            if (!overlayAllowed) {
                Button(onClick = activity::requestOverlayPermission) { Text("授予悬浮窗权限") }
            }
            Button(onClick = { activity.startOwnTeamCapture() }, enabled = !running) {
                Text(if (running) "对局助手运行中" else "启动对局助手（悬浮球）")
            }
            OutlinedButton(onClick = { OverlayCaptureService.stop(activity) }, enabled = running) {
                Text("结束对局助手")
            }
            Text("启动后悬浮球常驻在游戏画面上，直到你手动结束或系统清理后台。红框 HUD 可在悬浮球菜单里选择「显示对战 HUD」打开。")
        }
        SectionCard("2. 快速开始（不用识别双方阵容）") {
            Text("对局中只要能看到对手是谁，就能直接开 HUD：选我方队伍 + 选当前对手，对方加点按三档估算。")
            Text("适合临时开一局、或懒得走阵容识别的情况。")
            if (teams.isEmpty()) {
                Text("还没有我方队伍：可以先用上面的「录入我的队伍」，或在「队伍」页手动建一支。")
            } else {
                Text("我方队伍")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    teams.forEach { team ->
                        val selected = (quickTeamId ?: teams.first().id) == team.id
                        FilterChip(
                            selected = selected,
                            onClick = { quickTeamId = team.id },
                            label = { Text(team.name) },
                        )
                    }
                }
                OutlinedTextField(
                    value = opponentQuery,
                    onValueChange = {
                        opponentQuery = it
                        opponent = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索对手宝可梦（中文或英文）") },
                    singleLine = true,
                )
                opponents.forEach { candidate ->
                    TextButton(onClick = { opponent = candidate }) {
                        Text("选择 ${candidate.displayName}（${candidate.showdownId}）")
                    }
                }
                opponent?.let { chosen ->
                    Text("当前对手：${chosen.displayName}", fontWeight = FontWeight.Bold)
                    Button(
                        onClick = {
                            OverlayCaptureService.startQuickSession(
                                activity,
                                quickTeamId ?: teams.first().id,
                                chosen,
                            )
                        },
                        enabled = running,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (running) "用这只对手开始对局" else "请先启动对局助手")
                    }
                }
            }
        }
        SectionCard("3. 在游戏中识别") {
            Text("启动后打开 Pokémon Champions，点击悬浮球使用各项功能。")
            Text("先进入队伍信息页（入口比较隐蔽）：在游戏主页依次点击“对战”→“级别对战”，再按本局格式进入“单打对战”或“双打对战”。")
            Text("在准备匹配的页面点击当前使用的“队伍X”卡片；队伍列表打开后，再点击该队伍顶部的“队伍X”，最后在弹出菜单中点击“对战队伍信息”。不需要开始匹配。")
            Text("录入我的队伍：队伍页或单只状态页都能录，一页也能直接存；两页都录（能力值 + 招式与道具）会更精确。全程不弹窗。")
            Text("对局中也可以单独补录：打开某只宝可梦的“状态”页，用悬浮球菜单里的“记录当前宝可梦能力值”，以最近一次记录为准。")
            Text("开始一场对局：返回准备匹配页面并开始匹配；双方队伍预览一出现，选择“识别双方阵容”。必须在确认出战前识别。")
        }
        SectionCard("3. 数据保存") {
            Text("录入的队伍会自动保存到本机，可在首页和「队伍」页查看、编辑或用于自由计算。")
            Text("双方阵容只用于当前对局；重新识别会开始新对局，并清除上一局的临时状态。")
            Text("能力值以最近一次记录为准：先整队录入，之后对局中单独记录的某只宝可梦会覆盖它的旧数据。")
        }
        OutlinedCard(Modifier.fillMaxWidth()) {
            Text(
                "隐私说明：只有在你点击悬浮按钮或 HUD 识别按钮后，App 才会读取当前游戏画面。识别结果保存在本机，不会上传；App 不会修改或操作游戏。",
                Modifier.padding(14.dp),
            )
        }
    }
}

@Composable
private fun HomeScreen(teams: List<SavedTeam>, runtime: DamageEngineRuntime, openManual: (String?) -> Unit) {
    val context = LocalContext.current
    var userPresetRevision by remember { mutableStateOf(0) }
    var managingUserPresets by remember { mutableStateOf(false) }
    var importingShowdown by remember { mutableStateOf(false) }
    var importingTeamCode by remember { mutableStateOf(false) }
    var teamCodeImportMessage by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<SavedTeam?>(null) }
    var previewTarget by remember { mutableStateOf<SavedTeam?>(null) }
    var editTarget by remember { mutableStateOf<SavedTeam?>(null) }
    var deleteTarget by remember { mutableStateOf<SavedTeam?>(null) }
    var renameText by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf("") }
    var deleteError by remember { mutableStateOf("") }
    var resetCorruptedPresetStorage by remember { mutableStateOf(false) }
    var presetStorageMessage by remember { mutableStateOf("") }
    val userPresetRepository = remember(context, userPresetRevision) { OpponentPresetRepository(context) }
    val userPresets = remember(userPresetRepository) { userPresetRepository.userPresets() }
    val userPresetStorageProblem = remember(userPresetRepository) {
        userPresetRepository.userPresetStorageProblem()
    }

    if (importingShowdown) {
        ShowdownImportScreen(onClose = { importingShowdown = false }, onSaved = { name ->
            CaptureUiState.teamLibraryRevision.value += 1
            teamCodeImportMessage = "已把“$name”保存到我的队伍。"
            importingShowdown = false
        })
        return
    }

    if (importingTeamCode) {
        TeamCodeImportScreen(
            onClose = { importingTeamCode = false },
            onSavedOwnTeam = { name ->
                CaptureUiState.teamLibraryRevision.value += 1
                teamCodeImportMessage = "已把“$name”保存到我的队伍。"
                importingTeamCode = false
            },
            onSavedOpponentPresets = { name ->
                userPresetRevision += 1
                teamCodeImportMessage = "已把 6 只宝可梦保存为“$name”对手配置。"
                importingTeamCode = false
            },
        )
        return
    }

    if (managingUserPresets) {
        UserOpponentPresetManagerScreen(
            onClose = { managingUserPresets = false },
            onChanged = { userPresetRevision += 1 },
        )
        return
    }

    editTarget?.let { team ->
        TeamEditorScreen(
            team = team,
            onClose = { editTarget = null },
            onSaved = {
                CaptureUiState.teamLibraryRevision.value += 1
                editTarget = null
            },
        )
        return
    }

    renameTarget?.let { team ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名队伍") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it; renameError = "" },
                        label = { Text("队伍名称") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = dismissingKeyboardActions(),
                        singleLine = true,
                        isError = renameError.isNotBlank(),
                        supportingText = renameError.takeIf(String::isNotBlank)?.let { message ->
                            { Text(message) }
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { TeamRepository.rename(context, team.id, renameText) }
                        .onSuccess {
                            CaptureUiState.teamLibraryRevision.value += 1
                            renameTarget = null
                        }
                        .onFailure { renameError = it.message ?: "重命名失败" }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("取消") }
            },
        )
    }

    previewTarget?.let { team ->
        TeamPreviewDialog(team = team, onDismiss = { previewTarget = null })
    }

    deleteTarget?.let { team ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null; deleteError = "" },
            title = { Text("删除队伍") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("确定删除“${team.name}”吗？此操作无法撤销。")
                    if (deleteError.isNotBlank()) Text(deleteError, color = Color(0xFFFF8A80))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { TeamRepository.delete(context, team.id) }
                        .onSuccess {
                            CaptureUiState.teamLibraryRevision.value += 1
                            deleteTarget = null
                            deleteError = ""
                        }
                        .onFailure { deleteError = it.message ?: "删除失败" }
                }) { Text("确认删除", color = Color(0xFFFF8A80)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null; deleteError = "" }) { Text("取消") }
            },
        )
    }

    if (resetCorruptedPresetStorage) {
        AlertDialog(
            onDismissRequest = { resetCorruptedPresetStorage = false },
            title = { Text("备份损坏文件并重置？") },
            text = {
                Text(
                    "原始损坏文件会先完整保留在 App 私有目录中，再重建一个空的保存配置库。已有配置不会被静默覆盖，但损坏文件中的内容无法自动恢复；如有完整数据备份，也可以先取消并改用整包导入。",
                )
            },
            confirmButton = {
                Button(onClick = {
                    runCatching {
                        userPresetRepository.preserveCorruptedUserPresetFileAndReset()
                    }.onSuccess { recoveryName ->
                        presetStorageMessage = "已保留原文件副本：$recoveryName；现在可以重新保存或导入配置。"
                        resetCorruptedPresetStorage = false
                        userPresetRevision += 1
                    }.onFailure {
                        presetStorageMessage = it.message ?: "无法保留损坏文件并重置"
                        resetCorruptedPresetStorage = false
                    }
                }) { Text("保留副本并重置") }
            },
            dismissButton = {
                TextButton(onClick = { resetCorruptedPresetStorage = false }) { Text("取消") }
            },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Pokémon Champions", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("公开队伍码", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("输入 Pokémon Champions 游戏内的十位公开码，核对完整队伍后再选择保存位置。")
                Button(
                    onClick = { importingTeamCode = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("导入队伍码")
                }
                OutlinedButton(onClick = { importingShowdown = true }, modifier = Modifier.fillMaxWidth()) { Text("导入 PS 队伍") }
                if (teamCodeImportMessage.isNotBlank()) {
                    Text(teamCodeImportMessage, color = Color(0xFF80CBC4), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Text("我的队伍（${teams.size}）", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (teams.isEmpty()) {
            Text("还没有保存自己的队伍。请在“对局”页启动悬浮识别并完成两张队伍截图。")
        }
        teams.forEach { team ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(team.name, fontWeight = FontWeight.Bold)
                    Text(team.speciesSummary, style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (team.damageReady) "可用于计算" else "暂不可用于计算：${team.readinessIssues.firstOrNull() ?: "存在缺失项"}",
                        color = if (team.damageReady) Color(0xFF80CBC4) else Color(0xFFFFB74D),
                    )
                    val partialMoveSlots = team.pokemon.count { it.moves.size in 1..3 }
                    if (partialMoveSlots > 0) {
                        Text("提醒：$partialMoveSlots 只宝可梦未带满 4 个招式；空技能槽是允许的。", color = Color(0xFFFFB74D), style = MaterialTheme.typography.bodySmall)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { openManual(team.id) },
                            enabled = runtime.status.contains("已就绪") && team.damageReady,
                        ) { Text("用于计算") }
                        TextButton(onClick = { previewTarget = team }) { Text("预览") }
                        TextButton(onClick = { editTarget = team }) { Text("手动调整") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            renameTarget = team
                            renameText = team.name
                            renameError = ""
                        }) { Text("重命名") }
                        ShowdownExportButton(team.pokemon)
                        TextButton(onClick = {
                            deleteTarget = team
                            deleteError = ""
                        }) { Text("删除", color = Color(0xFFFF8A80)) }
                    }
                }
            }
        }

        Text(
            "我保存的宝可梦配置（${userPresets.size}）",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("管理在对局悬浮面板中保存的对手预设。")
                if (userPresetStorageProblem != null) {
                    Text(userPresetStorageProblem, color = MaterialTheme.colorScheme.error)
                    Text(
                        "为防止下一次保存覆盖原文件，当前已禁止修改、删除和导出用户配置。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = { resetCorruptedPresetStorage = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("保留损坏文件副本并重置") }
                } else {
                    Text(
                        if (userPresets.isEmpty()) {
                            "还没有保存配置；可进入管理页手动选择宝可梦新建，也可在悬浮面板的“调整对手配置”中保存。"
                        } else {
                            "可搜索、修改或删除；删除前会再次确认。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(
                    onClick = { managingUserPresets = true },
                    enabled = userPresetStorageProblem == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("新建或管理配置")
                }
                if (presetStorageMessage.isNotBlank()) {
                    Text(
                        presetStorageMessage,
                        color = if (presetStorageMessage.startsWith("已保留")) {
                            Color(0xFF80CBC4)
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun TeamCodeImportScreen(
    onClose: () -> Unit,
    onSavedOwnTeam: (String) -> Unit,
    onSavedOpponentPresets: (String) -> Unit,
) {
    val context = LocalContext.current
    val repository = remember(context) { OpponentPresetRepository(context) }
    val worker = remember(context) { TeamCodeImportWorker(context.applicationContext) }
    var code by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var resolved by remember { mutableStateOf<ResolvedTeamCode?>(null) }
    var pokemon by remember { mutableStateOf<List<PokemonConfig>>(emptyList()) }
    var resolving by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    val presetStorageProblem = remember(repository) { repository.userPresetStorageProblem() }

    DisposableEffect(worker) {
        onDispose(worker::close)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onClose, enabled = !saving) { Text("返回") }
            Column {
                Text("导入队伍码", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("App 直接查询官方公开队伍", color = MaterialTheme.colorScheme.primary)
            }
        }
        OutlinedTextField(
            value = code,
            onValueChange = { raw ->
                code = raw.uppercase(java.util.Locale.ROOT)
                    .filter { it in 'A'..'Z' || it in '0'..'9' }
                    .take(10)
                resolved = null
                pokemon = emptyList()
                name = ""
                errorMessage = ""
            },
            enabled = !resolving,
            label = { Text("十位公开队伍码") },
            supportingText = { Text("${code.length}/10，例如 A4RBRNN9YE") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = dismissingKeyboardActions(),
            singleLine = true,
            isError = errorMessage.isNotBlank() && resolved == null,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                val normalized = normalizeTeamCode(code)
                if (normalized == null) {
                    errorMessage = "队伍码必须是 10 位英文字母或数字"
                    return@Button
                }
                resolving = true
                errorMessage = ""
                worker.resolve(normalized) { result ->
                    resolving = false
                    result.onSuccess { response ->
                        runCatching { repository.configurationsForTeamCode(response) }
                            .onSuccess { mapped ->
                                resolved = response
                                pokemon = mapped
                                if (name.isBlank()) {
                                    name = response.trainerName.orEmpty().take(24)
                                }
                            }
                            .onFailure { errorMessage = it.message ?: "公开队伍数据无法使用" }
                    }.onFailure { error ->
                        errorMessage = error.message ?: "队伍码解析失败"
                    }
                }
            },
            enabled = code.length == 10 && !resolving,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(if (resolving) "正在解析…" else "解析并预览")
        }
        Text(
            "无需启动游戏。直连需要可用的 Google Play 服务及可连接官方服务的网络。App 使用匿名游客身份查询；认证令牌和 Cookie 不会保存。截图、本地队伍和计算数据不会上传。",
            style = MaterialTheme.typography.bodySmall,
        )
        if (errorMessage.isNotBlank()) {
            Text(errorMessage, color = Color(0xFFFF8A80))
        }

        resolved?.let { team ->
            HorizontalDivider()
            Text(
                buildString {
                    append("已解析 ${team.code}")
                    team.trainerName?.let { append(" · 公开训练家：$it") }
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            ShowdownExportButton(pokemon)
            pokemon.forEachIndexed { index, config ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${index + 1}. ${config.species.displayName}", fontWeight = FontWeight.Bold)
                        Text(pokemonConfigSummary(config), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it.take(24)
                    errorMessage = ""
                },
                label = { Text("配置名称") },
                supportingText = { Text("保存为对手配置时，六只都会使用这个名称（最多 24 个字符）") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = dismissingKeyboardActions(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    val normalizedName = name.trim()
                    if (normalizedName.isBlank()) {
                        errorMessage = "请填写配置名称"
                        return@Button
                    }
                    saving = true
                    runCatching {
                        TeamRepository.saveImportedTeam(
                            context = context,
                            teamName = normalizedName,
                            publicCode = team.code,
                            trainerName = team.trainerName,
                            pokemon = pokemon,
                        )
                    }.onSuccess {
                        onSavedOwnTeam(normalizedName)
                    }.onFailure {
                        saving = false
                        errorMessage = it.message ?: "保存我的队伍失败"
                    }
                },
                enabled = !saving && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(if (saving) "正在保存…" else "保存为我的队伍")
            }
            OutlinedButton(
                onClick = {
                    val normalizedName = name.trim()
                    if (normalizedName.isBlank()) {
                        errorMessage = "请填写配置名称"
                        return@OutlinedButton
                    }
                    saving = true
                    runCatching { repository.saveTeamCodeAsUserPresets(normalizedName, pokemon) }
                        .onSuccess { onSavedOpponentPresets(normalizedName) }
                        .onFailure {
                            saving = false
                            errorMessage = it.message ?: "保存对手配置失败"
                        }
                },
                enabled = !saving && name.isNotBlank() && presetStorageProblem == null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text("保存为 6 个对手配置")
            }
            presetStorageProblem?.let {
                Text(it, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun UserOpponentPresetManagerScreen(
    onClose: () -> Unit,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    var revision by remember { mutableStateOf(0) }
    val repository = remember(context, revision) { OpponentPresetRepository(context) }
    val entries = remember(repository) { repository.userPresets() }
    var query by rememberSaveable { mutableStateOf("") }
    var createSpecies by remember { mutableStateOf<EntityValue?>(null) }
    var choosingCreateSpecies by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<UserOpponentPresetEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<UserOpponentPresetEntry?>(null) }
    var deleteError by remember { mutableStateOf("") }

    if (choosingCreateSpecies) {
        EntitySearchDialog(
            title = "选择配置所属宝可梦",
            entities = repository.speciesCatalog,
            onDismiss = { choosingCreateSpecies = false },
            onSelect = { selected ->
                createSpecies = repository.localizeSpecies(selected)
                choosingCreateSpecies = false
            },
        )
    }

    createSpecies?.let { species ->
        val draft = remember(repository, species.showdownId) {
            blankUserOpponentPresetDraft(species)
        }
        UserOpponentPresetEditorScreen(
            entry = draft,
            repository = repository,
            onClose = { createSpecies = null },
            onSaved = {
                revision += 1
                onChanged()
                createSpecies = null
            },
            isCreating = true,
            onChooseSpecies = { choosingCreateSpecies = true },
        )
        return
    }

    editTarget?.let { entry ->
        UserOpponentPresetEditorScreen(
            entry = entry,
            repository = repository,
            onClose = { editTarget = null },
            onSaved = {
                revision += 1
                onChanged()
                editTarget = null
            },
        )
        return
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = {
                deleteTarget = null
                deleteError = ""
            },
            title = { Text("删除保存的配置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("确定删除“${entry.species.displayName} · ${entry.preset.profileName}”吗？此操作无法撤销。")
                    Text("如果当前对局正在使用它，将自动回退到仍然存在的配置。", style = MaterialTheme.typography.bodySmall)
                    if (deleteError.isNotBlank()) Text(deleteError, color = Color(0xFFFF8A80))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        require(repository.deleteUserPreset(entry.preset.profileId)) { "配置已不存在" }
                    }.onSuccess {
                        revision += 1
                        onChanged()
                        deleteTarget = null
                        deleteError = ""
                    }.onFailure {
                        deleteError = it.message ?: "删除失败"
                    }
                }) { Text("确认删除", color = Color(0xFFFF8A80)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    deleteError = ""
                }) { Text("取消") }
            },
        )
    }

    val normalizedQuery = query.trim()
    val displayed = remember(entries, normalizedQuery) {
        entries.filter { entry ->
            normalizedQuery.isBlank() ||
                entry.species.displayName.contains(normalizedQuery, ignoreCase = true) ||
                entry.species.showdownId.contains(normalizedQuery, ignoreCase = true) ||
                entry.preset.profileName.contains(normalizedQuery, ignoreCase = true)
        }.withIndex().sortedWith(
            compareBy<IndexedValue<UserOpponentPresetEntry>> { it.value.species.displayName }
                .thenBy(IndexedValue<UserOpponentPresetEntry>::index),
        ).map(IndexedValue<UserOpponentPresetEntry>::value)
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onClose) { Text("返回") }
            Column {
                Text("我保存的宝可梦配置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("共 ${entries.size} 个用户预设", color = MaterialTheme.colorScheme.primary)
            }
        }
        Button(
            onClick = { choosingCreateSpecies = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("新建配置")
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("搜索宝可梦或预设名称") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = dismissingKeyboardActions(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (displayed.isEmpty()) {
                item {
                    Text(
                        if (entries.isEmpty()) {
                            "还没有保存的配置。可点击上方“新建配置”手动选择宝可梦创建，也可在对局悬浮面板中保存。"
                        } else {
                            "没有找到匹配的配置。"
                        },
                        modifier = Modifier.padding(vertical = 20.dp),
                    )
                }
            }
            itemsIndexed(displayed, key = { _, entry -> entry.preset.profileId }) { _, entry ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(entry.species.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(entry.preset.profileName, color = MaterialTheme.colorScheme.primary)
                        Text(userOpponentPresetSummary(entry.preset), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { editTarget = entry }) { Text("修改") }
                            TextButton(onClick = {
                                deleteError = ""
                                deleteTarget = entry
                            }) { Text("删除", color = Color(0xFFFF8A80)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UserOpponentPresetEditorScreen(
    entry: UserOpponentPresetEntry,
    repository: OpponentPresetRepository,
    onClose: () -> Unit,
    onSaved: () -> Unit,
    isCreating: Boolean = false,
    onChooseSpecies: (() -> Unit)? = null,
) {
    val preset = entry.preset
    val editorKey = "${preset.profileId}|${entry.species.showdownId}|$isCreating"
    var name by remember(editorKey) { mutableStateOf(preset.profileName) }
    var points by remember(editorKey) { mutableStateOf(preset.statPoints) }
    var nature by remember(editorKey) {
        mutableStateOf(repository.natures.firstOrNull {
            it.entity.showdownId.equals(preset.statAlignment?.showdownId, ignoreCase = true)
        })
    }
    var ability by remember(editorKey) { mutableStateOf(preset.ability) }
    var item by remember(editorKey) { mutableStateOf(preset.item) }
    var chooseItem by remember(editorKey) { mutableStateOf(false) }
    var saving by remember(editorKey) { mutableStateOf(false) }
    var errorMessage by remember(editorKey) { mutableStateOf("") }
    val abilities = remember(entry.species.showdownId, preset.ability) {
        (listOfNotNull(preset.ability) + repository.abilitiesFor(entry.species))
            .distinctBy { it.showdownId.lowercase() }
    }

    if (chooseItem) {
        EntitySearchDialog(
            title = "选择道具",
            entities = repository.itemCatalog,
            onDismiss = { chooseItem = false },
            onSelect = {
                item = it
                chooseItem = false
            },
        )
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onClose) { Text("返回") }
            Column {
                Text(
                    if (isCreating) "新建宝可梦配置" else "修改保存的配置",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(entry.species.displayName, color = MaterialTheme.colorScheme.primary)
            }
        }
        onChooseSpecies?.let { chooseSpecies ->
            OutlinedButton(
                onClick = chooseSpecies,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("重新选择宝可梦")
            }
        }
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it.take(24)
                errorMessage = ""
            },
            label = { Text("预设名称") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = dismissingKeyboardActions(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text("能力点（每项 0–32）", fontWeight = FontWeight.SemiBold)
        UserPresetPointEditor(points) { points = it }
        SimplePicker<NatureOption?>(
            label = "性格数值修正",
            options = listOf(null) + repository.natures,
            selected = nature,
            display = { it?.entity?.displayName ?: "中性（不修正）" },
            onSelect = { nature = it },
        )
        SimplePicker<EntityValue?>(
            label = "特性",
            options = listOf(null) + abilities,
            selected = ability,
            display = { it?.displayName ?: "未指定" },
            onSelect = { ability = it },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { chooseItem = true }, modifier = Modifier.weight(1f)) {
                Text("道具：${item?.displayName ?: "无道具"}")
            }
            if (item != null) TextButton(onClick = { item = null }) { Text("清除") }
        }
        if (preset.moves.isNotEmpty()) {
            Text(
                "保存时的配置招式：${preset.moves.joinToString(" / ") { it.entity.displayName }}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            if (isCreating) {
                "这里设置名称、能力点、性格、特性和道具；新配置归属于所选宝可梦或具体形态，招式不固定，对局中仍可从该宝可梦的合法招式池选择。"
            } else {
                "这里修改名称、能力点、性格、特性和道具；宝可梦与保存时继承的招式保持不变。"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        if (errorMessage.isNotBlank()) Text(errorMessage, color = Color(0xFFFF8A80))
        Button(
            enabled = !saving,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            onClick = {
                val normalizedName = name.trim()
                if (normalizedName.isBlank()) {
                    errorMessage = "请填写预设名称"
                    return@Button
                }
                saving = true
                errorMessage = ""
                val updated = preset.copy(
                    profileName = normalizedName,
                    statPoints = points,
                    statAlignment = nature?.entity,
                    ability = ability,
                    item = item,
                )
                runCatching {
                    if (isCreating) {
                        repository.saveUserPreset(entry.species, normalizedName, updated)
                    } else {
                        repository.updateUserPreset(entry.species, updated)
                    }
                }
                    .onSuccess { onSaved() }
                    .onFailure {
                        saving = false
                        errorMessage = it.message ?: "保存失败"
                    }
            },
        ) {
            Text(
                when {
                    saving -> "正在保存…"
                    isCreating -> "创建配置"
                    else -> "保存修改"
                },
            )
        }
    }
}

@Composable
private fun UserPresetPointEditor(value: StatFields, onChange: (StatFields) -> Unit) {
    UserPresetPointRow(
        listOf("生命" to value.hp, "攻击" to value.atk, "防御" to value.def),
    ) { index, text ->
        onChange(when (index) {
            0 -> value.copy(hp = text)
            1 -> value.copy(atk = text)
            else -> value.copy(def = text)
        })
    }
    UserPresetPointRow(
        listOf("特攻" to value.spa, "特防" to value.spd, "速度" to value.spe),
    ) { index, text ->
        onChange(when (index) {
            0 -> value.copy(spa = text)
            1 -> value.copy(spd = text)
            else -> value.copy(spe = text)
        })
    }
}

@Composable
private fun UserPresetPointRow(
    values: List<Pair<String, String>>,
    onChange: (Int, String) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEachIndexed { index, (label, value) ->
            OutlinedTextField(
                value = value,
                onValueChange = { raw ->
                    val digits = raw.filter(Char::isDigit).take(2)
                    onChange(index, digits.toIntOrNull()?.coerceAtMost(32)?.toString().orEmpty())
                },
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

private fun userOpponentPresetSummary(preset: OpponentPreset): String {
    val labels = mapOf(
        "hp" to "HP",
        "atk" to "攻击",
        "def" to "防御",
        "spa" to "特攻",
        "spd" to "特防",
        "spe" to "速度",
    )
    val points = preset.statPoints.asMap().mapNotNull { (key, value) ->
        value.toIntOrNull()?.takeIf { it > 0 }?.let { "${labels.getValue(key)} $it" }
    }.joinToString(" / ").ifBlank { "无能力点投入" }
    return buildString {
        append(points)
        append("\n性格：${preset.statAlignment?.displayName ?: "中性"}")
        append(" · 特性：${preset.ability?.displayName ?: "未指定"}")
        append(" · 道具：${preset.item?.displayName ?: "无道具"}")
    }
}

@Composable
private fun TeamPreviewDialog(team: SavedTeam, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(team.name) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(team.pokemon) { index, pokemon ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("${index + 1}. ${pokemon.species.displayName}", fontWeight = FontWeight.Bold)
                            Text(pokemonConfigSummary(pokemon), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun TeamEditorScreen(team: SavedTeam, onClose: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { OpponentPresetRepository(context) }
    var selectedSlot by rememberSaveable(team.id) { mutableStateOf(0) }
    var draft by remember(team.id) { mutableStateOf(team.pokemon.first()) }
    var saving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    LaunchedEffect(selectedSlot) {
        draft = team.pokemon[selectedSlot]
        errorMessage = ""
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onClose) { Text("返回") }
            Column {
                Text("手动调整队伍", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(team.name, color = MaterialTheme.colorScheme.primary)
            }
        }
        SimplePicker(
            label = "选择要调整的宝可梦",
            options = team.pokemon.indices.toList(),
            selected = selectedSlot,
            display = { index -> "${index + 1}. ${team.pokemon[index].species.displayName}" },
            onSelect = { selectedSlot = it },
        )
        PokemonConfigEditor(draft, repository) { draft = it }
        if (errorMessage.isNotBlank()) Text(errorMessage, color = Color(0xFFFF8A80))
        Button(
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = !saving,
            onClick = {
                saving = true
                errorMessage = ""
                runCatching { TeamRepository.updatePokemon(context, team.id, selectedSlot, draft) }
                    .onSuccess { onSaved() }
                    .onFailure {
                        saving = false
                        errorMessage = it.message ?: "保存失败"
                    }
            },
        ) { Text(if (saving) "保存中…" else "保存本次调整") }
    }
}

private fun pokemonConfigSummary(pokemon: PokemonConfig): String = buildString {
    append("等级 ${pokemon.level} · 特性 ${pokemon.ability?.displayName ?: "未设置"} · 道具 ${pokemon.item?.displayName ?: "无"}")
    append("\n性格：${pokemon.statAlignment?.displayName ?: "中性或未指定"}")
    append("\n招式：${pokemon.moves.joinToString(" / ") { it.entity.displayName }.ifBlank { "未设置" }}")
    append("\n实际能力：${statsSummaryText(pokemon.actualStats)}")
    append("\n加点：${statsSummaryText(pokemon.statPoints)}")
}

private fun statsSummaryText(stats: StatFields): String = listOf(
    "生命" to stats.hp,
    "攻击" to stats.atk,
    "防御" to stats.def,
    "特攻" to stats.spa,
    "特防" to stats.spd,
    "速度" to stats.spe,
).joinToString("  ") { (label, value) -> "$label ${value.ifBlank { "?" }}" }

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
    teams: List<SavedTeam>,
    runtime: DamageEngineRuntime,
    preferredOwnTeamId: String?,
    repository: OpponentPresetRepository,
) {
    val catalog = repository.speciesCatalog

    fun defaultState(species: EntityValue, label: String = "图鉴默认（0 加点）"): CalculatorPokemonState {
        val profile = repository.profilesFor(species).firstOrNull { it.profileId == "generated.default" }
            ?: repository.recommendedProfile(species)
        return CalculatorPokemonState(repository.configFor(species, profile), label, profile.profileId)
    }

    fun pickSpecies(id: String): EntityValue = catalog.firstOrNull { it.showdownId == id } ?: catalog.first()

    val initialOwnConfig = teams.firstOrNull { it.id == preferredOwnTeamId }?.pokemon?.firstOrNull()
        ?: teams.firstOrNull()?.pokemon?.firstOrNull()
    var ownSelection by remember {
        mutableStateOf(
            initialOwnConfig?.let { CalculatorPokemonState(it, "已保存队伍") } ?: defaultState(pickSpecies("Pikachu"))
        )
    }
    var opponentSelection by remember { mutableStateOf(defaultState(pickSpecies("Armarouge"))) }
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

    LaunchedEffect(preferredOwnTeamId, teams.map(SavedTeam::id)) {
        val team = teams.firstOrNull { it.id == preferredOwnTeamId } ?: return@LaunchedEffect
        team.pokemon.firstOrNull()?.let { ownSelection = CalculatorPokemonState(it, "已保存队伍") }
    }

    val learnableMoves = remember(ownSelection.config.species.showdownId) {
        repository.learnableMovesFor(ownSelection.config.species)
    }
    LaunchedEffect(learnableMoves.map { it.entity.showdownId }) {
        if (learnableMoves.none { it.entity.showdownId == selectedMove }) {
            selectedMove = learnableMoves.firstOrNull { (it.basePower ?: 0) > 0 }?.entity?.showdownId
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
        return ownSelection.config.copy(statPoints = points, actualStats = stats, statAlignment = null)
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
) {
    var chooseSpecies by remember { mutableStateOf(false) }
    var chooseItem by remember { mutableStateOf(false) }
    var chooseMove by remember { mutableStateOf(false) }
    var screensExpanded by remember { mutableStateOf(false) }
    val config = state.config
    val abilities = (listOfNotNull(config.ability) + repository.abilitiesFor(config.species))
        .distinctBy { it.showdownId.lowercase() }
    val selectedMoveName = learnableMoves.firstOrNull { it.entity.showdownId == selectedMove }?.entity?.displayName
        ?: "搜索招式"

    if (chooseSpecies) {
        EntitySearchDialog(
            title = "选择$title",
            entities = repository.speciesCatalog,
            onDismiss = { chooseSpecies = false },
            onSelect = { species ->
                val profile = repository.profilesFor(species).firstOrNull { it.profileId == "generated.default" }
                    ?: repository.recommendedProfile(species)
                onStateChange(CalculatorPokemonState(repository.configFor(species, profile), "图鉴默认（0 加点）", profile.profileId))
                chooseSpecies = false
            },
        )
    }
    if (chooseItem) {
        EntitySearchDialog(
            title = "选择持有物",
            entities = repository.itemCatalog,
            onDismiss = { chooseItem = false },
            onSelect = { item ->
                onStateChange(state.copy(config = config.copy(item = item), sourceLabel = "手动配置", profileId = null))
                chooseItem = false
            },
        )
    }
    if (chooseMove) {
        MoveSearchDialog(
            title = "选择招式",
            moves = learnableMoves,
            repository = repository,
            onDismiss = { chooseMove = false },
            onSelect = { move ->
                onSelectMove(move.entity.showdownId)
                chooseMove = false
            },
        )
    }

    fun updateConfig(next: PokemonConfig) {
        onStateChange(state.copy(config = next, sourceLabel = "手动配置", profileId = null))
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            OutlinedButton(onClick = { chooseSpecies = true }, modifier = Modifier.fillMaxWidth()) {
                Text(config.species.displayName, modifier = Modifier.weight(1f))
                Text("更换")
            }

            if (isAttacker) {
                OutlinedButton(onClick = { chooseMove = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("招式：$selectedMoveName", modifier = Modifier.weight(1f))
                    Text("搜索")
                }
            }

            Text("性格修正（$statLabel）", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.9 to "×0.9", 1.0 to "×1.0", 1.1 to "×1.1").forEach { (value, label) ->
                    FilterChip(
                        selected = kotlin.math.abs(nature - value) < 0.001,
                        onClick = { onNatureChange(value) },
                        label = { Text(label) },
                    )
                }
            }

            StepperField("能力等级（$statLabel）", stage, -6, 6, Modifier.fillMaxWidth(), onStageChange)

            if (showHpPoints) {
                StepperField("生命点数", hpPoints, 0, 32, Modifier.fillMaxWidth(), onHpPoints)
                StepperField("$statLabel 点数", primaryPoints, 0, 32, Modifier.fillMaxWidth(), onPrimaryPoints)
            } else {
                StepperField("$statLabel 点数", primaryPoints, 0, 32, Modifier.fillMaxWidth(), onPrimaryPoints)
            }
            if (showSpeedPoints) {
                StepperField("速度点数（电球用）", speedPoints, 0, 32, Modifier.fillMaxWidth(), onSpeedPoints)
            }

            SimplePicker<EntityValue?>(
                label = "特性",
                options = listOf(null) + abilities,
                selected = config.ability,
                display = { it?.displayName ?: "无" },
                onSelect = { ability -> updateConfig(config.copy(ability = ability)) },
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { chooseItem = true }, modifier = Modifier.weight(1f)) {
                    Text("持有物：${config.item?.displayName ?: "无"}")
                }
                if (config.item != null) {
                    TextButton(onClick = { updateConfig(config.copy(item = null)) }) { Text("清除") }
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
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = { onChange((value - 1).coerceAtLeast(min)) }) { Text("−") }
        Text(
            if (value > 0) "+$value" else value.toString(),
            modifier = Modifier.widthIn(min = 28.dp),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = { onChange((value + 1).coerceAtMost(max)) }) { Text("+") }
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
            Text(reminder, color = Color(0xFFFFB74D), style = MaterialTheme.typography.bodySmall)
        }
        Text("实际能力值", fontWeight = FontWeight.SemiBold)
        StatEditor(state.actualStats) { onChange(state.copy(actualStats = it)) }
        Text("能力加点（可留空）", fontWeight = FontWeight.SemiBold)
        StatEditor(state.statPoints) { onChange(state.copy(statPoints = it)) }
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
    var query by rememberSaveable(title) { mutableStateOf("") }
    var sortModeName by rememberSaveable("$title-sort") { mutableStateOf(MoveSortMode.PINYIN.name) }
    val sortMode = MoveSortMode.valueOf(sortModeName)
    val filtered = remember(moves, query, sortMode) {
        val matches = moves.filter { it.matchesSearch(query) }
        sortMoves(matches, sortMode, repository::moveTypeFor)
    }
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
                    modifier = Modifier.fillMaxWidth(),
                )
                MoveSortSelector(sortMode) { sortModeName = it.name }
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
                if (filtered.isEmpty()) Text("没有找到匹配项", color = Color(0xFFFFB74D))
                else if (filtered.size > 100) Text("结果较多，请继续输入关键词。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun MoveSortSelector(selected: MoveSortMode, onSelect: (MoveSortMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("排序方式", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MoveSortMode.entries.forEach { mode ->
                FilterChip(
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                    label = { Text(mode.label) },
                )
            }
        }
    }
}

@Composable
private fun EntitySearchDialog(
    title: String,
    entities: List<EntityValue>,
    onDismiss: () -> Unit,
    onSelect: (EntityValue) -> Unit,
) {
    var query by rememberSaveable(title) { mutableStateOf("") }
    val filtered = remember(entities, query) {
        entities.filter { it.matchesSearch(query) }
    }
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
                    modifier = Modifier.fillMaxWidth(),
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
                if (filtered.isEmpty()) Text("没有找到匹配项", color = Color(0xFFFFB74D))
                else if (filtered.size > 100) Text("结果较多，请继续输入关键词。", style = MaterialTheme.typography.bodySmall)
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
        if (result.moves.isEmpty()) Text("没有可计算的招式，请重新选择。", color = Color(0xFFFFB74D))
        if (result.warnings.isNotEmpty()) {
            HorizontalDivider()
            Text("计算提示", fontWeight = FontWeight.Bold, color = Color(0xFFFFB74D))
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
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(display(selected), modifier = Modifier.weight(1f))
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
    Card(Modifier.fillMaxWidth()) {
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
            containerColor = if (ready) Color(0xFF153C36) else Color(0xFF3B3420),
        ),
    ) {
        Text(message, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

@Composable
private fun SettingsScreen(runtime: DamageEngineRuntime, teams: List<SavedTeam>) {
    val context = LocalContext.current
    val version = remember { installedVersion(context) }
    val releaseVariant = remember { AppReleaseVariant.fromBuildValue(BuildConfig.RELEASE_VARIANT) }
    val checker = remember { AppUpdateChecker() }
    var channel by remember { mutableStateOf(AppUpdatePreferences.loadChannel(context)) }
    var checking by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var presetTransferMessage by remember { mutableStateOf<String?>(null) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var pendingPresetImportUri by remember { mutableStateOf<Uri?>(null) }
    val exportBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            backupMessage = runCatching { AppDataBackup.exportTo(context, uri) }
                .fold(
                    onSuccess = {
                        "备份已导出：${it.teamCount} 支队伍、${it.userOpponentPresetCount} 个保存配置" +
                            if (it.hasBattleSession) "，含当前对局" else ""
                    },
                    onFailure = { "导出失败：${it.message ?: "未知错误"}" },
                )
        }
    }
    val selectBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingRestoreUri = uri
    }
    val exportUserPresets = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            presetTransferMessage = runCatching { OpponentPresetTransfer.exportTo(context, uri) }
                .fold(
                    onSuccess = { "配置分享文件已导出：$it 个保存配置" },
                    onFailure = { "配置导出失败：${it.message ?: "未知错误"}" },
                )
        }
    }
    val selectUserPresets = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingPresetImportUri = uri
    }

    DisposableEffect(checker) {
        onDispose(checker::close)
    }

    pendingRestoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text("恢复整包备份？") },
            text = { Text("恢复会先完整校验文件，然后用备份中的队伍、当前对局、保存的宝可梦配置和未完成导入草稿替换本机现有数据。旧版备份未包含保存配置时会保留本机现有配置；校验或写入失败时会回滚。") },
            confirmButton = {
                Button(onClick = {
                    pendingRestoreUri = null
                    backupMessage = runCatching { AppDataBackup.restoreFrom(context, uri) }
                        .fold(
                            onSuccess = {
                                CaptureUiState.teamLibraryRevision.value += 1
                                "恢复完成：${it.teamCount} 支队伍、${it.userOpponentPresetCount} 个保存配置" +
                                    if (it.hasBattleSession) "，含当前对局" else ""
                            },
                            onFailure = { "恢复失败，原数据已保留：${it.message ?: "未知错误"}" },
                        )
                }) { Text("校验并恢复") }
            },
            dismissButton = { TextButton(onClick = { pendingRestoreUri = null }) { Text("取消") } },
        )
    }

    pendingPresetImportUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingPresetImportUri = null },
            title = { Text("导入宝可梦配置？") },
            text = {
                Text("只会导入分享文件中的保存配置，不会改动队伍或当前对局。新配置会追加；相同配置 ID 会更新；本机其他配置会保留。")
            },
            confirmButton = {
                Button(onClick = {
                    pendingPresetImportUri = null
                    presetTransferMessage = runCatching { OpponentPresetTransfer.importFrom(context, uri) }
                        .fold(
                            onSuccess = {
                                "配置导入完成：新增 ${it.added} 个，更新 ${it.updated} 个，未变化 ${it.unchanged} 个"
                            },
                            onFailure = { "配置导入失败，原数据已保留：${it.message ?: "未知错误"}" },
                        )
                }) { Text("合并导入") }
            },
            dismissButton = {
                TextButton(onClick = { pendingPresetImportUri = null }) { Text("取消") }
            },
        )
    }

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            updateResult = UpdateCheckResult.Failure("无法打开浏览器，请检查系统默认应用设置。")
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("设置与诊断", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        SectionCard("应用版本") {
            Text(
                "当前版本：${version.name}（${version.code}）· ${releaseVariantLabel(releaseVariant)}",
                fontWeight = FontWeight.Bold,
            )
            Text("发布源：${AppUpdateConfig.REPOSITORY}", style = MaterialTheme.typography.bodySmall)
            SimplePicker(
                label = "更新频道",
                options = UpdateChannel.entries,
                selected = channel,
                display = ::updateChannelLabel,
            ) { selected ->
                channel = selected
                updateResult = null
                AppUpdatePreferences.saveChannel(context, selected)
            }
            Text(
                if (channel == UpdateChannel.STABLE) {
                    "稳定版只接收正式 Release，适合日常使用。"
                } else {
                    "预览版也会接收标记为 Pre-release 的测试版本，可能不稳定。"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = {
                    val requestedChannel = channel
                    checking = true
                    updateResult = null
                    checker.check(version.name, requestedChannel) { result ->
                        checking = false
                        if (channel == requestedChannel) updateResult = result
                    }
                },
                enabled = !checking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (checking) "正在连接 GitHub…" else "检查更新")
            }
            OutlinedButton(
                onClick = { openUrl(AppUpdateConfig.RELEASES_PAGE_URL) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("打开 GitHub 发布页")
            }
            updateResult?.let { result ->
                UpdateResultContent(result, releaseVariant, ::openUrl)
            }
        }
        SectionCard("本地状态") {
            StatusCard(runtime.status, runtime.status.contains("已就绪"))
            Text("队伍资产：${teams.size} 份")
            Text("引擎信息：${runtime.engineInfo.ifBlank { "尚未读取" }}", style = MaterialTheme.typography.bodySmall)
        }
        SectionCard("数据备份与恢复") {
            Text("系统加密备份和设备迁移已启用；也可以主动导出一份不含截图的 JSON 整包备份。整包包含队伍、当前对局和所有保存的宝可梦配置。")
            Button(
                onClick = { exportBackup.launch("pokemon-champions-backup-${java.time.LocalDate.now()}.json") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("导出我的数据") }
            OutlinedButton(
                onClick = { selectBackup.launch(arrayOf("application/json", "text/json", "text/plain")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("导入我的数据") }
            backupMessage?.let {
                Text(it, color = if (it.startsWith("恢复失败") || it.startsWith("导出失败")) MaterialTheme.colorScheme.error else Color(0xFF80CBC4))
            }
        }
        SectionCard("宝可梦配置分享") {
            Text("只导出或合并导入在悬浮面板中保存的宝可梦配置，方便用户之间分享；不会包含队伍、当前对局或截图。")
            Button(
                onClick = {
                    exportUserPresets.launch(
                        "pokemon-champions-presets-${java.time.LocalDate.now()}.json",
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("只导出保存的配置") }
            OutlinedButton(
                onClick = {
                    selectUserPresets.launch(arrayOf("application/json", "text/json", "text/plain"))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("只导入保存的配置") }
            presetTransferMessage?.let {
                Text(
                    it,
                    color = if (it.startsWith("配置导出失败") || it.startsWith("配置导入失败")) {
                        MaterialTheme.colorScheme.error
                    } else {
                        Color(0xFF80CBC4)
                    },
                )
            }
        }
        Text(
            "App 自身只在手动检查更新时访问 GitHub，不主动上传截图、队伍或计算输入；若系统备份已开启，Android 可加密同步所选 JSON 数据。APK 下载由系统浏览器处理。",
            color = Color(0xFF80CBC4),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun UpdateResultContent(
    result: UpdateCheckResult,
    preferredVariant: AppReleaseVariant,
    openUrl: (String) -> Unit,
) {
    HorizontalDivider()
    when (result) {
        is UpdateCheckResult.Available -> {
            Text("发现新版本 ${result.release.tagName}", fontWeight = FontWeight.Bold, color = Color(0xFFFFD54F))
            Text(result.release.title)
            releaseNotesPreview(result.release.notes)?.let { notes ->
                Text(notes, style = MaterialTheme.typography.bodySmall)
            }
            ReleaseDownloadButtons(result.release, preferredVariant, openUrl)
            OutlinedButton(onClick = { openUrl(result.release.pageUrl) }, modifier = Modifier.fillMaxWidth()) {
                Text("查看版本说明")
            }
        }
        is UpdateCheckResult.Current -> {
            Text("当前已经是此频道的最新版本（${result.release.tagName}）。", color = Color(0xFF80CBC4))
            Text("如需切换版本，可在下方重新选择安装包。", style = MaterialTheme.typography.bodySmall)
            ReleaseDownloadButtons(result.release, preferredVariant, openUrl)
            OutlinedButton(onClick = { openUrl(result.release.pageUrl) }, modifier = Modifier.fillMaxWidth()) {
                Text("查看版本说明")
            }
        }
        is UpdateCheckResult.NoRelease -> Text(result.message, color = Color(0xFFFFB74D))
        is UpdateCheckResult.Failure -> Text(result.message, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ReleaseDownloadButtons(
    release: ReleaseInfo,
    preferredVariant: AppReleaseVariant,
    openUrl: (String) -> Unit,
) {
    val standard = release.standardApkUrl?.let {
        Triple(AppReleaseVariant.STANDARD, "下载标准版", it)
    }
    val replay = release.replayApkUrl?.let {
        Triple(AppReleaseVariant.REPLAY, "下载录屏功能版（Android 16）", it)
    }
    val downloads = when (preferredVariant) {
        AppReleaseVariant.STANDARD -> listOfNotNull(standard, replay)
        AppReleaseVariant.REPLAY -> listOfNotNull(replay, standard)
    }

    downloads.forEachIndexed { index, (variant, label, url) ->
        val text = if (variant == preferredVariant) "$label（当前版本默认）" else label
        if (index == 0) {
            Button(onClick = { openUrl(url) }, modifier = Modifier.fillMaxWidth()) { Text(text) }
        } else {
            OutlinedButton(onClick = { openUrl(url) }, modifier = Modifier.fillMaxWidth()) { Text(text) }
        }
    }
    if (downloads.isEmpty()) {
        Text("这个 Release 没有附带 APK，请从发布页查看文件。", style = MaterialTheme.typography.bodySmall)
    }
}

private fun releaseVariantLabel(variant: AppReleaseVariant): String = when (variant) {
    AppReleaseVariant.STANDARD -> "标准版"
    AppReleaseVariant.REPLAY -> "录屏功能版"
}

private fun updateChannelLabel(channel: UpdateChannel): String = when (channel) {
    UpdateChannel.STABLE -> "稳定版"
    UpdateChannel.PREVIEW -> "预览版"
}

private fun releaseNotesPreview(notes: String): String? {
    val normalized = notes.trim().takeIf(String::isNotBlank) ?: return null
    return if (normalized.length <= 600) normalized else "${normalized.take(600)}…"
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
    Card(Modifier.fillMaxWidth()) {
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
                                    .background(Color(0xFF1E2736), RoundedCornerShape(8.dp))
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

