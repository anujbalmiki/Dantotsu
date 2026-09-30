package ani.dantotsu.media.extension

import android.content.Intent
import android.view.View
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ani.dantotsu.R
import ani.dantotsu.media.LocalMappingSearchDialog
import ani.dantotsu.media.MediaDetailsActivity
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Serializable

/** The "Not on AniList" button of a title opened from an extension: submit it, or link it. */
fun AppCompatActivity.setupExtensionTitle(mediaId: Int, button: View) {
    val t = ExtensionTitles.get(mediaId) ?: return
    button.setOnClickListener { v ->
        PopupMenu(this, v).apply {
            menu.add(R.string.submit_to_anilist).setOnMenuItemClickListener { submitToAniList(t); true }
            menu.add(R.string.link_to_anilist).setOnMenuItemClickListener {
                LocalMappingSearchDialog.newInstance(t.name, t.anime) { linkToAniList(t, it) }
                    .apply { saveMapping = false }
                    .show(supportFragmentManager, "linkToAniList")
                true
            }
            show()
        }
    }
}

/** Offers to link [mediaId] once AniList has an entry with the same title. */
fun AppCompatActivity.checkExtensionTitleOnAniList(mediaId: Int) {
    val t = ExtensionTitles.get(mediaId) ?: return
    lifecycleScope.launch {
        val match = withContext(Dispatchers.IO) {
            runCatching { ExtensionTitles.findOnAniList(t) }.onFailure { Logger.log(it) }.getOrNull()
        } ?: return@launch
        Snackbar.make(
            findViewById(android.R.id.content),
            getString(R.string.found_on_anilist, match.userPreferredName),
            10_000,
        ).setAction(R.string.link) { linkToAniList(t, match.id) }.show()
    }
}

private fun AppCompatActivity.submitToAniList(t: ExtensionTitle) {
    val sources = ExtensionTitles.sources(t.anime)
    val host = sources.list.getOrNull(sources.names.indexOf(t.source))?.get?.value?.hostUrl
    val url = if (t.link.startsWith("http") || host == null) t.link
    else host.trimEnd('/') + "/" + t.link.trimStart('/')
    startActivity(
        Intent(this, AniListSubmitActivity::class.java)
            .putExtra(AniListSubmitActivity.EXTRA_ANIME, t.anime)
            .putExtra(AniListSubmitActivity.EXTRA_NAME, t.name)
            .putExtra(AniListSubmitActivity.EXTRA_COVER, t.cover)
            .putExtra(AniListSubmitActivity.EXTRA_SOURCE_URL, url)
    )
}

private fun AppCompatActivity.linkToAniList(t: ExtensionTitle, anilistId: Int) {
    lifecycleScope.launch {
        val media = withContext(Dispatchers.IO) {
            runCatching { ExtensionTitles.link(t, anilistId) }.onFailure { Logger.log(it) }.getOrNull()
        }
        if (media == null) {
            snackString(getString(R.string.link_failed))
            return@launch
        }
        snackString(getString(R.string.linked_to_anilist))
        startActivity(
            Intent(this@linkToAniList, MediaDetailsActivity::class.java)
                .putExtra("media", media as Serializable)
        )
        finish()
    }
}
