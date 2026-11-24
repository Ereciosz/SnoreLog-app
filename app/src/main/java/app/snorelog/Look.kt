package app.snorelog

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors

/**
 * Appearance: theme mode (0 system, 1 light, 2 dark, 3 OLED black), accent / dynamic colours and the
 * high-contrast option. The flags below are read by the custom chart views.
 */
object Look {
    const val OLED = 3
    @Volatile var oled = false
    @Volatile var contrast = false
    @Volatile var night = true

    private val ACCENT = listOf(R.style.AccentOrange, R.style.AccentTeal, R.style.AccentViolet, R.style.AccentGreen)
    private val HC_LIGHT = listOf(R.style.HcLightOrange, R.style.HcLightTeal, R.style.HcLightViolet, R.style.HcLightGreen)
    private val HC_DARK = listOf(R.style.HcDarkOrange, R.style.HcDarkTeal, R.style.HcDarkViolet, R.style.HcDarkGreen)

    fun delegateMode(mode: Int) = when (mode) {
        1 -> AppCompatDelegate.MODE_NIGHT_NO
        2, OLED -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    private fun isNight(ctx: Context, mode: Int) = when (mode) {
        1 -> false
        2, OLED -> true
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    /** Call in onCreate before super.onCreate. */
    fun apply(a: Activity, st: Settings) {
        AppCompatDelegate.setDefaultNightMode(delegateMode(st.themeMode))
        val night = isNight(a, st.themeMode)
        Look.night = night; oled = st.themeMode == OLED; contrast = st.contrast
        val acc = st.accent.coerceIn(0, ACCENT.size - 1)
        if (!st.contrast && st.dynamic && DynamicColors.isDynamicColorAvailable()) DynamicColors.applyToActivityIfAvailable(a)
        else a.theme.applyStyle(ACCENT[acc], true)
        if (oled) a.theme.applyStyle(R.style.OledBlack, true)
        if (st.contrast) a.theme.applyStyle((if (night) HC_DARK else HC_LIGHT)[acc], true)
    }
}
