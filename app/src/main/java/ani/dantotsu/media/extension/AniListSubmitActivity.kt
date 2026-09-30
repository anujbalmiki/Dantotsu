package ani.dantotsu.media.extension

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import ani.dantotsu.R
import ani.dantotsu.copyToClipboard
import ani.dantotsu.databinding.ActivityAnilistSubmitBinding
import ani.dantotsu.initActivity
import ani.dantotsu.navBarHeight
import ani.dantotsu.snackString
import ani.dantotsu.themes.ThemeManager
import com.google.android.material.chip.Chip

/**
 * AniList's own new-entry form in a WebView. AniList has no API for submissions, so the user
 * logs in to the site here once and submits by hand; the details we know are one tap to copy.
 */
class AniListSubmitActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAnilistSubmitBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initActivity(this)
        ThemeManager(this).applyTheme()
        binding = ActivityAnilistSubmitBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.submitRoot.setPadding(0, binding.submitRoot.paddingTop, 0, navBarHeight)

        val anime = intent.getBooleanExtra(EXTRA_ANIME, false)
        val formUrl = "https://anilist.co/edit/${if (anime) "anime" else "manga"}/new"

        val web = binding.submitWebView
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.submitProgress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.submitProgress.visibility = View.GONE
            }
        }
        web.loadUrl(formUrl)

        binding.submitTitle.setOnClickListener { finish() }
        binding.submitLogin.setOnClickListener { web.loadUrl("https://anilist.co/login") }
        binding.submitForm.setOnClickListener { web.loadUrl(formUrl) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        listOf(
            getString(R.string.field_title) to intent.getStringExtra(EXTRA_NAME),
            getString(R.string.field_cover) to intent.getStringExtra(EXTRA_COVER),
            getString(R.string.field_source) to intent.getStringExtra(EXTRA_SOURCE_URL),
        ).forEach { (label, value) ->
            if (value.isNullOrBlank()) return@forEach
            binding.submitFields.addView(Chip(this).apply {
                text = label
                setOnClickListener {
                    copyToClipboard(value, false)
                    snackString(getString(R.string.copied_field, label))
                }
            })
        }
    }

    override fun onDestroy() {
        binding.submitWebView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ANIME = "anime"
        const val EXTRA_NAME = "name"
        const val EXTRA_COVER = "cover"
        const val EXTRA_SOURCE_URL = "sourceUrl"
    }
}
