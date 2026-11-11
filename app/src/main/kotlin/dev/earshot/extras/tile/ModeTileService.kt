package dev.earshot.extras.tile

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import dev.earshot.MainActivity
import dev.earshot.appContainer
import dev.earshot.core.AppPreferences
import dev.earshot.core.ConnectionStatus
import dev.earshot.core.HeadphoneState
import dev.earshot.core.ModeMeaning
import dev.earshot.core.ModeResolver
import dev.earshot.extras.ExtrasInitializer
import dev.earshot.extras.HeadphoneLabels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: shows connection + current mode, tap opens a mode picker (or connects).
 * Long-press opens the app (system default for a tile whose service has a launcher activity).
 *
 * Declared as an *active* tile in the manifest, so the system only binds us while the panel
 * is open or when [requestRefresh] asks; between those windows the tile keeps the last state
 * we wrote. A mode switch never leaves the shade; only on a model without listening modes, where
 * there is nothing to cycle, does a tap open the app (`startActivityAndCollapse`).
 */
class ModeTileService : TileService() {
    private var listening: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val container = applicationContext.appContainer
        ExtrasInitializer.start(applicationContext, container) // in case the tile is the first thing to wake the process
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { listening = it }
        scope.launch {
            combine(container.headphones.state, container.automation.preferences) { s, p -> s to p }
                .collect { (state, prefs) -> render(state, cycleOrder(prefs, state)) }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val container = applicationContext.appContainer
        val headphones = container.headphones
        val state = headphones.state.value
        if (!state.isConnected) {
            if (state.connection.status != ConnectionStatus.CONNECTING) headphones.connect()
            return
        }
        // A model without listening modes (NC 700, basic-tier firmware) has nothing to cycle:
        // cycling the QC Ultra's preset slots there would only send commands that fail. Open the
        // app instead, where its controls are.
        if (!state.capabilities.listeningModes) {
            openApp()
            return
        }
        // Open a three-way mode picker directly over Quick Settings.
        // Use the actual supported mode indices, not a cycle command.
        val options = listOf(
            ModeMeaning.QUIET to "Шумоподавление",
            ModeMeaning.AWARE to "Прозрачность",
            ModeMeaning.OFF to "Выключено"
        ).mapNotNull { (meaning, label) ->
            state.modes.firstOrNull { it.isConfigured && ModeResolver.meaningOf(it) == meaning }
                ?.let { it.index to label }
        }
        if (options.isEmpty()) {
            openApp()
            return
        }
        val selected = options.indexOfFirst { it.first == state.currentModeIndex }
        val dialog = createModeDialog(options, selected) { index ->
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                runCatching { headphones.setMode(index) }
                    .onFailure { Log.w(TAG, "Tile setMode($index) failed: ${it.message}") }
            }
        }
        runCatching { showDialog(dialog) }
            .onFailure { Log.w(TAG, "Cannot show mode picker: ${it.message}"); openApp() }
    }

    /** OriginOS-inspired floating selector, anchored near the first home-screen icon row. */
    private fun createModeDialog(
        options: List<Pair<Int, String>>,
        selected: Int,
        onModeSelected: (Int) -> Unit
    ): Dialog {
        val dialog = Dialog(this)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        fun shape(color: Int, radius: Int, border: Int? = null): GradientDrawable =
            GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(radius).toFloat()
                border?.let { setStroke(dp(1), it) }
            }

        // Muted, translucent glass colors similar to the OriginOS quick-settings tiles.
        val glass = Color.argb(225, 49, 61, 83)
        val inactive = Color.argb(185, 102, 113, 139)
        val active = Color.argb(245, 218, 226, 241)
        val outline = Color.argb(85, 229, 237, 255)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(17), dp(16), dp(17))
            background = shape(glass, 29, outline)
        }
        panel.addView(TextView(this).apply {
            text = "Sony WH-1000XM6"
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        })
        panel.addView(TextView(this).apply {
            text = "Управление звуком"
            textSize = 12f
            setTextColor(Color.rgb(206, 215, 230))
            setPadding(0, dp(3), 0, dp(15))
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        options.forEachIndexed { i, (index, label) ->
            val isActive = i == selected
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(10), dp(2), dp(10))
                background = shape(if (isActive) active else inactive, 22, outline)
                isClickable = true
                isFocusable = true
                contentDescription = label
            }
            tile.addView(TextView(this).apply {
                text = when (label) {
                    "Шумоподавление" -> "◉"
                    "Прозрачность" -> "◎"
                    else -> "⊘"
                }
                textSize = 29f
                gravity = Gravity.CENTER
                setTextColor(if (isActive) Color.rgb(45, 57, 79) else Color.WHITE)
            })
            tile.addView(TextView(this).apply {
                text = when (label) {
                    "Шумоподавление" -> "Шумодав"
                    "Прозрачность" -> "Прозрачность"
                    else -> "Выкл."
                }
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(if (isActive) Color.rgb(45, 57, 79) else Color.WHITE)
                setPadding(0, dp(7), 0, 0)
                if (isActive) setTypeface(null, Typeface.BOLD)
            })
            tile.setOnClickListener {
                dialog.dismiss()
                onModeSelected(index)
            }
            row.addView(tile, LinearLayout.LayoutParams(0, dp(88), 1f).apply {
                if (i > 0) leftMargin = dp(7)
            })
        }
        panel.addView(row)
        dialog.setContentView(panel)
        // Do not close merely because a volume overlay appears or the user touches outside.
        // Back still closes the picker; choosing a mode closes it intentionally.
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(0.12f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            // Window y is measured below the status bar. Approximately aligns with
            // the top of the first row of home-screen folders on vivo X300.
            attributes = attributes.apply { y = dp(58) }
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { setBackgroundBlurRadius(dp(24)) }
            }
        }
        return dialog
    }

    /**
     * Opens Earshot from a tap (only used for models without modes). Android 14 takes a
     * PendingIntent here; older versions the deprecated Intent overload.
     */
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(intent)
            }
        }.onFailure { Log.w(TAG, "Tile could not open the app: ${it.message}") }
    }

    /** The slots a tap cycles through: only ones with a real name, so the tile never lands on "Mode 5". */
    private fun cycleOrder(prefs: AppPreferences, state: HeadphoneState): List<Int> =
        TileCycle.order(prefs, state).filter { HeadphoneLabels.modeName(state, it) != null }

    private fun render(state: HeadphoneState, cycle: List<Int>) {
        val tile = qsTile ?: return
        val connected = state.isConnected
        val name = HeadphoneLabels.displayName(state)
        val buds = HeadphoneLabels.isEarbuds(HeadphoneLabels.formFactor(state))
        val label: String
        val subtitle: String?
        when {
            connected -> {
                label = state.currentMode?.name?.takeIf { it.isNotBlank() }
                    ?: name ?: if (buds) "Earbuds" else "Headphones"
                subtitle = HeadphoneLabels.batteryShort(state) ?: name
            }
            state.connection.status == ConnectionStatus.CONNECTING -> { label = "Connecting…"; subtitle = null }
            state.connection.status == ConnectionStatus.NO_PERMISSION -> { label = "Connect"; subtitle = "Open Earshot" }
            else -> { label = "Connect"; subtitle = name }
        }
        // From the resolved order, not the saved list: with nothing chosen the favourites or the
        // first modes still cycle, and a choice made on another headset may resolve to nothing here.
        val canCycle = connected && state.capabilities.listeningModes && cycle.any { it != state.currentModeIndex }
        tile.state = when {
            connected -> Tile.STATE_ACTIVE
            state.connection.status == ConnectionStatus.NO_PAIRED_DEVICE -> Tile.STATE_UNAVAILABLE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = label
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = subtitle
        tile.contentDescription = buildString {
            append(label); subtitle?.let { append(", ").append(it) }
            if (canCycle) append(". Tap to choose a mode")
            if (connected && !state.capabilities.listeningModes) append(". Tap to open Earshot")
        }
        runCatching { tile.updateTile() }
    }

    companion object {
        private const val TAG = "Earshot/Tile"

        /** Ask the system to bind the tile so [onStartListening] re-renders it with fresh state. */
        fun requestRefresh(context: Context) {
            runCatching {
                requestListeningState(context.applicationContext, ComponentName(context.applicationContext, ModeTileService::class.java))
            }
        }
    }
}
