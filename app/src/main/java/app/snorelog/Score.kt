package app.snorelog

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Sleep score 0-100 for one night.
 * score = 100 - SNORE_WEIGHT x (snoring % of recorded time) - loudness penalty (0..LOUD_MAX)
 * Loudness uses the 95th-percentile event peak, so one cough does not decide the night.
 */
object Score {
    const val SNORE_WEIGHT = 1.5
    const val LOUD_MAX = 15.0
    private const val QUIET_DB = -40.0   // 95th-percentile peak at or below this costs nothing
    private const val LOUD_DB = -10.0    // at or above this the full penalty applies
    private const val FALLBACK_REC_MIN = 420.0

    class Result(val score: Int, val snoreMin: Double, val recMin: Double, val pct: Double, val p95: Double, val snorePenalty: Int, val loudPenalty: Int)

    /** @param sessionMin minutes the microphone was recording this night (from sessions); 0 if unknown. */
    fun calc(events: List<Ev>, sessionMin: Double): Result {
        val snoreMin = events.sumOf { it.durMs } / 60000.0
        val rec = if (sessionMin >= 30.0 && sessionMin >= snoreMin) sessionMin else max(FALLBACK_REC_MIN, snoreMin)
        val pct = 100.0 * snoreMin / rec
        val peaks = events.map { it.peak }.sorted()
        val p95 = if (peaks.isEmpty()) -90.0 else peaks[min(peaks.size - 1, max(0, ceil(0.95 * peaks.size).toInt() - 1))]
        val sp = min(100.0, SNORE_WEIGHT * pct)
        val lp = LOUD_MAX * ((p95 - QUIET_DB) / (LOUD_DB - QUIET_DB)).coerceIn(0.0, 1.0)
        return Result((100.0 - sp - lp).coerceIn(0.0, 100.0).toInt(), snoreMin, rec, pct, p95, Math.round(sp).toInt(), Math.round(lp).toInt())
    }
}
