package app.snorelog

import android.app.Application
import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small file log (logs/diag.log) used to find out why monitoring stops. */
object Diag {
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    fun file(ctx: Context): File = File(File(ctx.getExternalFilesDir(null), "logs").also { it.mkdirs() }, "diag.log")

    @Synchronized
    fun log(ctx: Context, msg: String) {
        try {
            val f = file(ctx)
            if (f.length() > 200_000) {
                val old = File(f.parentFile, "diag.old.log")
                old.delete(); f.renameTo(old)
            }
            f.appendText(fmt.format(Date()) + " " + msg + "\n")
        } catch (t: Throwable) { /* never crash because of logging */ }
    }

    fun read(ctx: Context, maxLines: Int): String = try {
        val f = file(ctx)
        if (f.exists()) f.readLines().takeLast(maxLines).joinToString("\n") else "(log is empty)"
    } catch (t: Throwable) { "(cannot read log)" }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        L.init(this)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Diag.log(this@App, "CRASH in thread ${t.name}: " + e.stackTraceToString().take(1500))
            prev?.uncaughtException(t, e)
        }
    }
}
