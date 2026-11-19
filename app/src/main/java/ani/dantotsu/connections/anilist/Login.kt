package ani.dantotsu.connections.anilist

import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import ani.dantotsu.logError
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.startMainActivity
import ani.dantotsu.themes.ThemeManager

class Login : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()
        val data: Uri? = intent?.data
        val token = try {
            Regex("""(?<=access_token=).+(?=&token_type)""").find(data.toString())?.value
        } catch (e: Exception) {
            logError(e)
            null
        }
        if (token == null) {
            startMainActivity(this)
            return
        }
        fun save() {
            Anilist.token = token
            PrefManager.setVal(PrefName.AnilistToken, token)
            startMainActivity(this)
        }
        // AniList doesn't echo `state`, so confirm before an external link replaces a live login
        if (Anilist.token.isNullOrEmpty()) {
            save()
        } else {
            AlertDialog.Builder(this)
                .setTitle("Replace AniList login?")
                .setMessage("A link is trying to sign you in to a different AniList account. Only continue if you started this login.")
                .setCancelable(false)
                .setPositiveButton("Replace") { _, _ -> save() }
                .setNegativeButton("Cancel") { _, _ -> startMainActivity(this) }
                .show()
        }
    }
}
