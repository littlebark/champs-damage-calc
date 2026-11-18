package com.bark.championsassistant

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import kotlin.math.roundToInt
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.core.util.Consumer
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

object CaptureUiState {
    val message = mutableStateOf("对局助手尚未启动")
    val running = mutableStateOf(false)
    val teamLibraryRevision = mutableStateOf(0)
    val ownTeamDraftRevision = mutableStateOf(0)
}

class OverlayCaptureService : Service() {
    companion object {
        private const val ACTION_START = "com.bark.championsassistant.START_CAPTURE"
        private const val ACTION_STOP = "com.bark.championsassistant.STOP_CAPTURE"
        private const val ACTION_OPEN_OWN_TEAM_CORRECTION =
            "com.bark.championsassistant.OPEN_OWN_TEAM_CORRECTION"
        private const val ACTION_COMPUTE_SNAPSHOT =
            "com.bark.championsassistant.COMPUTE_SNAPSHOT"
        private const val ACTION_QUICK_SESSION =
            "com.bark.championsassistant.QUICK_SESSION"
        private const val EXTRA_QUICK_TEAM_ID = "quick_team_id"
        private const val EXTRA_QUICK_OPPONENT_NAME = "quick_opponent_name"
        private const val EXTRA_QUICK_OPPONENT_SHOWDOWN = "quick_opponent_showdown"
        private const val EXTRA_QUICK_OPPONENT_CANONICAL = "quick_opponent_canonical"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_ASSISTANT_MODE = "assistant_mode"
        private const val CHANNEL_ID = "own_team_capture"
        private const val NOTIFICATION_ID = 4102
        private const val LOG_TAG = "OverlayCaptureService"
        /** 选招页固定区域的最小画面宽度：低于这个值区域会缩到看不清，改走整屏检测。 */
        private const val SNAPSHOT_ROI_MIN_WIDTH = 1200
        /** 形态识别第一版先留一份裁下来的头像在 files/debug-icons，方便核对 ROI。 */
        private const val DEBUG_ICON_DIR = true
        /** 选招页固定区域（距左 / 距右 / 上 / 下）：对手名牌、我方名牌、天气场地、四招。 */
        private val SNAPSHOT_REGIONS = listOf(
            SnapshotRegion(0.772f, 0.110f, 0.042f, 0.092f),
            SnapshotRegion(0.118f, 0.790f, 0.894f, 0.946f),
            SnapshotRegion(0.196f, 0.688f, 0.046f, 0.094f),
            SnapshotRegion(0.752f, 0.124f, 0.492f, 0.548f),
            SnapshotRegion(0.752f, 0.124f, 0.612f, 0.666f),
            SnapshotRegion(0.752f, 0.124f, 0.730f, 0.784f),
            SnapshotRegion(0.752f, 0.124f, 0.848f, 0.906f),
        )
        /** 性别徽章区域，只做颜色判断（蓝底 ♂ / 粉红底 ♀），不送 OCR。 */
        private val SNAPSHOT_OWN_GENDER_BADGE = SnapshotRegion(0.2215f, 0.7605f, 0.899f, 0.934f)
        private val SNAPSHOT_OPPONENT_GENDER_BADGE = SnapshotRegion(0.898f, 0.072f, 0.052f, 0.086f)
        /**
         * 名牌左侧的小头像（连同一圈底板）：形态识别只看这两块。
         * 对手在右上、我方在左下，底板左缘会随名字长度浮动，框留了余量，靠底板色把本体抠出来。
         */
        private val SNAPSHOT_OPPONENT_ICON = SnapshotRegion(0.745f, 0.185f, 0.020f, 0.175f)
        private val SNAPSHOT_OWN_ICON = SnapshotRegion(0.038f, 0.900f, 0.845f, 0.970f)
        private const val CAPTURE_RESIZE_DEBOUNCE_MS = 150L
        private const val OWN_TEAM_SLOW_NOTICE_MS = 30_000L
        private const val OWN_TEAM_TIMEOUT_MS = 90_000L

        fun start(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            mode: BattleAssistantMode = BattleAssistantMode.STANDARD,
        ) {
            val intent = Intent(context, OverlayCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_ASSISTANT_MODE, mode.wireName)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, OverlayCaptureService::class.java).setAction(ACTION_STOP))
        }

        fun requestOwnTeamCorrection(context: Context) {
            context.startService(
                Intent(context, OverlayCaptureService::class.java).setAction(ACTION_OPEN_OWN_TEAM_CORRECTION),
            )
        }

        /** 选招页一键计算：不需要先识别阵容。 */
        fun computeSnapshot(context: Context) {
            context.startService(
                Intent(context, OverlayCaptureService::class.java).setAction(ACTION_COMPUTE_SNAPSHOT),
            )
        }

        /** 不识别阵容也能开局：用已录入队伍 + 当前对手直接建立对局。 */
        fun startQuickSession(context: Context, teamId: String?, opponent: EntityValue) {
            val intent = Intent(context, OverlayCaptureService::class.java).apply {
                action = ACTION_QUICK_SESSION
                putExtra(EXTRA_QUICK_TEAM_ID, teamId)
                putExtra(EXTRA_QUICK_OPPONENT_NAME, opponent.displayName)
                putExtra(EXTRA_QUICK_OPPONENT_SHOWDOWN, opponent.showdownId)
                putExtra(EXTRA_QUICK_OPPONENT_CANONICAL, opponent.canonicalId)
            }
            runCatching { context.startService(intent) }
                .onFailure { CaptureUiState.message.value = "请先启动对局助手，再开始快速对局" }
        }
    }

    private val mainHandler = Handler(android.os.Looper.getMainLooper())
    private val immediateOverlaySafeAreaRefresh = Runnable { onOverlaySafeAreaChanged() }
    private val settledOverlaySafeAreaRefresh = Runnable { onOverlaySafeAreaChanged() }
    private lateinit var overlayWindowContext: Context
    private lateinit var displayManager: DisplayManager
    private lateinit var windowManager: WindowManager
    private lateinit var safeArea: OverlaySafeAreaProvider
    private var overlayDisplayId = Display.DEFAULT_DISPLAY
    private var windowInfoTracker: WindowInfoTrackerCallbackAdapter? = null
    private val overlayComponentCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            scheduleOverlaySafeAreaRefresh()
        }

        override fun onLowMemory() = Unit
    }
    private val windowLayoutInfoListener = Consumer<WindowLayoutInfo> { layoutInfo ->
        if (destroyed || !::safeArea.isInitialized) return@Consumer
        safeArea.updateWindowLayoutInfo(layoutInfo)
        scheduleOverlaySafeAreaRefresh()
    }
    private val overlayDisplayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != overlayDisplayId || destroyed) return
            scheduleCaptureResize(reason = "displayChanged")
            scheduleOverlaySafeAreaRefresh()
        }
    }
    private lateinit var ocrEngine: OwnTeamOcrEngine
    private var paddleOcr: PaddleOcrEngine? = null
    private val ocrVocabulary: EntityCatalog by lazy { EntityCatalog(this) }
    private lateinit var importRepository: OwnTeamImportRepository
    private lateinit var presetRepository: OpponentPresetRepository
    private lateinit var damageRuntime: DamageEngineRuntime
    private lateinit var battleOverlayController: BattleOverlayController
    private lateinit var ownTeamCorrectionController: OwnTeamCorrectionOverlayController
    @Volatile private var destroyed = false
    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var ownMegaChip: TextView? = null
    private var opponentMegaChip: TextView? = null
    private var ownStageChip: TextView? = null
    private var opponentStageChip: TextView? = null
    private var teamNamePrompt: View? = null
    private var teamNamePromptParams: WindowManager.LayoutParams? = null
    private var activeToast: Toast? = null
    private var statusChip: TextView? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null
    private var captureBufferSpec: CaptureBufferSpec? = null
    private var pendingCaptureResize: Runnable? = null
    private var pendingCapturedContentSize: Pair<Int, Int>? = null
    private var pendingFrameCapture: CancelableDelayedTask? = null
    @Volatile private var captureGeneration = 0L
    private val bitmapLock = Any()
    private var latestBitmap: Bitmap? = null
    private var frozenMenuBitmap: Bitmap? = null
    private var frozenMenuFrameCopyMs = 0.0
    @Volatile private var frameTrackingEnabled = true
    @Volatile private var recognizing = false
    private var ownTeamRecognitionGeneration = 0L
    private var ownTeamRecognitionTask: Future<*>? = null
    private var pendingOwnTeamSlowNotice: Runnable? = null
    private var pendingOwnTeamTimeout: Runnable? = null
    private var assistantMode = BattleAssistantMode.STANDARD

    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(DisplayManager::class.java)
        val overlayDisplay = displayManager
            .getDisplay(Display.DEFAULT_DISPLAY)
            ?: error("Default display is unavailable for application overlays")
        overlayDisplayId = overlayDisplay.displayId
        overlayWindowContext = createDisplayContext(overlayDisplay).createWindowContext(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            null,
        )
        windowManager = overlayWindowContext.getSystemService(WindowManager::class.java)
        safeArea = OverlaySafeAreaProvider(overlayWindowContext)
        overlayWindowContext.registerComponentCallbacks(overlayComponentCallbacks)
        displayManager.registerDisplayListener(overlayDisplayListener, mainHandler)
        WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(this)).also { tracker ->
            windowInfoTracker = tracker
            runCatching {
                tracker.addWindowLayoutInfoListener(
                    overlayWindowContext,
                    mainExecutor,
                    windowLayoutInfoListener,
                )
            }.onFailure { error ->
                Log.w(LOG_TAG, "Fold posture updates are unavailable; window metrics remain active", error)
            }
        }
        paddleOcr = runCatching { PaddleOcrEngine(this) }.getOrElse { error ->
            Log.e(LOG_TAG, "PaddleOCR could not be loaded", error)
            null
        }
        ocrEngine = OwnTeamOcrEngine(this, paddleOcr)
        importRepository = OwnTeamImportRepository(this)
        damageRuntime = DamageEngineRuntime(this)
        presetRepository = OpponentPresetRepository(this)
        val battleSessionRepository = BattleSessionRepository(this)
        battleOverlayController = BattleOverlayController(
            context = overlayWindowContext,
            windowManager = windowManager,
            safeArea = safeArea,
            runtime = damageRuntime,
            sessionRepository = battleSessionRepository,
            presetRepository = presetRepository,
            publish = ::publish,
            onOverlayVisible = { visible ->
                if (visible) {
                    // 红框 HUD 出现时保留悬浮球：一回合打完后要能马上重算下一次。
                    if (assistantMode.usesFloatingBubble) showBubble()
                } else {
                    if (projection == null) stopSelf() else setBubbleWindowPresent(true)
                }
            },
            shouldAutoOpenDirectHud = { assistantMode.autoOpenDirectHud },
            // The direct HUD has no PopupMenu phase and therefore no frozen menu frame.
            // Wait for a clean live frame after the HUD windows are dismissed instead.
            onRecognizeOwnTeam = ::captureAndRecognizeOwnTeam,
            onRecalculateFromScreen = ::captureAndComputeSnapshot,
        )
        ownTeamCorrectionController = OwnTeamCorrectionOverlayController(
            context = overlayWindowContext,
            windowManager = windowManager,
            safeArea = safeArea,
            importRepository = importRepository,
            presetRepository = presetRepository,
            publish = ::publish,
            onOverlayVisible = { visible ->
                setBubbleWindowPresent(!visible)
            },
            onSaved = { saved ->
                CaptureUiState.ownTeamDraftRevision.value += 1
                CaptureUiState.teamLibraryRevision.value += 1
                updateOwnTeamRecognitionState(OwnTeamRecognitionHudState())
                publish(saved.message)
            },
            onDiscarded = {
                CaptureUiState.ownTeamDraftRevision.value += 1
                updateOwnTeamRecognitionState(OwnTeamRecognitionHudState())
                publish("已放弃本次队伍识别，可以重新开始")
            },
        )
        refreshOwnTeamRecognitionState()
        // 每次启动助手都是新一轮对局：把上一局留在偏好里的 Mega / 形态 / 能力等级意图清掉，
        // 否则一打开 App「我方MEGA」就是亮的（那是上一局点的）。
        battleOverlayController.resetPendingBattleIntents()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStop()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_COMPUTE_SNAPSHOT) {
            captureAndComputeSnapshot()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_QUICK_SESSION) {
            val teamId = intent.getStringExtra(EXTRA_QUICK_TEAM_ID)
            val name = intent.getStringExtra(EXTRA_QUICK_OPPONENT_NAME).orEmpty()
            val showdown = intent.getStringExtra(EXTRA_QUICK_OPPONENT_SHOWDOWN).orEmpty()
            val canonical = intent.getStringExtra(EXTRA_QUICK_OPPONENT_CANONICAL).orEmpty()
            if (name.isBlank() || showdown.isBlank()) {
                publish("请先选择对手宝可梦")
            } else {
                battleOverlayController.startQuickSession(
                    teamId,
                    EntityValue(
                        canonicalId = canonical,
                        showdownId = showdown,
                        displayName = name,
                        entityType = "species",
                    ),
                )
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_OPEN_OWN_TEAM_CORRECTION) {
            openOwnTeamCorrection()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        assistantMode = BattleAssistantMode.fromWireName(intent.getStringExtra(EXTRA_ASSISTANT_MODE))
        startProjectionForeground()
        if (!Settings.canDrawOverlays(this)) {
            publish("请先在 App 中授予悬浮窗权限")
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            publish("屏幕共享授权已失效，请返回 App 重新启动")
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching {
            startProjection(resultCode, resultData)
            if (assistantMode.autoOpenDirectHud) battleOverlayController.resetForAssistantLaunch()
        }
            .onSuccess {
                showAssistantEntry()
                CaptureUiState.running.value = true
                publish(
                    if (assistantMode.usesFloatingBubble) {
                        "对局助手已启动；打开 Pokémon Champions 后点击悬浮按钮"
                    } else {
                        "HUD 对局助手已启动；请在 HUD 中使用“再战”识别双方阵容"
                    },
                )
            }
            .onFailure {
                Log.e(LOG_TAG, "Could not start capture projection", it)
                publish("对局助手启动失败，请返回 App 后重试")
                stopSelf()
            }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        scheduleCaptureResize(reason = "configurationChanged")
        scheduleOverlaySafeAreaRefresh()
    }

    private fun startProjectionForeground() {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, OverlayCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Pokémon Champions 对局助手")
            .setContentText("仅在点击悬浮按钮或 HUD 识别按钮后读取游戏画面；数据保存在本机")
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "结束助手", stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startProjection(resultCode: Int, resultData: Intent) {
        releaseProjection()
        val bounds = windowManager.currentWindowMetrics.bounds
        val initialSpec = changedCaptureBufferSpec(
            current = null,
            width = bounds.width(),
            height = bounds.height(),
            densityDpi = resources.displayMetrics.densityDpi,
            rotation = currentCaptureRotation(),
        ) ?: error("当前屏幕尺寸无效：${bounds.width()}×${bounds.height()}")
        val manager = getSystemService(MediaProjectionManager::class.java)
        val activeProjection = manager.getMediaProjection(resultCode, resultData)
            ?: error("MediaProjectionManager 未返回有效会话")
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                publish("系统已停止屏幕共享，对局助手即将结束")
                requestStop()
            }

            override fun onCapturedContentResize(width: Int, height: Int) {
                scheduleCaptureResize(width, height, "capturedContentResize")
            }
        }
        activeProjection.registerCallback(callback, mainHandler)
        val thread = HandlerThread("capture-frames").apply { start() }
        val handler = Handler(thread.looper)
        val generation = captureGeneration + 1
        val reader = createImageReader(initialSpec, handler, generation)
        val display = activeProjection.createVirtualDisplay(
            "pokemon-champions-own-team",
            initialSpec.virtualDisplayWidth,
            initialSpec.virtualDisplayHeight,
            initialSpec.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        ) ?: error("MediaProjection 未返回有效虚拟显示")
        applyVirtualDisplayRotation(display, initialSpec.rotation)
        projection = activeProjection
        projectionCallback = callback
        imageReader = reader
        imageThread = thread
        imageHandler = handler
        virtualDisplay = display
        captureBufferSpec = initialSpec
        captureGeneration = generation
        frameTrackingEnabled = true
        Log.i(LOG_TAG, "Capture surface ready: $initialSpec, generation=$generation")
        publish("对局助手已就绪")
    }

    private fun createImageReader(
        spec: CaptureBufferSpec,
        handler: Handler,
        generation: Long,
    ): ImageReader = ImageReader.newInstance(
        spec.virtualDisplayWidth,
        spec.virtualDisplayHeight,
        PixelFormat.RGBA_8888,
        2,
    ).also { reader ->
        reader.setOnImageAvailableListener(
            {
                source -> updateLatestFrame(
                    source,
                    spec.virtualDisplayWidth,
                    spec.virtualDisplayHeight,
                    generation,
                )
            },
            handler,
        )
    }

    private fun scheduleCaptureResize(
        width: Int? = null,
        height: Int? = null,
        reason: String,
    ) {
        if (width != null && width > 0 && height != null && height > 0) {
            pendingCapturedContentSize = width to height
        }
        pendingCaptureResize?.let(mainHandler::removeCallbacks)
        val request = Runnable {
            pendingCaptureResize = null
            val bounds = windowManager.currentWindowMetrics.bounds
            val capturedContentSize = pendingCapturedContentSize.also {
                pendingCapturedContentSize = null
            }
            resizeCaptureSurface(
                width = capturedContentSize?.first ?: bounds.width(),
                height = capturedContentSize?.second ?: bounds.height(),
                densityDpi = resources.displayMetrics.densityDpi,
                rotation = currentCaptureRotation(),
                reason = reason,
            )
        }
        pendingCaptureResize = request
        mainHandler.postDelayed(request, CAPTURE_RESIZE_DEBOUNCE_MS)
    }

    private fun resizeCaptureSurface(
        width: Int,
        height: Int,
        densityDpi: Int,
        rotation: Int,
        reason: String,
    ) {
        val handler = imageHandler ?: return
        handler.post {
            val currentSpec = captureBufferSpec ?: return@post
            val nextSpec = changedCaptureBufferSpec(
                currentSpec,
                width,
                height,
                densityDpi,
                rotation,
            ) ?: return@post
            val display = virtualDisplay ?: return@post
            val oldReader = imageReader ?: return@post
            val nextGeneration = captureGeneration + 1
            val nextReader = runCatching { createImageReader(nextSpec, handler, nextGeneration) }
                .getOrElse { error ->
                    Log.e(LOG_TAG, "Could not create capture surface for $nextSpec", error)
                    publish("画面方向变化后暂时无法继续识别，请结束并重新启动对局助手")
                    return@post
                }

            runCatching {
                // ImageReader callbacks and Surface replacement share this looper. Closing an
                // ImageReader from the main thread while copyPixelsFromBuffer() is still reading
                // one of its planes can crash in native memcpy on Android 16.
                display.resize(
                    nextSpec.virtualDisplayWidth,
                    nextSpec.virtualDisplayHeight,
                    nextSpec.densityDpi,
                )
                applyVirtualDisplayRotation(display, nextSpec.rotation)
                display.setSurface(nextReader.surface)
            }.onSuccess {
                captureGeneration = nextGeneration
                captureBufferSpec = nextSpec
                imageReader = nextReader
                oldReader.setOnImageAvailableListener(null, null)
                oldReader.close()
                synchronized(bitmapLock) {
                    latestBitmap?.recycle()
                    latestBitmap = null
                    frozenMenuBitmap?.recycle()
                    frozenMenuBitmap = null
                    frameTrackingEnabled = true
                }
                Log.i(
                    LOG_TAG,
                    "Capture surface resized: $currentSpec -> $nextSpec, generation=$nextGeneration, reason=$reason",
                )
                mainHandler.post {
                    if (destroyed) return@post
                    CaptureUiState.message.value = "画面方向已调整，可以继续识别"
                }
            }.onFailure { error ->
                nextReader.setOnImageAvailableListener(null, null)
                runCatching {
                    display.resize(
                        currentSpec.virtualDisplayWidth,
                        currentSpec.virtualDisplayHeight,
                        currentSpec.densityDpi,
                    )
                    applyVirtualDisplayRotation(display, currentSpec.rotation)
                    display.setSurface(oldReader.surface)
                }
                nextReader.close()
                Log.e(LOG_TAG, "Could not resize capture surface from $currentSpec to $nextSpec", error)
                publish("画面方向变化后暂时无法继续识别，请结束并重新启动对局助手")
            }
        }
    }

    private fun currentCaptureRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY)
                ?.rotation
                ?: Surface.ROTATION_0
        } else {
            // VirtualDisplay.setRotation() is unavailable before Android 16.
            // Preserve the existing size-only behavior on older releases.
            Surface.ROTATION_0
        }

    private fun applyVirtualDisplayRotation(display: VirtualDisplay, rotation: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            display.setRotation(rotation)
        }
    }

    private fun updateLatestFrame(
        reader: ImageReader,
        width: Int,
        height: Int,
        generation: Long,
    ) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        try {
            if (!frameTrackingEnabled || generation != captureGeneration) return
            val plane = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * width
            val paddedWidth = width + rowPadding / plane.pixelStride
            synchronized(bitmapLock) {
                if (!frameTrackingEnabled || generation != captureGeneration) return
                var reusable = latestBitmap
                if (reusable == null || reusable.width != paddedWidth || reusable.height != height) {
                    reusable?.recycle()
                    reusable = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                    latestBitmap = reusable
                }
                plane.buffer.rewind()
                reusable.copyPixelsFromBuffer(plane.buffer)
            }
        } catch (_: IllegalStateException) {
            // The reader can invalidate an already-acquired frame while a projection is stopping.
            // A later callback will supply a fresh frame when the session remains active.
        } finally {
            runCatching { image.close() }
        }
    }

    /**
     * 常驻紧凑十字键：上（计算伤害）/下（关闭伤害）/左（识别队伍）/右（识别当前），
     * 中间只留一个很小的拖动手柄，整体按游戏手柄十字键排布，尽量少占画面。
     * 不再用 ListPopupWindow 弹列表，避免横屏右侧余量不足时文字被裁。
     */
    private fun showBubble() {
        if (destroyed || !assistantMode.usesFloatingBubble || bubble != null) return
        val density = overlayWindowContext.resources.displayMetrics.density
        fun px(value: Int) = (value * density).toInt()

        val buttonWidth = px(64)
        val buttonHeight = px(42)
        val ballSize = px(26)
        val containerWidth = px(160)
        // 整块高度按内容收紧：顶排 chip 2~32、计算/关闭 36~78、识别 82~108、能力等级 116~142，
        // 拖动球塞进计算/关闭中间那道 26dp 的空隙，所以底部那片空白也没了。
        val containerHeight = px(142)
        val chipWidth = px(76)
        val chipHeight = px(30)
        // 顶排三枚挤在 160dp 里，各 52dp，字缩到 9.5sp。
        val topChipWidth = px(52)
        val stageChipHeight = px(26)

        fun actionButton(
            label: String,
            primary: Boolean,
            small: Boolean = false,
            onClick: () -> Unit,
        ): TextView =
            TextView(overlayWindowContext).apply {
                text = label
                textSize = if (small) 10f else 13f
                maxLines = if (small) 1 else 2
                setLineSpacing(px(1).toFloat(), 1f)
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                includeFontPadding = false
                setTextColor(if (primary) Color.WHITE else Color.rgb(232, 244, 238))
                background = GradientDrawable().apply {
                    cornerRadius = px(if (small) 8 else 12).toFloat()
                    if (primary) {
                        setColor(Color.argb(236, 16, 150, 108))
                        setStroke(px(2), Color.argb(255, 118, 246, 200))
                    } else {
                        setColor(Color.argb(196, 24, 42, 36))
                        setStroke(px(1), Color.argb(150, 176, 214, 198))
                    }
                }
                setOnClickListener { onClick() }
            }

        val container = FrameLayout(overlayWindowContext).apply { clipChildren = false }

        // 顶排两枚：我方MEGA / 对方MEGA。玩家点任何一枚，都会先快速读一次对手名牌，
        // 两枚共用同一次读取结果——chip 只表示状态，真正套用仍然在「计算伤害」。
        // 形态不占 chip：由「计算伤害」前的头像识别决定（坚盾剑怪另有双行补算）。
        fun megaChip(onClick: () -> Unit): TextView = TextView(overlayWindowContext).apply {
            textSize = 9.5f
            maxLines = 1
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
            setOnClickListener { onClick() }
        }
        val ownChip = megaChip { onOwnMegaChip() }
        val opponentChip = megaChip { onOpponentMegaChip() }
        ownMegaChip = ownChip
        opponentMegaChip = opponentChip
        container.addView(
            ownChip,
            FrameLayout.LayoutParams(topChipWidth, chipHeight, Gravity.TOP or Gravity.START).apply {
                topMargin = px(2)
            },
        )
        container.addView(
            opponentChip,
            FrameLayout.LayoutParams(topChipWidth, chipHeight, Gravity.TOP or Gravity.END).apply {
                topMargin = px(2)
            },
        )
        refreshMegaChips()

        // 能力等级：最下方左右各一个，默认 0，点一下弹出加减面板。
        val ownStage = megaChip { onStageChip(own = true) }
        val opponentStage = megaChip { onStageChip(own = false) }
        ownStageChip = ownStage
        opponentStageChip = opponentStage
        container.addView(
            ownStage,
            FrameLayout.LayoutParams(chipWidth, stageChipHeight, Gravity.BOTTOM or Gravity.START),
        )
        container.addView(
            opponentStage,
            FrameLayout.LayoutParams(chipWidth, stageChipHeight, Gravity.BOTTOM or Gravity.END),
        )
        refreshStageChips()

        // 「计算伤害」和「关闭伤害」同一行、同样大小，中间留出 26dp 放拖动球：
        // 左边算、中间拖、右边关。
        container.addView(
            actionButton("计算\n伤害", primary = true) { captureAndComputeSnapshot() },
            FrameLayout.LayoutParams(buttonWidth, buttonHeight, Gravity.TOP or Gravity.START).apply {
                topMargin = px(36)
                leftMargin = px(3)
            },
        )
        container.addView(
            actionButton("关闭\n伤害", primary = false) {
                battleOverlayController.hideDirectHud()
                showStatusChip("已关闭伤害 HUD")
            },
            FrameLayout.LayoutParams(buttonWidth, buttonHeight, Gravity.TOP or Gravity.END).apply {
                topMargin = px(36)
                rightMargin = px(3)
            },
        )
        // 「识别队伍 / 识别当前」用得少，缩到和能力等级 chip 一样的小块。
        container.addView(
            actionButton("识别队伍", primary = false, small = true) { captureAndRecognizeOwnTeam() },
            FrameLayout.LayoutParams(chipWidth, stageChipHeight, Gravity.TOP or Gravity.START).apply {
                topMargin = px(82)
            },
        )
        container.addView(
            actionButton("识别当前", primary = false, small = true) { captureAndRecordOwnStats() },
            FrameLayout.LayoutParams(chipWidth, stageChipHeight, Gravity.TOP or Gravity.END).apply {
                topMargin = px(82)
            },
        )

        val ball = TextView(overlayWindowContext).apply {
            text = "✛"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.argb(230, 236, 245, 238))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(150, 24, 42, 36))
                setStroke(px(1), Color.argb(120, 200, 226, 210))
            }
            elevation = 6f
        }
        // 拖动球就停在「计算/关闭」中间那道缝里（36~78 那一行，球 26dp 居中 → 上边距 44）。
        container.addView(
            ball,
            FrameLayout.LayoutParams(ballSize, ballSize, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = px(44)
            },
        )

        val params = WindowManager.LayoutParams(
            containerWidth,
            containerHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val margin = px(16)
            val bounds = safeArea.currentRegion(preferEnd = false)
            x = (bounds.left + margin).coerceAtMost((bounds.right - containerWidth).coerceAtLeast(bounds.left))
            y = (bounds.top + bounds.height / 3)
                .coerceIn(bounds.top, (bounds.bottom - containerHeight).coerceAtLeast(bounds.top))
            bubbleParams?.let { previous ->
                val remembered = safeArea.clampPosition(previous.x, previous.y, containerWidth, containerHeight)
                x = remembered.x
                y = remembered.y
            }
        }

        var downX = 0f
        var downY = 0f
        var originalX = 0
        var originalY = 0
        ball.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    originalX = params.x
                    originalY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val position = safeArea.clampPosition(
                        proposedX = (originalX + event.rawX - downX).toInt(),
                        proposedY = (originalY + event.rawY - downY).toInt(),
                        width = params.width,
                        height = params.height,
                    )
                    params.x = position.x
                    params.y = position.y
                    windowManager.updateViewLayout(container, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // 只保留拖动；原来的「长按结束对局助手」和拖动太容易混，已取消，
                    // 结束助手统一走首页的按钮。
                    true
                }
                else -> false
            }
        }
        windowManager.addView(container, params)
        bubble = container
        bubbleParams = params
    }

    private fun removeBubble() {
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        ownMegaChip = null
        opponentMegaChip = null
        ownStageChip = null
        opponentStageChip = null
    }

    /** 我方只开/关：形态由持有物决定，不用每次点都选。 */
    private fun onOwnMegaChip() {
        battleOverlayController.toggleOwnMega()
        refreshMegaChips()
    }

    /**
     * 对手无法推断（不知道道具、也可能 X 也可能 Y），所以只让玩家选「哪一类」：
     * 关闭 / 普通 Mega / X / Y / Z。点之前先快速读一次对手名牌，把会话里的对手换成屏幕上这只，
     * 免得菜单里那些「家族里有没有这个形态」的判断还落在上一局的物种上。
     */
    private fun onOpponentMegaChip() {
        readOpponentQuickly { species ->
            if (species != null) {
                battleOverlayController.updateOpponentIdentity(species)
                refreshMegaChips()
            }
            showOpponentMegaMenu(opponentKnown = species != null)
        }
    }

    private fun showOpponentMegaMenu(opponentKnown: Boolean) {
        val anchor = opponentMegaChip ?: bubble ?: return
        val density = overlayWindowContext.resources.displayMetrics.density
        fun px(value: Int) = (value * density).toInt()

        val panel = LinearLayout(overlayWindowContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(6), px(6), px(6), px(6))
            background = GradientDrawable().apply {
                cornerRadius = px(12).toFloat()
                setColor(Color.argb(238, 24, 42, 36))
                setStroke(px(1), Color.argb(150, 176, 214, 198))
            }
        }
        fun optionRow(label: String, onClick: () -> Unit): TextView = TextView(overlayWindowContext).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = px(9).toFloat()
                setColor(Color.argb(210, 40, 66, 56))
            }
            setPadding(px(8), px(9), px(8), px(9))
            setOnClickListener { onClick() }
        }

        var popup: PopupWindow? = null
        fun dismiss() {
            popup?.dismiss()
            popup = null
        }
        fun choose(suffix: String?) {
            battleOverlayController.setOpponentMegaSuffix(suffix)
            dismiss()
            refreshMegaChips()
        }
        fun addRow(label: String, enabled: Boolean = true, onClick: () -> Unit) {
            panel.addView(
                optionRow(label) {}.apply {
                    if (!enabled) {
                        alpha = 0.45f
                        isEnabled = false
                        setOnClickListener(null)
                    } else {
                        setOnClickListener { onClick() }
                    }
                },
                LinearLayout.LayoutParams(px(140), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = px(4)
                },
            )
        }

        addRow("关闭超级进化") { choose(null) }
        if (!opponentKnown) {
            // 没读到对手（不在战斗画面 / OCR 读空）：退回通用列表，和改前行为一致。
            listOf("普通Mega" to "", "Mega X" to "X", "Mega Y" to "Y", "Mega Z" to "Z")
                .forEach { (label, suffix) -> addRow(label) { choose(suffix) } }
        } else {
            val options = runCatching { battleOverlayController.opponentMegaOptions() }
                .getOrDefault(emptyList())
            if (options.isEmpty()) {
                addRow("这只没有超级进化", enabled = false) {}
            } else {
                options.forEach { mega ->
                    addRow(battleOverlayController.megaOptionLabel(mega)) {
                        choose(battleOverlayController.megaOptionSuffix(mega))
                    }
                }
            }
        }
        popup = PopupWindow(panel, px(152), LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = 8f
            showAsDropDown(anchor, 0, px(4))
        }
    }

    /**
     * 只读对手名牌（含性别徽章）的快速识别：两枚 Mega chip 共用同一次结果。
     * 走 captureFrame 的互斥与画面冻结逻辑，但不建会话、不算伤害，成本只有一次 ROI OCR（约 0.2s）。
     */
    private fun readOpponentQuickly(onResult: (EntityValue?) -> Unit) {
        captureFrame(
            onCaptureStarted = { showStatusChip("正在读取对手…") },
            onBusy = {
                showStatusChip("上一次识别尚未结束，请稍候")
                onResult(null)
            },
            onFailure = { message ->
                publish(message)
                onResult(null)
            },
        ) { frame, _ ->
            val startedAt = System.currentTimeMillis()
            val engine = paddleOcr
            val nameText = if (engine == null || frame.width < SNAPSHOT_ROI_MIN_WIDTH) {
                ""
            } else {
                val pixels = SNAPSHOT_REGIONS[0].pixelBounds(frame)
                val rect = PaddleOcrEngine.RegionRect(
                    left = pixels[0].toFloat() / frame.width,
                    top = pixels[1].toFloat() / frame.height,
                    right = pixels[2].toFloat() / frame.width,
                    bottom = pixels[3].toFloat() / frame.height,
                )
                runCatching { engine.recognizeRegions(frame, listOf(rect)) }
                    .getOrElse { error ->
                        Log.e(LOG_TAG, "对手名牌快速识别失败", error)
                        emptyList()
                    }
                    .firstOrNull()
                    ?.let { cleanRegionText(it) }
                    .orEmpty()
            }
            val gender = readGenderBadge(frame, SNAPSHOT_OPPONENT_GENDER_BADGE)
            val species = if (nameText.isEmpty()) {
                null
            } else {
                runCatching { resolveSpeciesName(nameText) }.getOrNull()
                    ?.let { presetRepository.speciesWithGender(it, nameText + gender.orEmpty()) }
            }
            logRecognition(
                "snapshot quick opp text='$nameText${gender.orEmpty()}'→${species?.displayName ?: "?"} " +
                    "cost=${System.currentTimeMillis() - startedAt}ms",
            )
            mainHandler.post {
                recycleOwnTeamFrame(frame)
                if (destroyed) return@post
                recognizing = false
                frameTrackingEnabled = true
                showAssistantEntry()
                onResult(species)
            }
        }
    }

    /** 名字 → 正式名（先字面匹配，再用宝可梦词库纠正一字之差），供快速读名牌和整屏识别共用。 */
    private fun resolveSpeciesName(text: String): EntityValue? {
        val species = presetRepository.speciesCatalog
        SnapshotNameMatcher.bestMatch(text, species, EntityValue::displayName, allowFragment = false)
            ?.let { return it }
        val official = runCatching { ocrVocabulary.correctToOfficialName(text, "species") }.getOrNull()
            ?: return null
        return species.firstOrNull { it.displayName == official }
            ?: SnapshotNameMatcher.bestMatch(official, species, EntityValue::displayName, allowFragment = false)
    }

    /** 把 chip 的文案/底色刷成当前会话的 Mega 状态；没会话时是「默认关」的灰样子。 */
    private fun refreshMegaChips() {
        val density = overlayWindowContext.resources.displayMetrics.density
        fun px(value: Int) = (value * density).toInt()
        val state = runCatching { battleOverlayController.megaChips() }
            .getOrElse { BattleOverlayController.MegaChipState() }

        fun apply(view: TextView?, label: String, on: Boolean, available: Boolean) {
            view ?: return
            view.text = label
            view.isEnabled = available
            view.alpha = if (available) 1f else 0.5f
            view.setTextColor(if (on) Color.WHITE else Color.rgb(206, 230, 216))
            view.background = GradientDrawable().apply {
                cornerRadius = px(9).toFloat()
                if (on) {
                    setColor(Color.argb(240, 196, 120, 20))
                    setStroke(px(2), Color.argb(255, 255, 208, 96))
                } else {
                    setColor(Color.argb(190, 24, 42, 36))
                    setStroke(px(1), Color.argb(130, 176, 214, 198))
                }
            }
        }
        apply(ownMegaChip, state.ownLabel, state.ownOn, state.ownAvailable)
        apply(opponentMegaChip, state.opponentLabel, state.opponentOn, state.opponentAvailable)
    }

    /** 能力等级 chip：0 显示成「+0」，非 0 高亮，和 MEGA chip 一样的配色规则。 */
    private fun refreshStageChips() {
        val density = overlayWindowContext.resources.displayMetrics.density
        fun px(value: Int) = (value * density).toInt()
        val state = runCatching { battleOverlayController.stageChips() }
            .getOrElse { BattleOverlayController.StageChipState() }
        fun apply(view: TextView?, prefix: String, stage: Int) {
            view ?: return
            view.text = prefix + if (stage > 0) "+$stage" else stage.toString()
            val active = stage != 0
            view.alpha = 1f
            view.setTextColor(if (active) Color.WHITE else Color.rgb(206, 230, 216))
            view.background = GradientDrawable().apply {
                cornerRadius = px(9).toFloat()
                if (active) {
                    setColor(Color.argb(240, 196, 120, 20))
                    setStroke(px(2), Color.argb(255, 255, 208, 96))
                } else {
                    setColor(Color.argb(190, 24, 42, 36))
                    setStroke(px(1), Color.argb(130, 176, 214, 198))
                }
            }
        }
        apply(ownStageChip, "我", state.ownStage)
        apply(opponentStageChip, "敌", state.opponentStage)
    }

    /**
     * 点能力等级 chip 弹出 -6~+6 面板。只填一个数字：我方写进攻击+特攻、对手写进防御+特防，
     * 引擎按这招是物攻还是特攻各取一项，所以不需要玩家再分「物/特」。
     */
    private fun onStageChip(own: Boolean) {
        val anchor = (if (own) ownStageChip else opponentStageChip) ?: bubble ?: return
        val density = overlayWindowContext.resources.displayMetrics.density
        fun px(value: Int) = (value * density).toInt()
        val current = runCatching { battleOverlayController.stageChips() }
            .getOrElse { BattleOverlayController.StageChipState() }
            .let { if (own) it.ownStage else it.opponentStage }

        val panel = LinearLayout(overlayWindowContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(6), px(6), px(6), px(6))
            background = GradientDrawable().apply {
                cornerRadius = px(12).toFloat()
                setColor(Color.argb(238, 24, 42, 36))
                setStroke(px(1), Color.argb(150, 176, 214, 198))
            }
        }
        fun optionRow(label: String, highlighted: Boolean, onClick: () -> Unit): TextView =
            TextView(overlayWindowContext).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                includeFontPadding = false
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = px(9).toFloat()
                    if (highlighted) {
                        setColor(Color.argb(240, 196, 120, 20))
                        setStroke(px(2), Color.argb(255, 255, 208, 96))
                    } else {
                        setColor(Color.argb(210, 40, 66, 56))
                    }
                }
                setPadding(px(8), px(7), px(8), px(7))
                setOnClickListener { onClick() }
            }

        var popup: PopupWindow? = null
        fun dismiss() {
            popup?.dismiss()
            popup = null
        }
        fun choose(value: Int) {
            if (own) battleOverlayController.setOwnStatStage(value)
            else battleOverlayController.setOpponentStatStage(value)
            dismiss()
            refreshStageChips()
        }

        panel.addView(optionRow(if (own) "我方能力等级（攻/特攻）" else "对方能力等级（防/特防）", false) { dismiss() })
        // 上排减、下排加，同一数值上下对齐（-1 正上方是 +1），0 单独一行。
        panel.addView(
            optionRow("0（无变化）", current == 0) { choose(0) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = px(3) },
        )
        listOf(-1 downTo -6, 1..6).forEachIndexed { rowIndex, values ->
            panel.addView(LinearLayout(overlayWindowContext).apply {
                orientation = LinearLayout.HORIZONTAL
                values.forEach { value ->
                    addView(
                        optionRow(if (value > 0) "+$value" else value.toString(), value == current) { choose(value) },
                        LinearLayout.LayoutParams(px(40), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            if (value != values.last()) rightMargin = px(3)
                            if (rowIndex == 0) bottomMargin = px(3)
                        },
                    )
                }
            })
        }
        val panelWidth = px(6 * 40 + 5 * 3 + 12)
        popup = PopupWindow(panel, panelWidth, LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = 8f
            // chip 在最下方，弹窗往上展开，别被屏幕底边裁掉。
            val location = IntArray(2)
            anchor.getLocationOnScreen(location)
            panel.measure(
                View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val height = panel.measuredHeight
            val y = (location[1] - height - px(8)).coerceAtLeast(px(8))
            // 单行 13 个按钮比较宽，贴着屏幕左/右边缘的 chip 要把弹窗横向拉回屏内。
            val metrics = overlayWindowContext.resources.displayMetrics
            val x = location[0].coerceIn(px(4), (metrics.widthPixels - panelWidth - px(4)).coerceAtLeast(px(4)))
            showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        }
    }

    private fun showAssistantEntry(revealHiddenHud: Boolean = true) {
        if (destroyed) return
        if (assistantMode.usesFloatingBubble) {
            showBubble()
        } else {
            removeBubble()
            battleOverlayController.showDirectHudEntry(revealHiddenHud)
        }
    }

    private fun setBubbleWindowPresent(present: Boolean) {
        if (present) showAssistantEntry() else removeBubble()
    }

    private fun onOverlaySafeAreaChanged() {
        if (destroyed) return
        val bubbleView = bubble
        val params = bubbleParams
        if (bubbleView != null && params != null) {
            val position = safeArea.clampPosition(params.x, params.y, params.width, params.height)
            params.x = position.x
            params.y = position.y
            runCatching { windowManager.updateViewLayout(bubbleView, params) }
        }
        reflowTeamNamePrompt()
        if (::battleOverlayController.isInitialized) battleOverlayController.onSafeAreaChanged()
        if (::ownTeamCorrectionController.isInitialized) ownTeamCorrectionController.onSafeAreaChanged()
    }

    private fun scheduleOverlaySafeAreaRefresh() {
        if (destroyed) return
        // Some OEMs report the display change before WindowMetrics has published its
        // rotated bounds. Coalesce callback storms while retaining one immediate and
        // one settled in-place reflow for the final display state.
        mainHandler.removeCallbacks(immediateOverlaySafeAreaRefresh)
        mainHandler.removeCallbacks(settledOverlaySafeAreaRefresh)
        mainHandler.post(immediateOverlaySafeAreaRefresh)
        mainHandler.postDelayed(settledOverlaySafeAreaRefresh, 250L)
    }

    /** 识别诊断日志：写进应用私有目录 recognition.log，方便真机排查认出了什么。 */
    private fun logRecognition(message: String) {
        Log.i(LOG_TAG, message)
        runCatching {
            val dir = getExternalFilesDir("logs") ?: return
            if (!dir.exists()) dir.mkdirs()
            val file = java.io.File(dir, "recognition.log")
            if (file.exists() && file.length() > 512_000L) file.delete()
            val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            file.appendText("$stamp $message" + "\n", Charsets.UTF_8)
        }
    }


    private fun captureFrame(
        useFrozenMenuFrame: Boolean = false,
        onCaptureStarted: () -> Unit = {},
        onBusy: () -> Unit = {},
        onFailure: ((String) -> Unit)? = null,
        onFrame: (Bitmap, CaptureTiming) -> Unit,
    ) {
        if (destroyed) return
        if (recognizing) {
            Log.i(LOG_TAG, "Capture skipped: previous recognition still running")
            toast("上一次识别仍在进行，请稍候")
            showStatusChip("上一次识别仍在进行，请稍候")
            onBusy()
            return
        }
        recognizing = true
        logRecognition("capture started (frozenMenuFrame=$useFrozenMenuFrame)")
        onCaptureStarted()
        val requestedAt = System.nanoTime()
        hideStatusChip()
        removeBubble()
        // Toasts are part of the MediaProjection output. In landscape they sit
        // across the bottom two cards, so cancel any result Toast and update
        // status without drawing a new overlay before copying the clean frame.
        activeToast?.cancel()
        activeToast = null
        CaptureUiState.message.value = "正在读取当前画面…"
        if (!useFrozenMenuFrame) {
            synchronized(bitmapLock) {
                frozenMenuBitmap?.recycle()
                frozenMenuBitmap = null
                frameTrackingEnabled = true
            }
        }
        val request = CancelableDelayedTask capture@{
            pendingFrameCapture = null
            if (destroyed) return@capture
            val hideWaitMs = (System.nanoTime() - requestedAt) / 1_000_000.0
            val copyStarted = System.nanoTime()
            val frame = synchronized(bitmapLock) {
                if (destroyed) return@synchronized null
                frameTrackingEnabled = false
                if (useFrozenMenuFrame) frozenMenuBitmap.also { frozenMenuBitmap = null }
                else copyLatestFrameLocked()
            }
            if (destroyed) {
                frame?.recycle()
                return@capture
            }
            val selectionCopyMs = (System.nanoTime() - copyStarted) / 1_000_000.0
            val frameCopyMs = selectionCopyMs + if (useFrozenMenuFrame) frozenMenuFrameCopyMs else 0.0
            showAssistantEntry()
            if (frame == null) {
                frameTrackingEnabled = true
                recognizing = false
                val message = "暂时无法读取当前画面，请稍后重试"
                onFailure?.invoke(message) ?: publish(message)
                return@capture
            }
            publish("正在识别当前画面…")
            if (destroyed) {
                frame.recycle()
                return@capture
            }
            onFrame(frame, CaptureTiming(requestedAt, hideWaitMs, frameCopyMs))
        }
        pendingFrameCapture = request
        if (!mainHandler.postDelayed(request, if (useFrozenMenuFrame) 0 else 1_000)) {
            pendingFrameCapture = null
            request.cancel()
            recognizing = false
            showAssistantEntry()
            synchronized(bitmapLock) { frameTrackingEnabled = true }
            val message = "暂时无法开始识别，请重试"
            onFailure?.invoke(message) ?: publish(message)
        }
    }

    private fun copyLatestFrameLocked(): Bitmap? {
        val source = latestBitmap ?: return null
        val spec = captureBufferSpec ?: return null
        val bufferWidth = source.width.coerceAtMost(spec.virtualDisplayWidth)
        val bufferHeight = source.height.coerceAtMost(spec.virtualDisplayHeight)
        if (spec.rotation == Surface.ROTATION_0) {
            return Bitmap.createBitmap(
                bufferWidth,
                bufferHeight,
                Bitmap.Config.ARGB_8888,
            ).also { Canvas(it).drawBitmap(source, 0f, 0f, null) }
        }
        return Bitmap.createBitmap(
            source,
            0,
            0,
            bufferWidth,
            bufferHeight,
            Matrix().apply { postRotate(spec.bitmapRotationDegrees) },
            true,
        )
    }

    private fun captureAndRecognizeOwnTeam() {
        if (importRepository.hasPendingTeam()) {
            autoSaveOwnTeam()
        }
        if (importRepository.hasCorrectionDraft()) {
            runCatching { importRepository.discardCurrentImport() }
            publish("上一次识别不完整，已复位；可重新停在队伍页再点一次。")
            return
        }
        val expectedType = importRepository.expectedCapturePageType()
        Log.i(LOG_TAG, "Own-team recognition requested, expecting ${expectedType?.let(::ownTeamPageLabel) ?: "任意队伍页面"}")
        captureFrame(
            onCaptureStarted = {
                battleOverlayController.onOwnTeamRecognitionStarted()
                updateOwnTeamRecognitionState(OwnTeamRecognitionHudState(
                    buttonLabel = "正在读取…",
                    message = "正在读取当前队伍页面，读取完成后 HUD 会自动恢复",
                    inProgress = true,
                ))
            },
            onBusy = {
                updateOwnTeamRecognitionState(OwnTeamRecognitionHudState(
                    buttonLabel = "识别进行中",
                    message = "上一次识别尚未结束，请等待结果；最长等待 90 秒",
                    inProgress = true,
                ))
            },
            onFailure = { message ->
                acceptOwnTeamRecognitionFallback(expectedType, 1, 1, message)
            },
        ) { frame, _ ->
            val frameWidth = frame.width
            val frameHeight = frame.height
            val generation = ++ownTeamRecognitionGeneration
            updateOwnTeamRecognitionState(OwnTeamRecognitionHudState(
                buttonLabel = "识别中…",
                message = "正在识别当前队伍页面，最长等待 90 秒",
                inProgress = true,
            ))
            ownTeamRecognitionTask = ocrEngine.recognize(frame, expectedType) callback@{ result ->
                if (destroyed) {
                    recycleOwnTeamFrame(frame)
                    return@callback
                }
                mainHandler.post {
                    recycleOwnTeamFrame(frame)
                    if (destroyed || generation != ownTeamRecognitionGeneration) return@post
                    clearOwnTeamRecognitionWatchdog(cancelTask = false)
                    frameTrackingEnabled = true
                    recognizing = false
                    result.onSuccess(::acceptOwnTeamRecognitionPage).onFailure { error ->
                        Log.e(LOG_TAG, "Own-team recognition failed", error)
                        acceptOwnTeamRecognitionFallback(
                            expectedType,
                            frameWidth,
                            frameHeight,
                            "这一页没有识别成功",
                        )
                    }
                }
            }
            scheduleOwnTeamRecognitionWatchdog(generation, expectedType, frame)
        }
    }

    private fun scheduleOwnTeamRecognitionWatchdog(
        generation: Long,
        expectedType: OwnTeamPageType?,
        frame: Bitmap,
    ) {
        pendingOwnTeamSlowNotice = Runnable {
            if (destroyed || generation != ownTeamRecognitionGeneration || !recognizing) return@Runnable
            updateOwnTeamRecognitionState(OwnTeamRecognitionHudState(
                buttonLabel = "仍在识别…",
                message = "识别耗时较长，仍在处理；超过 90 秒会自动转入手动核对",
                inProgress = true,
            ))
        }.also { mainHandler.postDelayed(it, OWN_TEAM_SLOW_NOTICE_MS) }
        pendingOwnTeamTimeout = Runnable {
            if (destroyed || generation != ownTeamRecognitionGeneration || !recognizing) return@Runnable
            ownTeamRecognitionGeneration++
            val task = ownTeamRecognitionTask
            clearOwnTeamRecognitionWatchdog(cancelTask = false)
            task?.cancel(true)
            frameTrackingEnabled = true
            recognizing = false
            acceptOwnTeamRecognitionFallback(
                expectedType,
                frame.width,
                frame.height,
                "识别超过 90 秒，已自动结束",
            )
        }.also { mainHandler.postDelayed(it, OWN_TEAM_TIMEOUT_MS) }
    }

    private fun clearOwnTeamRecognitionWatchdog(cancelTask: Boolean) {
        pendingOwnTeamSlowNotice?.let(mainHandler::removeCallbacks)
        pendingOwnTeamSlowNotice = null
        pendingOwnTeamTimeout?.let(mainHandler::removeCallbacks)
        pendingOwnTeamTimeout = null
        if (cancelTask) ownTeamRecognitionTask?.cancel(true)
        ownTeamRecognitionTask = null
    }

    private fun recycleOwnTeamFrame(frame: Bitmap) {
        if (!frame.isRecycled) frame.recycle()
    }

    private fun acceptOwnTeamRecognitionFallback(
        expectedType: OwnTeamPageType?,
        width: Int,
        height: Int,
        reason: String,
    ) {
        if (expectedType == null || expectedType == OwnTeamPageType.MOVE_ITEM) {
            resetOwnTeamImportAfterEmptyFirstPage(reason)
            return
        }
        val message = "$reason；已转入核对窗口，未识别内容可手动补充"
        acceptOwnTeamRecognitionPage(blankOwnTeamPage(expectedType, width, height), message)
    }

    private fun acceptOwnTeamRecognitionPage(
        page: RecognizedOwnTeamPage,
        messageOverride: String? = null,
    ) {
        if (shouldResetAfterEmptyFirstOwnTeamPage(page)) {
            resetOwnTeamImportAfterEmptyFirstPage("这一页没有识别到宝可梦、道具或招式")
            return
        }
        runCatching { importRepository.accept(page) }
            .onSuccess { saved ->
                CaptureUiState.ownTeamDraftRevision.value += 1
                val message = messageOverride ?: saved.message
                when (saved.nextStep) {
                    OwnTeamImportNextStep.MANUAL_CORRECTION -> autoSaveOwnTeamLenient()
                    OwnTeamImportNextStep.CAPTURE_MOVE_ITEM -> {
                        if (importRepository.hasStatsPage()) {
                            autoSaveStatsOnlyTeam()
                        } else {
                            publish(message)
                            refreshOwnTeamRecognitionState(message)
                            showAssistantEntry()
                        }
                    }
                    OwnTeamImportNextStep.NAME_TEAM -> {
                        publish(message)
                        autoSaveOwnTeam()
                    }
                    else -> {
                        publish(message)
                        refreshOwnTeamRecognitionState(message)
                        showAssistantEntry()
                    }
                }
            }
            .onFailure { error ->
                Log.e(LOG_TAG, "Could not store own-team recognition result", error)
                recognizing = false
                updateOwnTeamRecognitionState(OwnTeamRecognitionHudState(
                    buttonLabel = "重新识别",
                    message = "识别结果处理失败，请重新识别；本次没有一直等待",
                ))
                showAssistantEntry()
                publish("识别结果处理失败，请重新识别")
            }
    }

    /** 对战中停在“当前状态”页时，读取这只宝可梦的能力值并写回队伍。 */
    private fun captureAndRecordOwnStats() {
        captureFrame(
            onCaptureStarted = {
                // 识别能力只是补数据：先把上一回合的伤害红框收掉，既避免它进截图，也不让旧数字继续挂着。
                battleOverlayController.hideDirectHud()
                battleOverlayController.onOwnTeamRecognitionStarted()
                publish("正在读取当前宝可梦状态页…")
            },
            onFailure = { message ->
                publish(message)
                battleOverlayController.hideDirectHud()
            },
        ) { frame, _ ->
            // captureFrame 恢复悬浮键时会把 HUD 一起抬出来，这里立刻再收掉，避免旧伤害框在状态页上闪一下。
            battleOverlayController.hideDirectHud()
            val generation = ++ownTeamRecognitionGeneration
            publish("正在识别当前页面…")
            ownTeamRecognitionTask = ocrEngine.recognizeCurrentPage(frame) callback@{ result ->
                if (destroyed) {
                    recycleOwnTeamFrame(frame)
                    return@callback
                }
                mainHandler.post {
                    recycleOwnTeamFrame(frame)
                    if (destroyed || generation != ownTeamRecognitionGeneration) return@post
                    frameTrackingEnabled = true
                    recognizing = false
                    result.onSuccess { page ->
                        when (page) {
                            is CurrentPageRecord.Stats ->
                                battleOverlayController.recordOwnStats(
                                    page.record.actualStats,
                                    page.record.statPoints,
                                    page.record.species,
                                )
                            is CurrentPageRecord.Loadout ->
                                battleOverlayController.recordOwnLoadout(page.loadout)
                        }
                    }.onFailure { error ->
                        Log.e(LOG_TAG, "Current page recognition failed", error)
                        publish("这一页没读到能力值，也没读到招式/特性/持有物，请停在该只宝可梦的“状态”或“能力”页")
                        battleOverlayController.hideDirectHud()
                    }
                }
            }
        }
    }

    /** 两张队伍页面都识别完成后直接并进「我的宝可梦」，不再弹命名窗口。 */
    private fun autoSaveOwnTeam() {
        if (!importRepository.hasPendingTeam()) return
        val name = "对局录入 " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        runCatching { importRepository.savePendingTeam(name) }
            .onSuccess { saved ->
                CaptureUiState.teamLibraryRevision.value += 1
                updateOwnTeamRecognitionState(OwnTeamRecognitionHudState())
                showAssistantEntry()
                publish(saved.message)
            }
            .onFailure { error ->
                Log.e(LOG_TAG, "Could not auto-save recognized own team", error)
                updateOwnTeamRecognitionState(OwnTeamRecognitionHudState(
                    buttonLabel = "重新识别",
                    message = "队伍自动保存失败，请重新识别两张队伍页面",
                ))
                showAssistantEntry()
                publish("队伍自动保存失败，请重新识别")
            }
    }

    /**
     * 两页都在但识别不完整时，先把能用的内容入库，再用图鉴默认值补齐特性、招式和能力值，
     * 这样对局建立时不会因为“队伍不完整”被挡掉。
     */
    private fun autoSaveOwnTeamLenient() {
        val name = "对局录入 " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        runCatching { importRepository.saveCurrentImportLenient(name) }
            .onSuccess { saved ->
                runCatching { repairMyPokemonDefaults() }
                    .onFailure { Log.e(LOG_TAG, "Could not complete my pokemon with defaults", it) }
                CaptureUiState.ownTeamDraftRevision.value += 1
                CaptureUiState.teamLibraryRevision.value += 1
                updateOwnTeamRecognitionState(OwnTeamRecognitionHudState())
                showAssistantEntry()
                publish(saved.message)
            }
            .onFailure { error ->
                Log.e(LOG_TAG, "Could not save own team leniently", error)
                updateOwnTeamRecognitionState(OwnTeamRecognitionHudState(
                    buttonLabel = "重新识别",
                    message = "队伍保存失败，请重新识别两张队伍页面",
                ))
                showAssistantEntry()
                publish("队伍保存失败，请重新识别两张队伍页面")
            }
    }

    /** 只识别了“能力值”页时也直接入库，招式/特性按图鉴默认补齐。 */
    private fun autoSaveStatsOnlyTeam() {
        val name = "状态页录入 " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        runCatching { importRepository.saveStatsOnly(name) }
            .onSuccess { saved ->
                runCatching { repairMyPokemonDefaults() }
                    .onFailure { Log.e(LOG_TAG, "Could not complete stats-only my pokemon", it) }
                CaptureUiState.ownTeamDraftRevision.value += 1
                CaptureUiState.teamLibraryRevision.value += 1
                updateOwnTeamRecognitionState(OwnTeamRecognitionHudState())
                showAssistantEntry()
                publish(saved.message)
            }
            .onFailure { error ->
                Log.e(LOG_TAG, "Could not save stats-only team", error)
                publish("能力值保存失败，请重试")
            }
    }

    /**
     * 补齐「我的宝可梦」里每只缺失的特性、招式和六项实际能力值。
     * 这里不再按“队伍文件 + teamId”去修，只有一份按物种 upsert 的列表。
     */
    private fun repairMyPokemonDefaults() {
        val current = TeamRepository.loadMyPokemon(this)?.pokemon ?: return
        var changed = false
        val repaired = current.map { config ->
            val ability = config.ability ?: presetRepository.abilitiesFor(config.species).firstOrNull()
            val moves = if (config.moves.isEmpty()) {
                presetRepository.learnableMovesFor(config.species)
                    .filter { (it.basePower ?: 0) > 0 }
                    .take(4)
            } else {
                config.moves
            }
            val actualStats = if (config.hasCompleteActualStats()) {
                config.actualStats
            } else {
                presetRepository.statsFor(config.species, config.statPoints, config.statAlignment)
            }
            val fixed = config.copy(ability = ability, moves = moves, actualStats = actualStats)
            if (fixed != config) changed = true
            fixed
        }
        if (changed) runCatching { TeamRepository.upsertMyPokemon(this, repaired) }
            .onFailure { Log.e(LOG_TAG, "Could not repair my pokemon defaults", it) }
    }

    /** 选招页一键计算：识别我方当前宝可梦、对手和四个招式，直接开 HUD 出三档结果。 */
    private fun captureAndComputeSnapshot() {
        Log.i(LOG_TAG, "Snapshot compute requested")
        captureFrame(
            onCaptureStarted = {
                // 这里不清伤害缓存：相同阵容组合要能直接命中上一回合的结果。
                showStatusChip("正在按当前画面重新识别双方与招式…")
            },
            onBusy = { showStatusChip("上一次识别尚未结束，请稍候") },
            onFailure = { message -> publish(message) },
        ) { frame, _ ->
            val roiStartedAt = System.currentTimeMillis()
            val regionLines = runCatching { buildSnapshotRegionLines(frame) }.getOrElse { error ->
                Log.e(LOG_TAG, "snapshot ROI 识别失败", error)
                emptyList()
            }
            var matched = if (regionLines.isEmpty()) null else matchSnapshot(regionLines)
            if (matched != null) {
                logRecognition(
                    "snapshot ROI lines=${regionLines.size} cost=${System.currentTimeMillis() - roiStartedAt}ms " +
                        "own=${matched.own.displayName} opponent=${matched.opponent.displayName} " +
                        "moves=${matched.moves.map { it.entity.displayName }}",
                )
            } else {
                // 固定区域认不出（布局变化、读空、招式不足）时回落到整屏检测，准确率不低于改前。
                logRecognition("snapshot ROI miss (${regionLines.size} lines), fallback to full det")
                val lines = runCatching { paddleOcr?.recognize(frame, limit = 1024) ?: emptyList() }.getOrElse { error ->
                    Log.e(LOG_TAG, "PaddleOCR failed", error)
                    emptyList()
                }
                if (lines.isNotEmpty()) {
                    logRecognition("paddle lines=" + lines.size)
                    matched = matchSnapshot(lines.filterNot { looksLikeHudText(it.text) })
                }
            }
            val iconRecognition = matched?.let { snapshot ->
                runCatching {
                    val ownIcon = cropSnapshotRegion(frame, SNAPSHOT_OWN_ICON)
                    val opponentIcon = cropSnapshotRegion(frame, SNAPSHOT_OPPONENT_ICON)
                    if (DEBUG_ICON_DIR) {
                        ownIcon?.let { saveIconDebug(it, "own") }
                        opponentIcon?.let { saveIconDebug(it, "opponent") }
                    }
                    val result = battleOverlayController.recognizeIcons(
                        ownIcon,
                        opponentIcon,
                        snapshot.own,
                        snapshot.opponent,
                    )
                    ownIcon?.recycle()
                    opponentIcon?.recycle()
                    result
                }.onFailure { error ->
                    Log.e(LOG_TAG, "头像形态识别失败", error)
                }.getOrNull()
            }
            logRecognition(iconRecognition?.log ?: "头像形态识别：跳过")
            mainHandler.post {
                recycleOwnTeamFrame(frame)
                if (destroyed) return@post
                recognizing = false
                frameTrackingEnabled = true
                showAssistantEntry()
                if (matched == null) {
                    logRecognition("snapshot match FAILED")
                    publish("没认出双方或招式，请停在能看到四个招式的画面再点一次")
                } else {
                battleOverlayController.startSnapshotSession(
                    matched.own,
                    matched.moves,
                    matched.opponent,
                    matched.weather,
                    matched.terrain,
                    iconRecognition,
                )
                refreshMegaChips()
                refreshStageChips()
                }
            }
        }
    }

    /** 按固定区域从当前帧里裁一块出来（形态识别用）。 */
    private fun cropSnapshotRegion(frame: Bitmap, region: SnapshotRegion): Bitmap? = runCatching {
        val bounds = region.pixelBounds(frame)
        Bitmap.createBitmap(frame, bounds[0], bounds[1], bounds[2] - bounds[0], bounds[3] - bounds[1])
    }.getOrNull()

    /** 调试用：把裁下来的头像存一份，方便核对 ROI 有没有偏。 */
    private fun saveIconDebug(bitmap: Bitmap, tag: String) {
        runCatching {
            val dir = java.io.File(filesDir, "debug-icons").apply { mkdirs() }
            val stamp = java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            java.io.File(dir, "icon-$tag-$stamp.png").outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }.onFailure { Log.e(LOG_TAG, "保存头像调试图失败", it) }
    }

    private data class SnapshotMatch(
        val own: EntityValue,
        val opponent: EntityValue,
        val moves: List<MoveValue>,
        /** 画面左上角读到的天气/场地，只在这一次快照里生效（有回合限制，不写进以后的计算）。 */
        val weather: String = "NONE",
        val terrain: String = "NONE",
    )

    /**
     * 一块固定识别区域：横向按「距左边 / 距右边」定义（游戏的招式条和对手名牌贴右边缘、
     * 我方名牌和天气条贴左边缘，换屏宽也不会整体错位），纵向按画面高度比例。
     */
    private class SnapshotRegion(
        val leftInset: Float,
        val rightInset: Float,
        val top: Float,
        val bottom: Float,
    ) {
        fun pixelBounds(frame: Bitmap): IntArray {
            val left = (leftInset * frame.width).roundToInt().coerceIn(0, frame.width - 1)
            val right = (frame.width - rightInset * frame.width).roundToInt().coerceIn(left + 1, frame.width)
            val topPixel = (top * frame.height).roundToInt().coerceIn(0, frame.height - 1)
            val bottomPixel = (bottom * frame.height).roundToInt().coerceIn(topPixel + 1, frame.height)
            return intArrayOf(left, topPixel, right, bottomPixel)
        }
    }

    /**
     * 按固定区域直接识别「计算伤害」页（选招页）：只裁 4 个招式 + 2 个名牌 + 天气条，
     * 跳过整屏检测，跑得快得多。坐标实测自真机 2772×1280 截图。
     * 返回能直接交给 [matchSnapshot] 的文本行（带真实像素位置）；读不全时返回空列表，
     * 由调用方回落到整屏检测，保证准确率不比改前低。
     */
    private fun buildSnapshotRegionLines(frame: Bitmap): List<SnapshotTextLine> {
        val engine = paddleOcr ?: return emptyList()
        // 画面太窄时这些固定区域会缩到几十像素，字会糊，直接用整屏检测更稳。
        if (frame.width < SNAPSHOT_ROI_MIN_WIDTH) return emptyList()
        val regions = SNAPSHOT_REGIONS
        val bounds = ArrayList<IntArray>(regions.size)
        val rects = ArrayList<PaddleOcrEngine.RegionRect>(regions.size)
        for (region in regions) {
            val pixels = region.pixelBounds(frame)
            bounds += pixels
            rects += PaddleOcrEngine.RegionRect(
                left = pixels[0].toFloat() / frame.width,
                top = pixels[1].toFloat() / frame.height,
                right = pixels[2].toFloat() / frame.width,
                bottom = pixels[3].toFloat() / frame.height,
            )
        }
        val texts = engine.recognizeRegions(frame, rects).map { cleanRegionText(it) }
        val opponentName = texts.getOrNull(0).orEmpty()
        val ownName = texts.getOrNull(1).orEmpty()
        val field = texts.getOrNull(2).orEmpty()
        val moves = texts.drop(3).filter { it.isNotEmpty() }
        if (ownName.isEmpty() || opponentName.isEmpty() || moves.size < 3) {
            logRecognition(
                "snapshot ROI texts own='$ownName' opp='$opponentName' moves=$moves field='$field'",
            )
            return emptyList()
        }
        val ownGender = readGenderBadge(frame, SNAPSHOT_OWN_GENDER_BADGE)
        val opponentGender = readGenderBadge(frame, SNAPSHOT_OPPONENT_GENDER_BADGE)
        logRecognition(
            "snapshot ROI texts own='$ownName${ownGender.orEmpty()}' " +
                "opp='$opponentName${opponentGender.orEmpty()}' moves=$moves field='$field'",
        )
        val lines = ArrayList<SnapshotTextLine>(texts.size)
        fun add(index: Int, text: String) {
            val pixels = bounds[index]
            lines += SnapshotTextLine(
                text = text,
                left = pixels[0],
                top = pixels[1],
                right = pixels[2],
                bottom = pixels[3],
                imageWidth = frame.width,
                imageHeight = frame.height,
            )
        }
        add(0, opponentName + opponentGender.orEmpty())
        add(1, ownName + ownGender.orEmpty())
        if (field.isNotEmpty()) add(2, field)
        texts.drop(3).forEachIndexed { offset, text ->
            if (text.isNotEmpty()) add(3 + offset, text)
        }
        return lines
    }

    /**
     * 只保留中文和字母，去掉 OCR 在图标残影、背景花纹上"强行认出来"的数字和符号
     * （例如招式图标边的「1」、墙面花纹的「6」）。
     */
    private fun cleanRegionText(text: String): String {
        val builder = StringBuilder()
        for (character in text) {
            if (character in '\u4E00'..'\u9FFF' || character.isLetter()) builder.append(character)
        }
        return builder.toString()
    }

    /**
     * 性别徽章靠底色判断：蓝底是 ♂、粉红底是 ♀。徽章只有 20 多像素，送 OCR 会把 ♂ 读成「3」，
     * 所以这里只做颜色统计。
     */
    private fun readGenderBadge(frame: Bitmap, region: SnapshotRegion): String? {
        val pixelsBounds = region.pixelBounds(frame)
        val width = pixelsBounds[2] - pixelsBounds[0]
        val height = pixelsBounds[3] - pixelsBounds[1]
        if (width < 4 || height < 4) return null
        val pixels = IntArray(width * height)
        frame.getPixels(pixels, 0, width, pixelsBounds[0], pixelsBounds[1], width, height)
        var blue = 0
        var pink = 0
        for (pixel in pixels) {
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blueValue = pixel and 0xFF
            if (blueValue > red + 40 && blueValue > green + 20) blue++
            else if (red > blueValue + 40 && red > green + 25) pink++
        }
        val colored = blue + pink
        if (colored < width * height / 20) return null
        return when {
            blue > pink * 1.3f -> "♂"
            pink > blue * 1.3f -> "♀"
            else -> null
        }
    }

    /** 与 BATTLE_TERRAIN_VALUES / BATTLE_WEATHER_VALUES 对齐的画面文案。 */
    private val SNAPSHOT_TERRAIN_KEYWORDS = listOf(
        "精神场地" to "Psychic",
        "电气场地" to "Electric",
        "青草场地" to "Grassy",
        "薄雾场地" to "Misty",
    )
    private val SNAPSHOT_WEATHER_KEYWORDS = listOf(
        "沙暴" to "Sand",
        "晴天" to "Sun",
        "下雨" to "Rain",
        "雨天" to "Rain",
        "下雪" to "Snow",
        "雪天" to "Snow",
        "冰雹" to "Snow",
    )

    /**
     * 上一回合的红框 HUD 在截屏前不会被摘掉，它的「37-44｜31-37｜22-26%」「107 82 112 123」
     * 这类文字会一起进 OCR。这里按形态把它们筛掉，避免数字行被当成宝可梦名/招式名去模糊匹配。
     */
    private fun looksLikeHudText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.contains('\uFF5C') || trimmed.contains('%')) return true
        return trimmed.all { it.isDigit() || it in " -.,()" }
    }

    private fun matchSnapshot(lines: List<SnapshotTextLine>): SnapshotMatch? {
        if (lines.isEmpty()) {
            Log.i(LOG_TAG, "Snapshot OCR returned no text lines")
            return null
        }
        logRecognition("snapshot OCR lines: " + lines.take(30).joinToString(" | ") { line ->
            "${line.text}@(${line.left},${line.top})/${line.imageWidth}x${line.imageHeight}"
        })
        val imageWidth = lines.firstOrNull()?.imageWidth ?: return null
        val imageHeight = lines.firstOrNull()?.imageHeight ?: return null
        val species = presetRepository.speciesCatalog
        // 名字归一打分见 SnapshotNameMatcher：完全同名压过只差一个字，避免喷火龙被认成喷火驼。
        fun scoreName(text: String, name: String, allowFragment: Boolean): Int =
            SnapshotNameMatcher.score(text, name, allowFragment)
        fun matchSpecies(text: String): EntityValue? =
            SnapshotNameMatcher.bestMatch(text, species, EntityValue::displayName, allowFragment = false)
        fun matchMove(text: String, candidates: List<MoveValue>): MoveValue? =
            SnapshotNameMatcher.bestMatch(text, candidates, { it.entity.displayName }, allowFragment = true)
        // 先按字面匹配，再用词库（宝可梦正式名）做编辑距离纠正，避免“玛カ露丽”这类一字之差。
        fun resolveSpecies(text: String): EntityValue? {
            matchSpecies(text)?.let { return it }
            val official = runCatching { ocrVocabulary.correctToOfficialName(text, "species") }.getOrNull()
                ?: return null
            return species.firstOrNull { it.displayName == official }
                ?: SnapshotNameMatcher.bestMatch(
                    official,
                    species,
                    EntityValue::displayName,
                    allowFragment = false,
                )
        }
        val speciesLines = lines.mapNotNull { line -> resolveSpecies(line.text)?.let { line to it } }
        // 对手名牌在右上、我方名牌在左下；不依赖严格阈值，按相对位置取最靠上/最靠下的一条。
        val opponentLine = speciesLines
            .filter { it.first.top < imageHeight * 0.40 && it.first.left > imageWidth * 0.35 }
            .minByOrNull { it.first.top }
            ?: speciesLines.minByOrNull { it.first.top }
        val ownLine = speciesLines
            .filter { it.first.top > imageHeight * 0.45 && it.first.left < imageWidth * 0.5 }
            .maxByOrNull { it.first.top }
            ?: speciesLines.maxByOrNull { it.first.top }
        if (opponentLine == null || ownLine == null || opponentLine.second == ownLine.second) {
            Log.i(LOG_TAG, "Snapshot match failed: species lines=${speciesLines.size}")
            return null
        }
        val own = presetRepository.speciesWithGender(ownLine.second, ownLine.first.text)
        val opponent = presetRepository.speciesWithGender(opponentLine.second, opponentLine.first.text)
        val learnable = presetRepository.learnableMovesFor(own)
        val moveLines = lines
            .filter { it.left > imageWidth * 0.45 }
            .sortedBy { it.top }
            .mapNotNull { line ->
                matchMove(line.text, learnable)
                    ?: runCatching { ocrVocabulary.correctToOfficialName(line.text, "move") }.getOrNull()
                        ?.let { official -> learnable.firstOrNull { it.entity.displayName == official } }
            }
            .distinctBy { it.entity.showdownId }
        val moves = if (moveLines.isEmpty()) {
            learnable.filter { (it.basePower ?: 0) > 0 }.take(4)
        } else {
            moveLines.take(4)
        }
        // 把「OCR 原文 → 选中的正式名 → 打分」写进 recognition.log：
        // 以后再出现认错（比如喷火龙被认成喷火驼），一眼能看出是 OCR 读错还是匹配挑错。
        logRecognition(
            "snapshot names " +
                "own('${ownLine.first.text}'→${own.displayName}/score=${scoreName(ownLine.first.text, own.displayName, false)}) " +
                "opp('${opponentLine.first.text}'→${opponent.displayName}/score=${scoreName(opponentLine.first.text, opponent.displayName, false)}) " +
                "moves=" + lines.filter { it.left > imageWidth * 0.45 }.sortedBy { it.top }
                .joinToString(" | ") { line ->
                    "'${line.text}'→" + (matchMove(line.text, learnable)?.entity?.displayName ?: "?")
                },
        )
        Log.i(LOG_TAG, "Snapshot matched: own=${own.displayName} opponent=${opponent.displayName} moves=${moves.map { it.entity.displayName }}")
        // 回合开始后左上角会写「精神场地状态 5/5」「沙暴状态 4/5」这类字段效果，
        // 只有有限回合，所以只带到这一次计算里，不沿用。
        val terrain = lines.firstOrNull { line ->
            line.top < imageHeight * 0.18 && line.left < imageWidth * 0.5 &&
                SNAPSHOT_TERRAIN_KEYWORDS.any { keyword -> line.text.contains(keyword.first) }
        }?.let { line ->
            SNAPSHOT_TERRAIN_KEYWORDS.first { keyword -> line.text.contains(keyword.first) }.second
        } ?: "NONE"
        val weather = lines.firstOrNull { line ->
            line.top < imageHeight * 0.18 && line.left < imageWidth * 0.5 &&
                SNAPSHOT_WEATHER_KEYWORDS.any { keyword -> line.text.contains(keyword.first) }
        }?.let { line ->
            SNAPSHOT_WEATHER_KEYWORDS.first { keyword -> line.text.contains(keyword.first) }.second
        } ?: "NONE"
        if (weather != "NONE" || terrain != "NONE") {
            Log.i(LOG_TAG, "Snapshot field: weather=$weather terrain=$terrain")
        }
        return SnapshotMatch(own, opponent, moves, weather, terrain)
    }

    private fun resetOwnTeamImportAfterEmptyFirstPage(reason: String) {        runCatching { importRepository.discardCurrentImport() }
            .onSuccess {
                recognizing = false
                CaptureUiState.ownTeamDraftRevision.value += 1
                updateOwnTeamRecognitionState(OwnTeamRecognitionHudState())
                showAssistantEntry()
                publish("$reason；本次队伍识别已复位，可回到队伍页重新识别")
            }
            .onFailure { error ->
                Log.e(LOG_TAG, "Could not reset empty first own-team page", error)
                updateOwnTeamRecognitionState(OwnTeamRecognitionHudState(
                    buttonLabel = "重新识别",
                    message = "未检测到队伍内容，但复位失败；请重试",
                ))
                showAssistantEntry()
                publish("未检测到队伍内容，但识别状态复位失败，请重试")
            }
    }

    private fun refreshOwnTeamRecognitionState(messageOverride: String? = null) {
        val state = when {
            importRepository.hasCorrectionDraft() -> OwnTeamRecognitionHudState(
                buttonLabel = "识别已记录（可选补全）",
                message = messageOverride ?: "这一页已记录，招式与能力值可用图鉴默认补齐；不影响按画面计算",
            )
            importRepository.expectedCapturePageType() == OwnTeamPageType.STATS -> OwnTeamRecognitionHudState(
                buttonLabel = "识别当前",
                message = messageOverride ?: "已记录「招式与道具」页；可再识别「能力值」页补全，也可以直接按画面计算",
            )
            else -> OwnTeamRecognitionHudState()
        }
        updateOwnTeamRecognitionState(state)
    }

    private fun updateOwnTeamRecognitionState(state: OwnTeamRecognitionHudState) {
        if (::battleOverlayController.isInitialized) {
            battleOverlayController.updateOwnTeamRecognitionState(state)
        }
    }

    private fun ownTeamPageLabel(type: OwnTeamPageType): String = when (type) {
        OwnTeamPageType.MOVE_ITEM -> "招式与道具页"
        OwnTeamPageType.STATS -> "能力值页"
    }


    private fun openOwnTeamCorrection() {
        if (!Settings.canDrawOverlays(this)) {
            publish("请先在 App 中授予悬浮窗权限")
            if (projection == null) stopSelf()
            return
        }
        if (!importRepository.hasCorrectionDraft()) {
            publish("没有需要继续核对的队伍")
            if (projection == null) stopSelf()
            return
        }
        runCatching { ownTeamCorrectionController.show() }
            .onFailure { error ->
                Log.e(LOG_TAG, "Could not open own-team correction overlay", error)
                refreshOwnTeamRecognitionState("核对窗口暂时无法打开，请点击“继续核对”重试")
                showAssistantEntry()
                publish("核对窗口暂时无法打开，请重试")
            }
    }

    private fun showTeamNamePrompt() {
        if (!importRepository.hasPendingTeam()) {
            publish("没有等待保存的队伍")
            return
        }
        if (teamNamePrompt != null) return
        removeBubble()
        val density = overlayWindowContext.resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val root = LinearLayout(overlayWindowContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            background = GradientDrawable().apply {
                setColor(Color.rgb(31, 40, 58))
                cornerRadius = 18 * density
                setStroke((1 * density).toInt().coerceAtLeast(1), Color.rgb(119, 215, 196))
            }
        }
        root.addView(TextView(overlayWindowContext).apply {
            text = "保存我的队伍"
            textSize = 22f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(overlayWindowContext).apply {
            text = "两张队伍页面均已识别。请为这支队伍命名，保存后可在首页和计算页使用。"
            textSize = 15f
            setTextColor(Color.LTGRAY)
            setPadding(0, (12 * density).toInt(), 0, (8 * density).toInt())
        })
        val input = EditText(overlayWindowContext).apply {
            hint = "例如：雨天队"
            textSize = 18f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            maxLines = 1
            finishInputOnImeDone()
        }
        root.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
        val actions = LinearLayout(overlayWindowContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, (16 * density).toInt(), 0, 0)
        }
        actions.addView(Button(overlayWindowContext).apply {
            text = "稍后命名"
            setOnClickListener {
                dismissTeamNamePrompt()
                publish("队伍尚未保存；可再次从当前对局助手入口选择“为我的队伍命名并保存”")
            }
        })
        actions.addView(Button(overlayWindowContext).apply {
            text = "保存队伍"
            setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    input.error = "请输入队伍名称"
                    return@setOnClickListener
                }
                runCatching { importRepository.savePendingTeam(name) }
                    .onSuccess { saved ->
                        CaptureUiState.teamLibraryRevision.value += 1
                        dismissTeamNamePrompt()
                        publish(saved.message)
                    }
                    .onFailure { error ->
                        Log.e(LOG_TAG, "Could not save named own team", error)
                        input.error = "保存队伍失败，请重试"
                    }
            }
        })
        root.addView(actions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ))

        val bounds = safeArea.currentRegion(preferEnd = true).inset((24 * density).toInt())
        val params = WindowManager.LayoutParams(
            minOf((720 * density).toInt(), bounds.width.coerceAtLeast(1)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left + (bounds.width - width) / 2
            y = bounds.top
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        windowManager.addView(root, params)
        teamNamePrompt = root
        teamNamePromptParams = params
        root.post(::reflowTeamNamePrompt)
        input.requestFocus()
        mainHandler.postDelayed({
            if (!destroyed && teamNamePrompt === root) {
                getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 200)
    }

    private fun dismissTeamNamePrompt() {
        teamNamePrompt?.let { prompt ->
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(prompt.windowToken, 0)
            runCatching { windowManager.removeView(prompt) }
        }
        teamNamePrompt = null
        teamNamePromptParams = null
        showAssistantEntry()
    }

    private fun reflowTeamNamePrompt() {
        val prompt = teamNamePrompt ?: return
        val params = teamNamePromptParams ?: return
        val measuredHeight = prompt.height.coerceAtLeast(1)
        val reference = OverlayBounds(
            params.x,
            params.y,
            params.x + params.width.coerceAtLeast(1),
            params.y + measuredHeight,
        )
        val density = overlayWindowContext.resources.displayMetrics.density
        val bounds = safeArea.currentRegion(reference, preferEnd = true).inset((24 * density).toInt())
        params.width = minOf((720 * density).toInt(), bounds.width.coerceAtLeast(1))
        params.x = bounds.left + (bounds.width - params.width) / 2
        params.y = bounds.top + (bounds.height - measuredHeight).coerceAtLeast(0) / 2
        runCatching { windowManager.updateViewLayout(prompt, params) }
    }

    private fun publish(message: String) {
        if (destroyed) return
        Log.i(LOG_TAG, "publish: $message")
        mainHandler.post {
            if (destroyed) return@post
            CaptureUiState.message.value = message
            // 只保留顶部深色胶囊这一处提示：之前这里还发了系统 Toast，
            // 游戏画面上会同时出现「深色胶囊 + 带图标的白色气泡」，看起来像重复。
            showStatusChip(message)
        }
    }

    /** MIUI 常把后台 Toast 吞掉，这里额外在游戏画面上方显示一条短提示。 */
    private fun showStatusChip(message: String) {
        if (destroyed) return
        mainHandler.post {
            if (destroyed) return@post
            hideStatusChip()
            val density = resources.displayMetrics.density
            val chip = TextView(overlayWindowContext).apply {
                text = message
                textSize = 13f
                setTextColor(Color.WHITE)
                setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
                maxLines = 3
                background = GradientDrawable().apply {
                    setColor(Color.argb(235, 22, 30, 46))
                    cornerRadius = 12 * density
                    setStroke((1 * density).toInt().coerceAtLeast(1), Color.argb(200, 119, 215, 196))
                }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = (56 * density).toInt()
            }
            runCatching {
                windowManager.addView(chip, params)
                statusChip = chip
                mainHandler.postDelayed({ hideStatusChip() }, 3200)
            }.onFailure { Log.w(LOG_TAG, "Could not show status chip", it) }
        }
    }

    private fun hideStatusChip() {
        val chip = statusChip ?: return
        statusChip = null
        runCatching { windowManager.removeView(chip) }
    }

    private fun toast(message: String) {
        activeToast?.cancel()
        activeToast = Toast.makeText(this, message, Toast.LENGTH_LONG).also(Toast::show)
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "对局助手", NotificationManager.IMPORTANCE_LOW).apply {
                description = "点击悬浮按钮或 HUD 识别按钮时读取当前游戏画面并进行本地识别"
            }
        )
    }

    private fun releaseProjection() {
        activeToast?.cancel()
        activeToast = null
        cancelPendingFrameCapture()
        ownTeamRecognitionGeneration++
        clearOwnTeamRecognitionWatchdog(cancelTask = true)
        pendingCaptureResize?.let(mainHandler::removeCallbacks)
        pendingCaptureResize = null
        pendingCapturedContentSize = null
        frameTrackingEnabled = false
        captureGeneration += 1

        val activeProjection = projection
        val callback = projectionCallback
        val display = virtualDisplay
        val reader = imageReader
        val thread = imageThread
        val handler = imageHandler

        callback?.let { activeProjection?.unregisterCallback(it) }
        projectionCallback = null
        projection = null
        virtualDisplay = null
        imageReader = null
        imageThread = null
        imageHandler = null
        captureBufferSpec = null

        val cleanup = Runnable {
            runCatching { reader?.setOnImageAvailableListener(null, null) }
            runCatching { display?.setSurface(null) }
            runCatching { display?.release() }
            runCatching { reader?.close() }
        }
        if (handler != null && thread != null && Thread.currentThread() !== thread) {
            handler.removeCallbacksAndMessages(null)
            val finished = CountDownLatch(1)
            val posted = handler.post {
                try {
                    cleanup.run()
                } finally {
                    finished.countDown()
                }
            }
            if (!posted || !finished.await(5, TimeUnit.SECONDS)) {
                Log.e(LOG_TAG, "Timed out while serializing capture surface teardown")
            }
        } else {
            cleanup.run()
        }
        runCatching { activeProjection?.stop() }
        thread?.quitSafely()
        synchronized(bitmapLock) {
            latestBitmap?.recycle(); latestBitmap = null
            frozenMenuBitmap?.recycle(); frozenMenuBitmap = null
        }
    }

    private fun cancelPendingFrameCapture() {
        val pending = pendingFrameCapture ?: return
        pendingFrameCapture = null
        pending.cancel()
        mainHandler.removeCallbacks(pending)
        recognizing = false
    }

    private fun requestStop() {
        cancelPendingFrameCapture()
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacks(immediateOverlaySafeAreaRefresh)
        mainHandler.removeCallbacks(settledOverlaySafeAreaRefresh)
        if (::displayManager.isInitialized) {
            displayManager.unregisterDisplayListener(overlayDisplayListener)
        }
        windowInfoTracker?.let { tracker ->
            runCatching { tracker.removeWindowLayoutInfoListener(windowLayoutInfoListener) }
        }
        windowInfoTracker = null
        if (::overlayWindowContext.isInitialized) {
            overlayWindowContext.unregisterComponentCallbacks(overlayComponentCallbacks)
        }
        cancelPendingFrameCapture()
        ownTeamCorrectionController.close()
        battleOverlayController.closeAll()
        dismissTeamNamePrompt()
        hideStatusChip()
        removeBubble()
        bubbleParams = null
        releaseProjection()
        ocrEngine.close()
        paddleOcr?.close()
        damageRuntime.destroy()
        CaptureUiState.running.value = false
        CaptureUiState.message.value = "对局助手已结束"
        super.onDestroy()
    }
}

