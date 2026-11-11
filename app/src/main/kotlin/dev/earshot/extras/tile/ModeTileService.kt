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

    /** Compact dark mode selector; intentionally uses platform views so no Activity is launched. */
    private fun createModeDialog(
        options: List<Pair<Int, String>>,
        selected: Int,
        onModeSelected: (Int) -> Unit
    ): Dialog {
        val dialog = Dialog(this)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(18))
            background = rounded(Color.rgb(27, 32, 45), 26)
        }
        val title = TextView(this).apply {
            text = "Sony WH-1000XM6"
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        }
        panel.addView(title)
        val subtitle = TextView(this).apply {
            text = "Управление звуком"
            textSize = 12f
            setTextColor(Color.rgb(174, 184, 204))
            setPadding(0, dp(3), 0, dp(18))
        }
        panel.addView(subtitle)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val icons = mapOf(
            ModeMeaning.QUIET to "◉",
            ModeMeaning.AWARE to "◎",
            ModeMeaning.OFF to "⊘"
        )
        options.forEachIndexed { i, (index, label) ->
            val meaning = when (label) {
                "Шумоподавление" -> ModeMeaning.QUIET
                "Прозрачность" -> ModeMeaning.AWARE
                else -> ModeMeaning.OFF
            }
            val active = i == selected
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = dp(96)
                setPadding(dp(3), dp(12), dp(3), dp(12))
                background = rounded(
                    if (active) Color.rgb(89, 104, 206) else Color.rgb(52, 57, 75), 17
                )
                isClickable = true
                isFocusable = true
                contentDescription = label
            }
            tile.addView(TextView(this).apply {
                text = icons[meaning] ?: "○"
                textSize = 29f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            })
            tile.addView(TextView(this).apply {
                text = when (meaning) {
                    ModeMeaning.QUIET -> "Шумодав"
                    ModeMeaning.AWARE -> "Прозрачность"
                    else -> "Выкл."
                }
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(0, dp(8), 0, 0)
                if (active) setTypeface(null, Typeface.BOLD)
            })
            tile.setOnClickListener {
                dialog.dismiss()
                onModeSelected(index)
            }
            row.addView(tile, LinearLayout.LayoutParams(0, dp(100), 1f).apply {
                if (i > 0) leftMargin = dp(7)
            })
        }
        panel.addView(row)
        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setDimAmount(0.35f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
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
