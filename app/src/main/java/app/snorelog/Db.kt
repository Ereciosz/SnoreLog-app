package app.snorelog

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

object Cfg {
    const val MAX_ZCR = 0.2         // crude filter: snores are low-frequency
    const val END_QUIET_FRAMES = 30 // 3 s of quiet ends an event (100 ms frames)
    const val MAX_EVENT_FRAMES = 600
    const val SAMPLE_RATE = 16000
}

data class Ev(val id: Long, val ts: Long, val durMs: Long, val peak: Double, val mean: Double, val path: String,
              val cls: Double = -1.0, val rej: Boolean = false, val reason: String = "", val thr: Double = 0.0)

class Db(private val ctx: Context) : SQLiteOpenHelper(ctx, "snore.db", null, 4) {
    override fun onCreate(d: SQLiteDatabase) {
        d.execSQL("CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, dur INTEGER, peak REAL, mean REAL, path TEXT, " +
            // band: legacy column, no longer written or read (SQLite cannot drop it cheaply)
            "cls REAL DEFAULT -1, band REAL DEFAULT -1, rej INTEGER DEFAULT 0, reason TEXT DEFAULT '', lv TEXT DEFAULT '', thr REAL DEFAULT 0)")
        mk(d)
    }
    override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) {
        mk(d)
        if (o < 4) listOf("cls REAL DEFAULT -1", "band REAL DEFAULT -1", "rej INTEGER DEFAULT 0",
            "reason TEXT DEFAULT ''", "lv TEXT DEFAULT ''", "thr REAL DEFAULT 0").forEach {
            try { d.execSQL("ALTER TABLE events ADD COLUMN $it") } catch (t: Throwable) { }
        }
    }

    private fun mk(d: SQLiteDatabase) {
        d.execSQL("CREATE TABLE IF NOT EXISTS nights(k INTEGER PRIMARY KEY, tags TEXT)")
        d.execSQL("CREATE TABLE IF NOT EXISTS pauses(ts INTEGER, dur REAL)")
        d.execSQL("CREATE TABLE IF NOT EXISTS sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, s INTEGER, e INTEGER)")
    }

    fun insertPause(ts: Long, durS: Double) {
        writableDatabase.insert("pauses", null, ContentValues().apply { put("ts", ts); put("dur", durS) })
    }

    fun pauses(): List<Pair<Long, Double>> {
        val out = ArrayList<Pair<Long, Double>>()
        readableDatabase.rawQuery("SELECT ts,dur FROM pauses", null).use { while (it.moveToNext()) out.add(it.getLong(0) to it.getDouble(1)) }
        return out
    }

    fun startSession(t: Long): Long =
        writableDatabase.insert("sessions", null, ContentValues().apply { put("s", t); put("e", t) })

    fun endSession(id: Long, t: Long) {
        writableDatabase.execSQL("UPDATE sessions SET e=? WHERE id=?", arrayOf<Any>(t, id))
    }

    fun sessions(): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        readableDatabase.rawQuery("SELECT s,e FROM sessions", null).use { while (it.moveToNext()) out.add(it.getLong(0) to it.getLong(1)) }
        return out
    }

    fun allTags(): Map<Int, Set<String>> {
        val out = HashMap<Int, Set<String>>()
        readableDatabase.rawQuery("SELECT k,tags FROM nights", null).use {
            while (it.moveToNext()) out[it.getInt(0)] = it.getString(1).split(",").filter { s -> s.isNotEmpty() }.toSet()
        }
        return out
    }

    fun setTags(k: Int, t: Set<String>) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO nights(k,tags) VALUES(?,?)", arrayOf<Any>(k, t.joinToString(",")))
    }

    fun insert(ts: Long, dur: Long, peak: Double, mean: Double, path: String,
               cls: Double = -1.0, rej: Boolean = false, reason: String = "", lv: String = "", thr: Double = 0.0) {
        writableDatabase.insert("events", null, ContentValues().apply {
            put("ts", ts); put("dur", dur); put("peak", peak); put("mean", mean); put("path", path)
            put("cls", cls); put("rej", if (rej) 1 else 0); put("reason", reason); put("lv", lv); put("thr", thr)
        })
    }

    private fun query(where: String): List<Ev> {
        val out = ArrayList<Ev>()
        readableDatabase.rawQuery("SELECT id,ts,dur,peak,mean,path,cls,rej,reason,thr FROM events WHERE $where ORDER BY ts DESC", null).use {
            while (it.moveToNext()) out.add(Ev(it.getLong(0), it.getLong(1), it.getLong(2), it.getDouble(3), it.getDouble(4), it.getString(5),
                it.getDouble(6), it.getInt(7) == 1, it.getString(8) ?: "", it.getDouble(9)))
        }
        return out
    }

    fun all(): List<Ev> = query("rej=0")
    fun rejected(): List<Ev> = query("rej=1")

    /** Loudness per 100 ms frame (dBFS), stored as tenths of a dB. */
    fun levels(id: Long): FloatArray {
        readableDatabase.rawQuery("SELECT lv FROM events WHERE id=?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) {
                val t = it.getString(0) ?: ""
                if (t.isNotEmpty()) return t.split(",").mapNotNull { v -> v.toIntOrNull()?.let { x -> x / 10f } }.toFloatArray()
            }
        }
        return FloatArray(0)
    }

    fun purge(days: Int, rejDays: Int = 3) {
        val now = System.currentTimeMillis()
        val cond = "((rej=0 OR reason LIKE 'fart%') AND ts < ${now - days * 86_400_000L}) OR (rej=1 AND reason NOT LIKE 'fart%' AND ts < ${now - rejDays * 86_400_000L})"
        readableDatabase.rawQuery("SELECT path FROM events WHERE $cond", null).use {
            while (it.moveToNext()) File(it.getString(0)).delete()
        }
        writableDatabase.delete("events", cond, null)
    }

    fun deleteEvents(ids: List<Long>) {
        for (id in ids) {
            readableDatabase.rawQuery("SELECT path FROM events WHERE id=?", arrayOf(id.toString())).use {
                if (it.moveToFirst()) File(it.getString(0)).delete()
            }
            writableDatabase.delete("events", "id=?", arrayOf(id.toString()))
        }
    }

    /** After a restore: point every event at its clip in [dir] (the backup may come from another phone). */
    fun fixPaths(dir: String) {
        val d = writableDatabase
        val rows = ArrayList<Pair<Long, String>>()
        d.rawQuery("SELECT id, path FROM events", null).use { c -> while (c.moveToNext()) rows.add(c.getLong(0) to c.getString(1)) }
        d.beginTransaction()
        try {
            for ((id, path) in rows) d.execSQL("UPDATE events SET path=? WHERE id=?", arrayOf<Any>(dir + "/" + File(path).name, id))
            d.setTransactionSuccessful()
        } finally { d.endTransaction() }
    }

    fun deleteRejected() {
        readableDatabase.rawQuery("SELECT path FROM events WHERE rej=1 AND reason NOT LIKE 'fart%'", null).use {
            while (it.moveToNext()) File(it.getString(0)).delete()
        }
        writableDatabase.delete("events", "rej=1 AND reason NOT LIKE 'fart%'", null)
    }
}

class Settings(c: Context) {
    private val p = c.getSharedPreferences("s", Context.MODE_PRIVATE)

    var margin: Int
        get() = p.getInt("m", 12)
        set(v) { p.edit().putInt("m", v).apply() }

    var nudge: Boolean
        get() = p.getBoolean("n", false)
        set(v) { p.edit().putBoolean("n", v).apply() }

    var apnea: Boolean
        get() = p.getBoolean("a", false)
        set(v) { p.edit().putBoolean("a", v).apply() }

    var themeMode: Int // 0 system, 1 light, 2 dark, 3 OLED black
        get() = p.getInt("tm", 0)
        set(v) { p.edit().putInt("tm", v).apply() }

    var contrast: Boolean
        get() = p.getBoolean("hc", false)
        set(v) { p.edit().putBoolean("hc", v).apply() }

    var scrollToEvent: Boolean // when a clip starts from the timeline or "play loudest", scroll to its row in the Events list
        get() = p.getBoolean("ste", false)
        set(v) { p.edit().putBoolean("ste", v).apply() }

    var trendScore: Boolean // Trends charts: false = minutes of snoring, true = sleep score
        get() = p.getBoolean("trs", false)
        set(v) { p.edit().putBoolean("trs", v).apply() }

    var statsDays: Int // Stats tab period: 7, 30, 90 days, 0 = everything that is kept
        get() = p.getInt("sd", 30)
        set(v) { p.edit().putInt("sd", v).apply() }

    var dynamic: Boolean
        get() = p.getBoolean("dy", true)
        set(v) { p.edit().putBoolean("dy", v).apply() }

    var accent: Int
        get() = p.getInt("ac", 0)
        set(v) { p.edit().putInt("ac", v).apply() }

    var armed: Boolean
        get() = p.getBoolean("ar", false)
        set(v) { p.edit().putBoolean("ar", v).apply() }

    var watchdog: Boolean
        get() = p.getBoolean("wd", true)
        set(v) { p.edit().putBoolean("wd", v).apply() }

    var keepRej: Boolean
        get() = p.getBoolean("kr", true)
        set(v) { p.edit().putBoolean("kr", v).apply() }

    var retention: Int // days
        get() = p.getInt("rt", 14)
        set(v) { p.edit().putInt("rt", v).apply() }

    var heatGuard: Boolean
        get() = p.getBoolean("hg", true)
        set(v) { p.edit().putBoolean("hg", v).apply() }

    var heatLimit: Int // battery temperature, deg C
        get() = p.getInt("hl", 42)
        set(v) { p.edit().putInt("hl", v).apply() }

    var aiPct: Int // YAMNet snoring score (%) needed to keep an event
        get() = p.getInt("ai", 25)
        set(v) { p.edit().putInt("ai", v).apply() }

    var boostDb: Int // playback gain in dB (LoudnessEnhancer)
        get() = p.getInt("bd", 12)
        set(v) { p.edit().putInt("bd", v).apply() }

    var lang: Int // 0 = follow phone, 1 = English, 2 = Polish
        get() = p.getInt("lg", 0)
        set(v) { p.edit().putInt("lg", v).apply() }

    var fart: Boolean
        get() = p.getBoolean("ft", false)
        set(v) { p.edit().putBoolean("ft", v).apply() }

    var lastVer: Int
        get() = p.getInt("lv", 0)
        set(v) { p.edit().putInt("lv", v).apply() }

    var battIntro: Boolean
        get() = p.getBoolean("bi", false)
        set(v) { p.edit().putBoolean("bi", v).apply() }

    var battNever: Boolean
        get() = p.getBoolean("bn", false)
        set(v) { p.edit().putBoolean("bn", v).apply() }

    var batteryMode: Boolean
        get() = p.getBoolean("bm", false)
        set(v) { p.edit().putBoolean("bm", v).apply() }

    var batteryFloor: Int // percent
        get() = p.getInt("bf", 25)
        set(v) { p.edit().putInt("bf", v).apply() }

    var alarmLimit: Int // minutes of continuous screen-on that mean "I am up" (after the alarm, or after sleeping if no alarm)
        get() = p.getInt("alm", 15).let { if (it <= 0) 15 else it }
        set(v) { p.edit().putInt("alm", v).apply() }

    var alarmOn: Boolean // disarm after the Clock alarm (master switch)
        get() = p.getBoolean("alo", false)
        set(v) { p.edit().putBoolean("alo", v).apply() }

    /** What unplugging does: 0 = pause and resume on charge, 1 = disarm if it happens after the Clock alarm, 2 = always disarm. */
    var unplugMode: Int
        get() = if (p.contains("um")) p.getInt("um", 1) else if (!p.getBoolean("sa", true)) 2 else 1
        set(v) { p.edit().putInt("um", v).apply() }

    var screenOffOnly: Boolean
        get() = p.getBoolean("so", false)
        set(v) { p.edit().putBoolean("so", v).apply() }

    var screenDelay: Int // minutes after screen off before recording starts
        get() = p.getInt("sd", 1)
        set(v) { p.edit().putInt("sd", v).apply() }

}
