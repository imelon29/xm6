package dev.earshot.extras.tile

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
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
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(.10f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { window.setBackgroundBlurRadius(dp(30)) }
        }
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
            text = "Sony WH-1000XM6"
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
        })
        panel.addView(TextView(this).apply {
            text = "Управление звуком"
            textSize = 12f
            setTextColor(Color.rgb(211, 219, 233))
            setPadding(0, dp(3), 0, dp(15))
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
                setTextColor(if (active) Color.rgb(42, 54, 76) else Color.WHITE)
            })
            tile.addView(TextView(this).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(if (active) Color.rgb(42, 54, 76) else Color.WHITE)
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
            topMargin = dp(100)
            leftMargin = dp(10)
            rightMargin = dp(10)
        })
        setContentView(root)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // No onPause/onWindowFocusChanged dismissal: volume slider may take temporary focus.
    }
}
