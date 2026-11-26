package app.snorelog

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.Locale

/** Tiny translator: English source text -> Polish when the phone language is Polish. Unknown text stays English. */
object L {
    private val pl = Locale("pl", "PL")
    @Volatile private var ready = false
    private val exact = HashMap<String, String>()
    private val upper = HashMap<String, String>()
    private val rules = ArrayList<Pair<Regex, String>>()

    fun init(ctx: Context) {
        mode = try { Settings(ctx).lang } catch (t: Throwable) { 0 }
        if (ready) return
        try {
            ctx.applicationContext.assets.open("pl.tsv").bufferedReader().useLines { ls ->
                for (l in ls) {
                    if (l.isBlank() || l.startsWith("#")) continue
                    val tab = l.indexOf('\t')
                    if (tab < 0) continue
                    val k = l.substring(0, tab)
                    val v = l.substring(tab + 1)
                    if (k.startsWith("~")) rules.add(Regex(k.substring(1)) to v)
                    else { exact[k] = v; upper[k.uppercase(Locale.ROOT)] = v.uppercase(pl) }
                }
            }
        } catch (t: Throwable) { }
        ready = true
    }

    @Volatile var mode = 0

    /** Locale for dates and day names: follows the app language, not only the phone language. */
    fun locale(): Locale = when (mode) { 1 -> Locale.ENGLISH; 2 -> pl; else -> Locale.getDefault() }

    private fun active() = mode == 2 || (mode == 0 && Locale.getDefault().language == "pl")

    fun t(s: String): String {
        if (s.isEmpty() || !ready || !active()) return s
        exact[s]?.let { return it }
        if (s.indexOf('\n') >= 0) return s.split("\n").joinToString("\n") { t(it) }
        upper[s]?.let { return it }
        for ((r, v) in rules) if (r.matches(s)) return r.replace(s, v)
        return s
    }

    fun f(fmt: String, vararg a: Any?): String = String.format(Locale.US, t(fmt), *a)

    /** Translates the text of every TextView (labels, buttons, switches, chips) under [v]. */
    fun walk(v: View?) {
        if (v == null || !ready || !active()) return
        if (v is TextView) {
            val cur = v.text?.toString() ?: ""
            if (cur.isNotEmpty()) { val n = t(cur); if (n != cur) v.text = n }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
    }
}
