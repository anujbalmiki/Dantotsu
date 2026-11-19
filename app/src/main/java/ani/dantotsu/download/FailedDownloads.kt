package ani.dantotsu.download

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.download.manga.MangaDownloaderService
import ani.dantotsu.download.manga.MangaServiceDataSingleton
import ani.dantotsu.media.MediaType
import ani.dantotsu.parsers.DynamicMangaParser
import ani.dantotsu.parsers.MangaSources
import ani.dantotsu.settings.saving.PrefManager
import eu.kanade.tachiyomi.source.model.SChapterImpl
import org.json.JSONArray
import org.json.JSONObject

/** Persisted list of downloads that failed, so the queue screen can show and retry them. */
object FailedDownloads {
    private const val KEY = "failed_downloads"
    private const val MAX = 50

    data class Entry(
        val type: MediaType,
        val title: String,
        val item: String, // chapter or episode name
        val mediaId: Int,
        val source: String = "", // extension name
        val sourceLang: Int = 0,
        val chapterUrl: String = "",
        val scanlator: String = "Unknown",
        val time: Long = System.currentTimeMillis(),
        val error: String = "",
    ) {
        fun matches(type: MediaType, title: String, item: String) =
            this.type == type && this.title == title && this.item == item
    }

    @Synchronized
    fun all(): List<Entry> = try {
        val arr = JSONArray(PrefManager.getCustomVal(KEY, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry(
                MediaType.valueOf(o.getString("type")), o.getString("title"), o.getString("item"),
                o.getInt("mediaId"), o.optString("source"), o.optInt("sourceLang"),
                o.optString("chapterUrl"), o.optString("scanlator", "Unknown"),
                o.optLong("time"), o.optString("error")
            )
        }
    } catch (_: Exception) {
        emptyList() // corrupt value: start over rather than crash
    }

    private fun save(list: List<Entry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("type", it.type.name).put("title", it.title).put("item", it.item)
                    .put("mediaId", it.mediaId).put("source", it.source).put("sourceLang", it.sourceLang)
                    .put("chapterUrl", it.chapterUrl).put("scanlator", it.scanlator)
                    .put("time", it.time).put("error", it.error)
            )
        }
        PrefManager.setCustomVal(KEY, arr.toString())
    }

    @Synchronized
    fun add(entry: Entry) {
        val list = all().filterNot { it.matches(entry.type, entry.title, entry.item) }
        save((listOf(entry) + list).take(MAX))
    }

    @Synchronized
    fun remove(type: MediaType, title: String, item: String) {
        val list = all()
        val kept = list.filterNot { it.matches(type, title, item) }
        if (kept.size != list.size) save(kept)
    }

    @Synchronized
    fun clear() = save(emptyList())

    /**
     * Re-resolves the chapter's pages (old image URLs expire) and queues a fresh manga task.
     * Returns false when that is not possible (extension gone, no network), so the caller can
     * open the title page instead.
     */
    suspend fun retryManga(context: Context, e: Entry): Boolean {
        if (e.chapterUrl.isEmpty()) return false
        val parser = MangaSources.list.firstOrNull { it.name == e.source }?.get?.value
            as? DynamicMangaParser ?: return false
        // The service rewrites media.json from this on success, so it must be a real Media.
        val media = Anilist.query.getMedia(e.mediaId) ?: return false
        val oldLang = parser.sourceLanguage
        val images = try {
            parser.sourceLanguage = e.sourceLang
            parser.imageList(SChapterImpl().apply { url = e.chapterUrl; name = e.item })
        } finally {
            parser.sourceLanguage = oldLang
        }
        if (images.isEmpty()) return false
        remove(e.type, e.title, e.item)
        MangaServiceDataSingleton.downloadQueue.offer(
            MangaDownloaderService.DownloadTask(
                title = e.title,
                chapter = e.item,
                scanlator = e.scanlator,
                imageData = images,
                sourceMedia = media,
                retries = 25,
                simultaneousDownloads = 2,
                chapterUrl = e.chapterUrl,
                sourceName = e.source,
                sourceLang = e.sourceLang,
            )
        )
        ContextCompat.startForegroundService(context, Intent(context, MangaDownloaderService::class.java))
        MangaServiceDataSingleton.isServiceRunning = true
        return true
    }
}
