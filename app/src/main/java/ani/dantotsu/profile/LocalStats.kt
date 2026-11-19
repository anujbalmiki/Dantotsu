package ani.dantotsu.profile

import ani.dantotsu.settings.saving.PrefManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/**
 * On-device watch/read time. Anime counts only while playing; manga counts while the reader is
 * resumed and the user interacted within IDLE_MS. Persisted as one JSON string in prefs:
 * {"d": {dayNumber: [animeSec, mangaSec]}, "t": {"a:id"|"m:id": [title, sec]}, "tot": [animeSec, mangaSec]}
 */
object LocalStats {
    private const val KEY = "local_stats"
    private const val IDLE_MS = 5 * 60 * 1000L
    private const val MAX_DAYS = 365
    private const val MAX_TITLES = 300

    private var root: JSONObject? = null
    private var isAnime = false
    private var mediaId = 0
    private var title = ""
    private var active = false
    private var lastTick = 0L
    private var lastTouch = 0L

    private fun day(now: Long) = (now + TimeZone.getDefault().getOffset(now)) / 86_400_000L

    @Synchronized
    private fun data(): JSONObject = root ?: try {
        JSONObject(PrefManager.getCustomVal(KEY, "{}"))
    } catch (e: Exception) {
        JSONObject() // corrupt blob: start over rather than crash the player/reader
    }.also { root = it }

    /** Start or continue counting for this title; playing=false pauses counting. */
    @Synchronized
    fun track(anime: Boolean, id: Int, name: String, playing: Boolean) {
        val now = System.currentTimeMillis()
        credit(now)
        isAnime = anime; mediaId = id; title = name
        active = playing
        lastTick = now
        if (playing) lastTouch = now
    }

    /** User interacted (e.g. page turn). */
    @Synchronized
    fun touch() {
        val now = System.currentTimeMillis()
        credit(now)
        lastTick = now
        lastTouch = now
    }

    /** Stop counting and write to disk. */
    @Synchronized
    fun pause() {
        credit(System.currentTimeMillis())
        active = false
        save()
    }

    private fun credit(now: Long) {
        if (!active || mediaId == 0) return
        // anime has no idle limit (playback is the signal); reader credits only up to lastTouch + IDLE
        val until = if (isAnime) now else minOf(now, lastTouch + IDLE_MS)
        val secs = ((until - lastTick) / 1000L).toInt()
        if (secs > 0) add(now, secs)
        lastTick = now
    }

    private fun add(now: Long, secs: Int) {
        val r = data()
        val i = if (isAnime) 0 else 1
        val d = r.optJSONObject("d") ?: JSONObject().also { r.put("d", it) }
        val dk = day(now).toString()
        val da = d.optJSONArray(dk) ?: JSONArray().put(0).put(0).also { d.put(dk, it) }
        da.put(i, da.optInt(i) + secs)
        val tot = r.optJSONArray("tot") ?: JSONArray().put(0L).put(0L).also { r.put("tot", it) }
        tot.put(i, tot.optLong(i) + secs)
        val t = r.optJSONObject("t") ?: JSONObject().also { r.put("t", it) }
        val tk = "${if (isAnime) "a" else "m"}:$mediaId"
        val ta = t.optJSONArray(tk) ?: JSONArray().put(title).put(0)
        ta.put(0, title).put(1, ta.optInt(1) + secs)
        t.put(tk, ta)
    }

    private fun save() {
        val r = data()
        r.optJSONObject("d")?.let { d ->
            if (d.length() > MAX_DAYS) d.keys().asSequence().map { it.toLong() }.sorted()
                .take(d.length() - MAX_DAYS).toList().forEach { d.remove(it.toString()) }
        }
        r.optJSONObject("t")?.let { t ->
            if (t.length() > MAX_TITLES) t.keys().asSequence().sortedBy { t.getJSONArray(it).optInt(1) }
                .take(t.length() - MAX_TITLES).toList().forEach { t.remove(it) }
        }
        PrefManager.setCustomVal(KEY, r.toString())
    }

    class Summary(val today: Long, val week: Long, val month: Long, val all: Long)

    @Synchronized
    fun summary(anime: Boolean): Summary {
        val r = data()
        val i = if (anime) 0 else 1
        val dn = day(System.currentTimeMillis())
        var today = 0L
        var week = 0L
        var month = 0L
        r.optJSONObject("d")?.let { d ->
            d.keys().forEach { k ->
                val age = dn - k.toLong()
                val v = d.getJSONArray(k).optLong(i)
                if (age == 0L) today += v
                if (age in 0..6) week += v
                if (age in 0..29) month += v
            }
        }
        return Summary(today, week, month, r.optJSONArray("tot")?.optLong(i) ?: 0L)
    }

    /** Top titles as (name, seconds), anime and manga merged. */
    @Synchronized
    fun top(n: Int): List<Pair<String, Int>> {
        val t = data().optJSONObject("t") ?: return emptyList()
        return t.keys().asSequence().map { t.getJSONArray(it) }
            .map { it.optString(0) to it.optInt(1) }
            .sortedByDescending { it.second }.take(n).toList()
    }
}
