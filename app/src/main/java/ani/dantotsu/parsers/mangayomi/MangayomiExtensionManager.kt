package ani.dantotsu.parsers.mangayomi

import android.app.Application
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/** One entry of a Mangayomi repo index (anime_index.json / index.json). */
@Serializable
data class MangayomiSource(
    val name: String,
    val id: Long,
    val baseUrl: String = "",
    val lang: String = "",
    val iconUrl: String = "",
    val typeSource: String = "",
    val isNsfw: Boolean = false,
    val hasCloudflare: Boolean = false,
    val sourceCodeUrl: String = "",
    val apiUrl: String = "",
    val version: String = "",
    val itemType: Int = 0,
    val isManga: Boolean? = null,
    val isFullData: Boolean = false,
    val appMinVerReq: String = "",
    val dateFormat: String = "",
    val dateFormatLocale: String = "",
    val additionalParams: String = "",
    val sourceCodeLanguage: Int = 0,
    val notes: String = "",
    /** The repo index this came from; not part of Mangayomi's format. */
    val repo: String = "",
) {
    val pkgName get() = "$PKG_PREFIX$id"

    companion object {
        const val PKG_PREFIX = "mangayomi-"
    }
}

/**
 * Installs Mangayomi JavaScript anime extensions from the anime repos the user added. Only
 * JavaScript anime sources are offered: Dart sources need Mangayomi's Dart interpreter.
 */
object MangayomiExtensions {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val context get() = Injekt.get<Application>()
    private val dir get() = File(context.filesDir, "mangayomi").also { it.mkdirs() }
    private val installedFile get() = File(dir, "installed.json")

    private val _installed = MutableStateFlow(loadInstalled())
    val installed = _installed.asStateFlow()

    private val _available = MutableStateFlow(emptyList<MangayomiSource>())
    val available = _available.asStateFlow()

    private fun loadInstalled(): List<MangayomiSource> = runCatching {
        json.decodeFromString<List<MangayomiSource>>(installedFile.readText())
    }.getOrDefault(emptyList())

    private fun saveInstalled(list: List<MangayomiSource>) {
        installedFile.writeText(json.encodeToString(list))
        _installed.value = list
    }

    fun code(id: Long): String = File(dir, "$id.js").readText()

    fun find(pkgName: String) = installed.value.find { it.pkgName == pkgName }
        ?: available.value.find { it.pkgName == pkgName }

    fun hasUpdate(source: MangayomiSource): Boolean {
        val remote = available.value.find { it.id == source.id } ?: return false
        return compareVersions(remote.version, source.version) > 0
    }

    /** Reads every repo; repos that are not Mangayomi indexes are skipped. */
    suspend fun refresh(repos: Collection<String>) = withContext(Dispatchers.IO) {
        val client = Injekt.get<NetworkHelper>().client
        _available.value = repos.flatMap { repo ->
            runCatching {
                val body = client.newCall(Request.Builder().url(repo).build()).execute()
                    .use { if (it.isSuccessful) it.body.string() else "" }
                val entries = Json.parseToJsonElement(body) as? JsonArray ?: return@runCatching emptyList()
                entries.filter { "sourceCodeUrl" in it.jsonObject }
                    .map { json.decodeFromJsonElement(MangayomiSource.serializer(), it).copy(repo = repo) }
                    .filter { it.sourceCodeLanguage == 1 && (it.itemType == 1 || it.isManga == false) }
            }.getOrElse {
                Logger.log("Mangayomi: failed to read repo $repo: ${it.message}")
                emptyList()
            }
        }.distinctBy { it.id }
    }

    suspend fun install(source: MangayomiSource): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val code = Injekt.get<NetworkHelper>().client
                .newCall(Request.Builder().url(source.sourceCodeUrl).header("Cache-Control", "no-cache").build())
                .execute().use { res ->
                    check(res.isSuccessful) { "HTTP ${res.code}" }
                    res.body.string()
                }
            File(dir, "${source.id}.js").writeText(code)
            saveInstalled(installed.value.filterNot { it.id == source.id } + source)
            true
        }.getOrElse {
            Logger.log("Mangayomi: install of ${source.name} failed: ${it.message}")
            false
        }
    }

    fun uninstall(id: Long) {
        File(dir, "$id.js").delete()
        saveInstalled(installed.value.filterNot { it.id == id })
    }

    private fun compareVersions(a: String, b: String): Int {
        val x = a.split(".").map { it.toIntOrNull() ?: 0 }
        val y = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}
