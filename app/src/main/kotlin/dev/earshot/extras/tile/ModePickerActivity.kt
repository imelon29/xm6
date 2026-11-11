package dev.earshot.extras.tile

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Window
import android.os.Process
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.earshot.appContainer
import dev.earshot.core.ModeMeaning
import dev.earshot.core.ModeResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Translucent activity instead of a system AlertDialog; volume overlay doesn't dismiss it. */
class ModePickerActivity : Activity() {
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun rounded(color: Int, radius: Int, stroke: Boolean = false) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke) setStroke(dp(1), Color.argb(90, 235, 241, 255))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the wallpaper and system bars visible behind this translucent Activity.
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND or
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        // Do not dim or blur the entire window: that can produce opaque black system bars.

        val headphones = applicationContext.appContainer.headphones
        val state = headphones.state.value
        if (!state.isConnected || !state.capabilities.listeningModes) { finish(); return }
        val options = listOf(
            ModeMeaning.QUIET to "Шумодав",
            ModeMeaning.AWARE to "Прозрачность",
            ModeMeaning.OFF to "Выкл."
        ).mapNotNull { (meaning, label) ->
            state.modes.firstOrNull { it.isConfigured && ModeResolver.meaningOf(it) == meaning }
                ?.let { it.index to label }
        }
        if (options.isEmpty()) { finish(); return }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(17), dp(16), dp(18))
            background = rounded(Color.argb(218, 49, 61, 83), 29, true)
            elevation = dp(10).toFloat()
            setOnClickListener { /* Keep panel open when tapping between controls. */ }
        }
        panel.addView(TextView(this).apply {
            text = "Sony XM6"
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(15))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEachIndexed { i, (modeIndex, label) ->
            val active = modeIndex == state.currentModeIndex
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = rounded(if (active) Color.argb(242, 220, 228, 242)
                                     else Color.argb(165, 113, 126, 151), 22, true)
                isClickable = true
                isFocusable = true
                contentDescription = label
            }
            tile.addView(TextView(this).apply {
                text = when (label) { "Шумодав" -> "◉"; "Прозрачность" -> "◎"; else -> "⊘" }
                textSize = 28f
                gravity = Gravity.CENTER
                setTextColor(if (active) Color.rgb(42, 53, 74) else Color.WHITE)
            })
            tile.addView(TextView(this).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(if (active) Color.rgb(42, 53, 74) else Color.WHITE)
                setPadding(0, dp(7), 0, 0)
                if (active) setTypeface(null, Typeface.BOLD)
            })
            tile.setOnClickListener {
                CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                    runCatching { headphones.setMode(modeIndex) }
                }
                finish()
            }
            row.addView(tile, LinearLayout.LayoutParams(0, dp(88), 1f).apply {
                if (i > 0) leftMargin = dp(7)
            })
        }
        panel.addView(row)
        root.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply {
            // Position relative to full display, aligned near the first row of home icons.
            topMargin = dp(65)
            leftMargin = dp(10)
            rightMargin = dp(10)
        })
        setContentView(root)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // No onPause/onWindowFocusChanged dismissal: volume slider may take temporary focus.
    }
}
