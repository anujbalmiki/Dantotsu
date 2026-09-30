package ani.dantotsu.media.extension

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.math.MathUtils.clamp
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.databinding.ActivityExtensionSearchBinding
import ani.dantotsu.databinding.ItemCharacterBinding
import ani.dantotsu.initActivity
import ani.dantotsu.loadImage
import ani.dantotsu.navBarHeight
import ani.dantotsu.parsers.ShowResponse
import ani.dantotsu.px
import ani.dantotsu.snackString
import ani.dantotsu.themes.ThemeManager
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/** Searches one extension directly, for titles AniList does not have. */
class ExtensionSearchActivity : AppCompatActivity() {
    private lateinit var binding: ActivityExtensionSearchBinding
    private var anime = true
    private var source: String? = null
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initActivity(this)
        ThemeManager(this).applyTheme()
        binding = ActivityExtensionSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.extensionSearchRoot.setPadding(0, binding.extensionSearchRoot.paddingTop, 0, navBarHeight)
        binding.extensionSearchTitle.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        anime = intent.getStringExtra(EXTRA_TYPE) != "MANGA"
        val spans = clamp(resources.displayMetrics.widthPixels / 124f.px, 1, 6)
        binding.extensionSearchResults.layoutManager = GridLayoutManager(this, spans).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) =
                    if ((binding.extensionSearchResults.adapter as? Adapter)?.isHeader(position) == true) spans else 1
            }
        }
        binding.extensionSearchType.check(if (anime) R.id.extensionSearchAnime else R.id.extensionSearchManga)
        binding.extensionSearchType.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            anime = id == R.id.extensionSearchAnime
            loadSources()
            showLibrary()
        }
        binding.extensionSearchSource.setOnItemClickListener { parent, _, position, _ ->
            source = parent.getItemAtPosition(position) as String
            if (!binding.extensionSearchText.text.isNullOrBlank()) search()
        }
        binding.extensionSearchText.setText(intent.getStringExtra(EXTRA_QUERY))
        binding.extensionSearchText.setOnEditorActionListener { _, actionId, _ ->
            (actionId == EditorInfo.IME_ACTION_SEARCH).also { if (it) search() }
        }
        binding.extensionSearchBar.setEndIconOnClickListener { search() }

        loadSources()
        if (binding.extensionSearchText.text.isNullOrBlank()) showLibrary() else search()
    }

    override fun onRestart() {
        super.onRestart()
        if (binding.extensionSearchText.text.isNullOrBlank()) showLibrary()
    }

    private fun loadSources() {
        val names = listOf(getString(R.string.all_extensions)) + ExtensionTitles.sourceNames(anime)
        binding.extensionSearchSource.setAdapter(ArrayAdapter(this, R.layout.item_dropdown, names))
        source = names.first()
        binding.extensionSearchSource.setText(source, false)
        if (names.size == 1) snackString(getString(R.string.no_extension_sources))
    }

    private fun showLibrary() {
        searchJob?.cancel()
        binding.extensionSearchProgress.visibility = View.GONE
        val titles = ExtensionTitles.all.value.filter { it.anime == anime }.sortedByDescending { it.lastOpened }
        binding.extensionSearchLabel.setText(R.string.opened_from_extensions)
        binding.extensionSearchLabel.visibility = if (titles.isEmpty()) View.GONE else View.VISIBLE
        showEmpty(if (titles.isEmpty()) getString(R.string.no_extension_titles) else null)
        binding.extensionSearchResults.adapter = Adapter(titles.map { t ->
            Item(t.name, t.cover, action = { ExtensionTitles.open(this, t) }, longAction = {
                ExtensionTitles.remove(t.id)
                snackString(getString(R.string.removed_from_list, t.name))
                showLibrary()
            })
        }.toMutableList())
    }

    /** Searches the chosen extension, or all of them at once, one section per extension. */
    private fun search() {
        val query = binding.extensionSearchText.text?.toString()?.trim().orEmpty()
        val chosen = source ?: return
        if (query.isEmpty()) return showLibrary()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.extensionSearchText.windowToken, 0)
        binding.extensionSearchText.clearFocus()
        showEmpty(null)
        binding.extensionSearchProgress.visibility = View.VISIBLE
        val type = anime
        val names = if (chosen == getString(R.string.all_extensions)) ExtensionTitles.sourceNames(type)
        else listOf(chosen)
        val adapter = Adapter(mutableListOf())
        binding.extensionSearchResults.adapter = adapter
        var pending = names.size
        fun progressText() {
            binding.extensionSearchLabel.visibility = if (pending > 0) View.VISIBLE else View.GONE
            binding.extensionSearchLabel.text =
                getString(R.string.searching_extensions, names.size - pending, names.size)
        }
        progressText()
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            val gate = Semaphore(SEARCH_PARALLELISM)
            val sources = ExtensionTitles.sources(type)
            names.forEach { name ->
                launch {
                    val results = gate.withPermit {
                        withContext(Dispatchers.IO) {
                            withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                                runCatching { sources[sources.names.indexOf(name)]!!.search(query) }
                                    .onFailure { Logger.log("Extension search in $name failed: $it") }
                                    .getOrNull()
                            }
                        }
                    }.orEmpty()
                    if (results.isNotEmpty()) {
                        binding.extensionSearchProgress.visibility = View.GONE
                        adapter.add(listOf(Item(name, null, header = true)) + results.map { r ->
                            Item(r.name, r.coverUrl.url, action = {
                                ExtensionTitles.open(this@ExtensionSearchActivity, type, name, r)
                            })
                        })
                    }
                    pending--
                    progressText()
                    if (pending == 0) {
                        binding.extensionSearchProgress.visibility = View.GONE
                        if (adapter.itemCount == 0) showEmpty(getString(R.string.no_results_in, chosen))
                    }
                }
            }
        }
    }

    private fun showEmpty(text: String?) {
        binding.extensionSearchEmpty.text = text
        binding.extensionSearchEmpty.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private class Item(
        val name: String,
        val cover: String?,
        val header: Boolean = false,
        val action: (() -> Unit)? = null,
        val longAction: (() -> Unit)? = null,
    )

    private class Adapter(val items: MutableList<Item>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        class Holder(val binding: ItemCharacterBinding) : RecyclerView.ViewHolder(binding.root)
        class Header(val text: TextView) : RecyclerView.ViewHolder(text)

        fun add(section: List<Item>) {
            val start = items.size
            items += section
            notifyItemRangeInserted(start, section.size)
        }

        fun isHeader(position: Int) = items.getOrNull(position)?.header == true

        override fun getItemViewType(position: Int) = if (isHeader(position)) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == 1) Header(TextView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setPadding(8.px, 16.px, 8.px, 4.px)
                typeface = ResourcesCompat.getFont(context, R.font.poppins_bold)
                textSize = 16f
            })
            else Holder(ItemCharacterBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            if (holder is Header) {
                holder.text.text = item.name
                return
            }
            val b = (holder as Holder).binding
            b.itemCompactImage.loadImage(item.cover, 200)
            b.itemCompactTitle.text = item.name
            b.itemCompactTitle.isSelected = true
            b.root.setOnClickListener { item.action?.invoke() }
            b.root.setOnLongClickListener {
                item.longAction?.invoke()
                item.longAction != null
            }
        }
    }

    companion object {
        const val EXTRA_TYPE = "type"
        const val EXTRA_QUERY = "query"
        private const val SEARCH_PARALLELISM = 6
        private const val SEARCH_TIMEOUT_MS = 25_000L
    }
}
