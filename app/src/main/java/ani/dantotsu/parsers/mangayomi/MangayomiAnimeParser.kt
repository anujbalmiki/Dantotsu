package ani.dantotsu.parsers.mangayomi

import android.app.Application
import android.content.Context
import ani.dantotsu.FileUrl
import ani.dantotsu.others.JsUnpacker
import ani.dantotsu.parsers.AnimeParser
import ani.dantotsu.parsers.Episode
import ani.dantotsu.parsers.ShowResponse
import ani.dantotsu.parsers.VideoExtractor
import ani.dantotsu.parsers.VideoServer
import ani.dantotsu.parsers.VideoServerPassthrough
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Track
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers.Companion.toHeaders
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** An anime source backed by a Mangayomi JavaScript extension. */
class MangayomiAnimeParser(private val source: MangayomiSource) : AnimeParser() {
    override val name = source.name
    override val saveName = "mangayomi_${source.id}"
    override val hostUrl = source.baseUrl
    override val iconUrl = source.iconUrl
    override val isNSFW = source.isNsfw

    private val engine by lazy {
        loadNative
        MangayomiJsEngine(json.encodeToString(MangayomiSource.serializer(), source), MangayomiExtensions.code(source.id), AndroidHost(source.id))
    }

    override suspend fun search(query: String): List<ShowResponse> {
        val page = decode<MPages>(engine.call("search", args(query, 1, null)))
        return page.list.map { item ->
            ShowResponse(item.name, item.link, FileUrl(item.imageUrl)).apply {
                // The extension tester reads sAnime straight off the first result.
                sAnime = SAnime.create().apply {
                    url = item.link
                    title = item.name
                    thumbnail_url = item.imageUrl
                }
            }
        }
    }

    override suspend fun loadEpisodes(
        animeLink: String,
        extra: Map<String, String>?,
        sAnime: SAnime
    ): List<Episode> {
        val detail = decode<MManga>(engine.call("getDetail", args(animeLink)))
        // Mangayomi lists newest first.
        return detail.chapters.asReversed().mapIndexed { i, ch ->
            val number = episodeNumber(ch.name) ?: (i + 1).toString()
            Episode(
                number = number,
                link = ch.url,
                title = ch.name.replace(EPISODE_PREFIX, "").trimStart(':', '-', ' ').ifBlank { null },
                thumbnail = ch.thumbnailUrl?.takeIf { it.isNotBlank() }?.let { FileUrl(it) },
                description = ch.description,
                isFiller = ch.isFiller == true,
                sEpisode = SEpisode.create().apply {
                    url = ch.url
                    name = ch.name
                    episode_number = number.toFloatOrNull() ?: (i + 1f)
                    date_upload = ch.dateUpload?.toLongOrNull() ?: 0L
                    scanlator = ch.scanlator?.takeIf { it.isNotBlank() }
                },
            )
        }
    }

    override suspend fun loadVideoServers(
        episodeLink: String,
        extra: Map<String, String>?,
        sEpisode: SEpisode
    ): List<VideoServer> {
        val videos = decode<List<MVideo>>(engine.call("getVideoList", args(episodeLink)))
            .filter { it.url.isNotBlank() }
        if (videos.isEmpty()) {
            // Extensions swallow their network errors, so say which request went wrong.
            val trace = engine.lastTrace
            Logger.log("Mangayomi $name: no videos for $episodeLink\n${trace.joinToString("\n")}")
            val failed = trace.filterNot { " -> 2" in it }
            throw MangayomiException(
                "$name found no videos. " + (failed.lastOrNull()
                    ?: "Requests: " + trace.joinToString(", ") { it.substringAfter("://").substringBefore("/") + it.substringAfter(" -> ", "").let { r -> " $r" } })
            )
        }
        return videos.map { v ->
            val headers = v.headers.orEmpty()
            val video = Video(
                url = v.originalUrl ?: v.url,
                quality = v.quality,
                videoUrl = v.url,
                headers = headers.toHeaders(),
                subtitleTracks = v.subtitles.orEmpty().map { Track(it.file, it.label) },
                audioTracks = v.audios.orEmpty().map { Track(it.file, it.label) },
            )
            VideoServer(v.quality, FileUrl(v.url, headers), null, video)
        }
    }

    override suspend fun getVideoExtractor(server: VideoServer): VideoExtractor =
        VideoServerPassthrough(server)

    private class AndroidHost(id: Long) : MangayomiHost {
        private val prefs = Injekt.get<Application>().getSharedPreferences("mangayomi_$id", Context.MODE_PRIVATE)
        override val http = Injekt.get<NetworkHelper>().client
        override fun prefGet(key: String): String? = prefs.getString(key, null)
        override fun prefSet(key: String, value: String) = prefs.edit().putString(key, value).apply()
        override fun log(message: String) = Logger.log("Mangayomi: $message")
        override fun unpack(packed: String) = JsUnpacker(packed).unpack() ?: packed
    }

    @Serializable
    private data class MPages(val list: List<MItem> = emptyList(), val hasNextPage: Boolean = false)

    @Serializable
    private data class MItem(val name: String = "", val imageUrl: String = "", val link: String = "")

    @Serializable
    private data class MManga(val name: String? = null, val chapters: List<MChapter> = emptyList())

    @Serializable
    private data class MChapter(
        val name: String = "",
        val url: String = "",
        val dateUpload: String? = null,
        val scanlator: String? = null,
        val isFiller: Boolean? = null,
        val thumbnailUrl: String? = null,
        val description: String? = null,
    )

    @Serializable
    private data class MTrack(val file: String = "", val label: String = "")

    @Serializable
    private data class MVideo(
        val url: String = "",
        val originalUrl: String? = null,
        val quality: String = "",
        val headers: Map<String, String>? = null,
        val subtitles: List<MTrack>? = null,
        val audios: List<MTrack>? = null,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
        private val EPISODE_PREFIX = Regex("""^\s*(?:episode|ep\.?|e)\s*\d+(?:\.\d+)?""", RegexOption.IGNORE_CASE)
        private val EPISODE_NUMBER = Regex("""\b(?:episode|ep\.?|e)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)

        /**
         * quickjs-kt ships libquickjs.so, the same file name as app.cash.quickjs, which Aniyomi
         * extensions need. So the app bundles it renamed (jniLibs/<abi>/libquickjskt.so) and loads
         * it here first; quickjs-kt's own loadLibrary("quickjs") then just loads the other one, and
         * its Java_com_dokar_* natives resolve from this library.
         */
        private val loadNative by lazy { System.loadLibrary("quickjskt") }

        private inline fun <reified T> decode(s: String): T = json.decodeFromString(s)

        private fun args(vararg values: Any?) = JsonArray(values.map {
            when (it) {
                null -> JsonNull
                is Number -> JsonPrimitive(it)
                else -> JsonPrimitive(it.toString())
            }
        })

        private fun episodeNumber(name: String): String? =
            (EPISODE_NUMBER.find(name) ?: Regex("""\d+(?:\.\d+)?""").find(name))
                ?.let { it.groupValues.getOrNull(1)?.takeIf { g -> g.isNotEmpty() } ?: it.value }
                ?.let { n -> n.toFloatOrNull()?.takeIf { it % 1f == 0f }?.toInt()?.toString() ?: n }
    }
}
