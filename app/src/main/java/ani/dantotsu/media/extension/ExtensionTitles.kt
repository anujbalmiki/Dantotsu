package ani.dantotsu.media.extension

import android.app.Application
import android.content.Context
import android.content.Intent
import ani.dantotsu.R
import ani.dantotsu.connections.anilist.Anilist
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.media.Selected
import ani.dantotsu.media.anime.Anime
import ani.dantotsu.media.manga.Manga
import ani.dantotsu.parsers.AnimeSources
import ani.dantotsu.parsers.BaseSources
import ani.dantotsu.parsers.MangaSources
import ani.dantotsu.parsers.ShowResponse
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.snackString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/** A title opened straight from an extension, for titles AniList does not have. */
@Serializable
data class ExtensionTitle(
    val id: Int,
    val anime: Boolean,
    val source: String,
    val name: String,
    val link: String,
    val cover: String? = null,
    val progress: Int? = null,
    val lastOpened: Long = 0,
)

/**
 * The phone-side library for [ExtensionTitle]s. They get negative media ids (AniList ids are
 * positive, local files use 0), which the details screen, progress and comments check for.
 */
object ExtensionTitles {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ExtensionTitle.serializer())
    private val file get() = File(Injekt.get<Application>().filesDir, "extension_titles.json")

    private val _all = MutableStateFlow(load())
    val all = _all.asStateFlow()

    private fun load(): List<ExtensionTitle> =
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyList())

    @Synchronized
    private fun save(list: List<ExtensionTitle>) {
        file.writeText(json.encodeToString(serializer, list))
        _all.value = list
    }

    /**
     * Stable per source and link. At or below -1000: the details screen uses -1 for "none" and
     * home uses -100 and down for loading placeholders.
     */
    fun idFor(anime: Boolean, source: String, link: String): Int =
        -(("$anime|$source|$link".hashCode() and 0x3fffffff) + 1000)

    fun get(id: Int) = _all.value.find { it.id == id }

    fun remove(id: Int) = save(_all.value.filterNot { it.id == id })

    fun setProgress(id: Int, progress: Int) {
        val t = get(id) ?: return
        val updated = t.copy(progress = progress, lastOpened = System.currentTimeMillis())
        save(listOf(updated) + _all.value.filterNot { it.id == id })
    }

    fun sources(anime: Boolean): BaseSources = if (anime) AnimeSources else MangaSources

    /** Extension sources only: not torrents, local files or downloads. */
    fun sourceNames(anime: Boolean) =
        sources(anime).names.filterNot { it in setOf("Torrent", "Local", "Downloaded") }

    fun open(context: Context, anime: Boolean, source: String, response: ShowResponse) {
        val old = _all.value.find { it.anime == anime && it.source == source && it.link == response.link }
        val id = old?.id ?: idFor(anime, source, response.link)
        val sources = sources(anime)
        val index = sources.names.indexOf(source)
        if (index < 0) {
            snackString(context.getString(R.string.source_not_installed, source))
            return
        }
        // Save what the user picked, so the source loads this exact title instead of searching.
        sources.saveResponse(index, id, response)
        val title = ExtensionTitle(
            id, anime, source, response.name, response.link,
            response.coverUrl.url.takeIf { it.isNotBlank() } ?: old?.cover,
            old?.progress, System.currentTimeMillis(),
        )
        save(listOf(title) + _all.value.filterNot { it.id == id })

        val selected = PrefManager.getNullableCustomVal("Selected-$id", null, Selected::class.java)
            ?: Selected()
        selected.sourceIndex = index
        PrefManager.setCustomVal("Selected-$id", selected)

        MediaDetailsActivity.mediaSingleton = media(title).also { it.selected = selected }
        context.startActivity(Intent(context, MediaDetailsActivity::class.java))
    }

    /** Reopens a saved title with the search result stored when it was first opened. */
    fun open(context: Context, title: ExtensionTitle) {
        val sources = sources(title.anime)
        val index = sources.names.indexOf(title.source)
        if (index < 0) {
            snackString(context.getString(R.string.source_not_installed, title.source))
            return
        }
        val response = PrefManager.getNullableCustomVal(
            "${sources[index]?.saveName}_${title.id}", null, ShowResponse::class.java
        ) ?: ShowResponse(title.name, title.link, title.cover ?: "")
        open(context, title.anime, title.source, response)
    }

    /** An AniList entry whose title matches exactly, e.g. once a submission is approved. */
    suspend fun findOnAniList(t: ExtensionTitle): Media? {
        val results = Anilist.query.searchAniManga(
            type = if (t.anime) "ANIME" else "MANGA", search = t.name
        )?.results ?: return null
        val key = normalize(t.name)
        return results.firstOrNull { m ->
            listOfNotNull(m.name, m.nameRomaji, m.userPreferredName).any { normalize(it) == key }
        }
    }

    private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Moves [t] onto AniList entry [anilistId]: the same source keeps loading the same title,
     * local progress goes to AniList when it is ahead, and the local entry is dropped.
     */
    suspend fun link(t: ExtensionTitle, anilistId: Int): Media? {
        val sources = sources(t.anime)
        val index = sources.names.indexOf(t.source)
        val parser = sources.list.getOrNull(index)?.get?.value
        if (parser != null) {
            parser.loadSavedShowResponse(t.id)?.let {
                PrefManager.setCustomVal("${parser.saveName}_$anilistId", it)
            }
            val selected = PrefManager.getNullableCustomVal("Selected-$anilistId", null, Selected::class.java)
                ?: Selected()
            selected.sourceIndex = index
            PrefManager.setCustomVal("Selected-$anilistId", selected)
        }
        val media = Anilist.query.getMedia(anilistId) ?: return null
        val progress = t.progress
        if (progress != null && Anilist.userid != null && progress > (media.userProgress ?: -1)) {
            val status = if (media.userStatus == "REPEATING") "REPEATING" else "CURRENT"
            Anilist.mutation.editList(anilistId, progress, status = status)
            media.userProgress = progress
            media.userStatus = status
        }
        remove(t.id)
        return media
    }

    /**
     * For home's Continue Watching / Reading: titles with progress, or opened in the player or
     * reader ([opened] is the ids those record), since progress only saves on finishing one.
     */
    fun continuing(anime: Boolean, opened: Collection<Int>) =
        _all.value.filter { it.anime == anime && (it.progress != null || it.id in opened) }

    fun media(t: ExtensionTitle) = Media(
        id = t.id,
        name = t.name,
        nameRomaji = t.name,
        userPreferredName = t.name,
        isAdult = false,
        cover = t.cover,
        userProgress = t.progress,
        userStatus = t.progress?.let { "CURRENT" },
        anime = if (t.anime) Anime() else null,
        manga = if (t.anime) null else Manga(),
        format = if (t.anime) null else "MANGA",
        userUpdatedAt = t.lastOpened,
    )
}
