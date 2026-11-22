package app.snorelog

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** ZIP backup of everything SnoreLog keeps: database (events, nights, pauses, sessions), clips and settings. */
object Backup {
    private const val DB = "snore.db"
    private val SKIP = setOf("ar", "lv") // armed state and last-seen version are not worth restoring

    fun clipsDir(c: Context) = File(c.getExternalFilesDir(null), "clips").also { it.mkdirs() }

    /** Writes the backup to [out]; returns the number of clips included. */
    fun write(c: Context, out: OutputStream): Int {
        // flush anything still in the write-ahead log into the main file, then copy it
        val d = Db(c)
        try { d.writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() } } finally { d.close() }
        var n = 0
        ZipOutputStream(out.buffered()).use { z ->
            z.setLevel(Deflater.BEST_SPEED)
            fun put(name: String, write: (ZipOutputStream) -> Unit) { z.putNextEntry(ZipEntry(name)); write(z); z.closeEntry() }
            put(DB) { s -> c.getDatabasePath(DB).inputStream().use { it.copyTo(s) } }
            val prefs = JSONObject()
            for ((k, v) in c.getSharedPreferences("s", Context.MODE_PRIVATE).all) {
                if (k in SKIP) continue
                prefs.put(k, JSONObject().put("t", when (v) { is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; else -> "s" }).put("v", v.toString()))
            }
            put("settings.json") { it.write(prefs.toString().toByteArray()) }
            // rejected clips (not fart events) are only for short-term review, so they are not backed up
            val skip = HashSet<String>()
            val d2 = Db(c)
            try { d2.readableDatabase.rawQuery("SELECT path FROM events WHERE rej=1 AND reason NOT LIKE 'fart%'", null).use { while (it.moveToNext()) skip.add(File(it.getString(0)).name) } } finally { d2.close() }
            clipsDir(c).listFiles()?.filter { it.isFile && it.name !in skip }?.forEach { f ->
                put("clips/" + f.name) { s -> f.inputStream().use { it.copyTo(s) } }; n++
            }
        }
        return n
    }

    /** Replaces the current data with the backup in [input]. Throws if it is not a SnoreLog backup. */
    fun restore(c: Context, input: InputStream): Int {
        val tmp = File(c.cacheDir, "restore").also { it.deleteRecursively(); it.mkdirs() }
        var hasDb = false; var prefsJson: String? = null; var n = 0
        try {
            ZipInputStream(input.buffered()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    val name = e.name
                    when {
                        name == DB -> { File(tmp, DB).outputStream().use { z.copyTo(it) }; hasDb = true }
                        name == "settings.json" -> prefsJson = z.readBytes().toString(Charsets.UTF_8)
                        name.startsWith("clips/") && !e.isDirectory -> {
                            val fn = name.removePrefix("clips/")
                            if (fn.isNotEmpty() && '/' !in fn && '\\' !in fn && fn != ".." && fn != ".") {
                                File(tmp, "clips").also { it.mkdirs() }.let { d -> File(d, fn).outputStream().use { z.copyTo(it) } }; n++
                            }
                        }
                    }
                    e = z.nextEntry
                }
            }
            if (!hasDb) throw IllegalArgumentException("not a SnoreLog backup")
            // database
            val dbFile = c.getDatabasePath(DB)
            c.deleteDatabase(DB)
            dbFile.parentFile?.mkdirs()
            File(tmp, DB).copyTo(dbFile, overwrite = true)
            // clips
            val dir = clipsDir(c)
            dir.listFiles()?.forEach { it.delete() }
            File(tmp, "clips").listFiles()?.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
            val d = Db(c)
            try { d.fixPaths(dir.absolutePath); d.writableDatabase.delete("events", "rej=1 AND reason NOT LIKE 'fart%'", null) } finally { d.close() }
            // settings
            prefsJson?.let { js ->
                val o = JSONObject(js)
                val ed = c.getSharedPreferences("s", Context.MODE_PRIVATE).edit()
                for (k in o.keys()) {
                    if (k in SKIP) continue
                    val x = o.getJSONObject(k); val v = x.getString("v")
                    when (x.getString("t")) {
                        "b" -> ed.putBoolean(k, v.toBoolean())
                        "i" -> ed.putInt(k, v.toInt())
                        "l" -> ed.putLong(k, v.toLong())
                        "f" -> ed.putFloat(k, v.toFloat())
                        else -> ed.putString(k, v)
                    }
                }
                ed.putBoolean("ar", false)
                ed.apply()
            }
        } finally { tmp.deleteRecursively() }
        return n
    }
}
