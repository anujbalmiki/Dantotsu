package ani.dantotsu.media.extension

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
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
        binding.extensionSearchResults.layoutManager = GridLayoutManager(
            this, clamp(resources.displayMetrics.widthPixels / 124f.px, 1, 6)
        )
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
        showLibrary()
    }

    override fun onResume() {
        super.onResume()
        if (binding.extensionSearchText.text.isNullOrBlank()) showLibrary()
    }

    private fun loadSources() {
        val names = ExtensionTitles.sourceNames(anime)
        binding.extensionSearchSource.setAdapter(ArrayAdapter(this, R.layout.item_dropdown, names))
        source = names.firstOrNull()
        binding.extensionSearchSource.setText(source ?: "", false)
        if (source == null) snackString(getString(R.string.no_extension_sources))
    }

    private fun showLibrary() {
        searchJob?.cancel()
        binding.extensionSearchProgress.visibility = View.GONE
        val titles = ExtensionTitles.all.value.filter { it.anime == anime }.sortedByDescending { it.lastOpened }
        binding.extensionSearchLabel.visibility = if (titles.isEmpty()) View.GONE else View.VISIBLE
        showEmpty(if (titles.isEmpty()) getString(R.string.no_extension_titles) else null)
        binding.extensionSearchResults.adapter = Adapter(
            titles.map { Item(it.name, it.cover) },
            onClick = { ExtensionTitles.open(this, titles[it]) },
            onLongClick = {
                ExtensionTitles.remove(titles[it].id)
                snackString(getString(R.string.removed_from_list, titles[it].name))
                showLibrary()
            },
        )
    }

    private fun search() {
        val query = binding.extensionSearchText.text?.toString()?.trim().orEmpty()
        val source = source ?: return
        if (query.isEmpty()) return showLibrary()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.extensionSearchText.windowToken, 0)
        binding.extensionSearchText.clearFocus()
        binding.extensionSearchLabel.visibility = View.GONE
        showEmpty(null)
        binding.extensionSearchResults.adapter = null
        binding.extensionSearchProgress.visibility = View.VISIBLE
        val type = anime
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                runCatching {
                    val sources = ExtensionTitles.sources(type)
                    sources[sources.names.indexOf(source)]!!.search(query)
                }.onFailure { Logger.log(it) }
            }
            binding.extensionSearchProgress.visibility = View.GONE
            val list = results.getOrNull().orEmpty()
            results.exceptionOrNull()?.let { snackString("$source: ${it.message}") }
            showEmpty(if (list.isEmpty()) getString(R.string.no_results_in, source) else null)
            binding.extensionSearchResults.adapter = Adapter(
                list.map { Item(it.name, it.coverUrl.url) },
                onClick = { ExtensionTitles.open(this@ExtensionSearchActivity, type, source, list[it]) },
            )
        }
    }

    private fun showEmpty(text: String?) {
        binding.extensionSearchEmpty.text = text
        binding.extensionSearchEmpty.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private data class Item(val name: String, val cover: String?)

    private class Adapter(
        val items: List<Item>,
        val onClick: (Int) -> Unit,
        val onLongClick: ((Int) -> Unit)? = null,
    ) : RecyclerView.Adapter<Adapter.Holder>() {
        class Holder(val binding: ItemCharacterBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemCharacterBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = items[position]
            holder.binding.itemCompactImage.loadImage(item.cover, 200)
            holder.binding.itemCompactTitle.text = item.name
            holder.binding.itemCompactTitle.isSelected = true
            holder.binding.root.setOnClickListener { onClick(holder.bindingAdapterPosition) }
            holder.binding.root.setOnLongClickListener {
                onLongClick?.invoke(holder.bindingAdapterPosition)
                onLongClick != null
            }
        }
    }

    companion object {
        const val EXTRA_TYPE = "type"
        const val EXTRA_QUERY = "query"
    }
}
