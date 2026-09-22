package ani.dantotsu.media.manga

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.NumberPicker
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.coroutineScope
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.R
import ani.dantotsu.connections.updateProgress
import ani.dantotsu.currContext
import ani.dantotsu.databinding.ItemChapterListBinding
import ani.dantotsu.databinding.ItemEpisodeCompactBinding
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaNameAdapter
import ani.dantotsu.setAnimation
import ani.dantotsu.util.SizeFormatter
import ani.dantotsu.util.customAlertDialog
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MangaChapterAdapter(
    private var type: Int,
    private val media: Media,
    private val fragment: MangaReadFragment,
    var arr: ArrayList<MangaChapter> = arrayListOf(),
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            1 -> ChapterCompactViewHolder(
                ItemEpisodeCompactBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            0 -> ChapterListViewHolder(
                ItemChapterListBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false
                )
            )

            else -> throw IllegalArgumentException()
        }
    }

    override fun getItemViewType(position: Int): Int {
        return type
    }

    override fun getItemCount(): Int = arr.size

    inner class ChapterCompactViewHolder(val binding: ItemEpisodeCompactBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            itemView.setOnClickListener {
                if (0 <= bindingAdapterPosition && bindingAdapterPosition < arr.size) {
                    if (selectionMode) toggleSelection(bindingAdapterPosition)
                    else fragment.onMangaChapterClick(arr[bindingAdapterPosition])
                }
            }
            itemView.setOnLongClickListener {
                if (0 <= bindingAdapterPosition && bindingAdapterPosition < arr.size) {
                    fragment.startChapterSelection(arr[bindingAdapterPosition])
                    true
                } else false
            }
        }
    }

    private val activeDownloads = mutableSetOf<String>()
    private val downloadedChapters = mutableSetOf<String>()

    // ---- bulk selection ----------------------------------------------------------------
    // Keyed by uniqueNumber() so a selection survives the list being re-sorted or re-filtered.
    private val selectedKeys = linkedSetOf<String>()
    var selectionMode = false
        private set

    /** Fired whenever the ticked count changes, so the contextual toolbar can retitle itself. */
    var onSelectionChanged: (() -> Unit)? = null

    val selectedCount: Int get() = selectedKeys.size

    fun selectedChapters(): List<MangaChapter> = arr.filter { it.uniqueNumber() in selectedKeys }

    fun beginSelection(chapter: MangaChapter? = null) {
        selectionMode = true
        chapter?.let { selectedKeys.add(it.uniqueNumber()) }
        notifyItemRangeChanged(0, arr.size)
        onSelectionChanged?.invoke()
    }

    fun endSelection() {
        if (!selectionMode && selectedKeys.isEmpty()) return
        selectionMode = false
        selectedKeys.clear()
        notifyItemRangeChanged(0, arr.size)
    }

    fun toggleSelection(position: Int) {
        val chapter = arr.getOrNull(position) ?: return
        val key = chapter.uniqueNumber()
        if (!selectedKeys.remove(key)) selectedKeys.add(key)
        notifyItemChanged(position)
        onSelectionChanged?.invoke()
    }

    fun selectAll() {
        selectedKeys.clear()
        arr.forEach { selectedKeys.add(it.uniqueNumber()) }
        notifyItemRangeChanged(0, arr.size)
        onSelectionChanged?.invoke()
    }

    /** Ticks every chapter whose parsed number falls inside [from]..[to]. */
    fun selectNumberRange(from: Float, to: Float): Int {
        var ticked = 0
        arr.forEach { chapter ->
            val number = chapterNumberOf(chapter) ?: return@forEach
            if (number in from..to && selectedKeys.add(chapter.uniqueNumber())) ticked++
        }
        notifyItemRangeChanged(0, arr.size)
        onSelectionChanged?.invoke()
        return ticked
    }

    fun selectDownloaded(): Int {
        var ticked = 0
        arr.forEach { chapter ->
            if (isDownloaded(chapter) && selectedKeys.add(chapter.uniqueNumber())) ticked++
        }
        notifyItemRangeChanged(0, arr.size)
        onSelectionChanged?.invoke()
        return ticked
    }

    /** Lowest and highest parsed chapter numbers, for seeding the range dialog. */
    fun numberBounds(): Pair<Float, Float>? {
        val numbers = arr.mapNotNull { chapterNumberOf(it) }
        if (numbers.isEmpty()) return null
        return numbers.min() to numbers.max()
    }

    private fun chapterNumberOf(chapter: MangaChapter): Float? =
        MediaNameAdapter.findChapterNumber(chapter.number) ?: chapter.number.trim().toFloatOrNull()

    private fun isSelected(chapter: MangaChapter) = chapter.uniqueNumber() in selectedKeys

    /**
     * Tinted foreground rather than a background or a checkbox: the item layouts are CardViews
     * with their own backgrounds, and a foreground overlay shows through regardless of which
     * layout variant is bound.
     */
    private fun paintSelection(itemView: View, selected: Boolean) {
        itemView.foreground = if (selected) {
            ColorDrawable(
                ColorUtils.setAlphaComponent(
                    MaterialColors.getColor(
                        itemView, androidx.appcompat.R.attr.colorPrimary, Color.CYAN
                    ),
                    80
                )
            )
        } else null
    }

    fun isDownloading(chapterNumber: String): Boolean = activeDownloads.contains(chapterNumber)
    fun isDownloaded(chapterNumber: String): Boolean = downloadedChapters.contains(chapterNumber)

    fun isDownloaded(chapter: MangaChapter): Boolean {
        val unique = chapter.uniqueNumber()
        val num = chapter.number
        val title = chapter.title
        val titleUnique = title?.let { "$it-${chapter.scanlator ?: "Unknown"}" }
        return downloadedChapters.contains(unique) ||
                downloadedChapters.contains(num) ||
                (title != null && downloadedChapters.contains(title)) ||
                (titleUnique != null && downloadedChapters.contains(titleUnique))
    }

    fun startDownload(chapterNumber: String) {
        activeDownloads.add(chapterNumber)
        val position = arr.indexOfFirst { it.uniqueNumber() == chapterNumber || it.number == chapterNumber }
        if (position != -1) {
            val chapter = arr[position]
            activeDownloads.add(chapter.uniqueNumber())
            activeDownloads.add(chapter.number)
            notifyItemChanged(position)
        }
    }

    fun stopDownload(chapterNumber: String) {
        activeDownloads.remove(chapterNumber)
        downloadedChapters.add(chapterNumber)
        val position = arr.indexOfFirst { it.uniqueNumber() == chapterNumber || it.number == chapterNumber }
        if (position != -1) {
            val chapter = arr[position]
            activeDownloads.remove(chapter.uniqueNumber())
            activeDownloads.remove(chapter.number)
            downloadedChapters.add(chapter.uniqueNumber())
            downloadedChapters.add(chapter.number)
            chapter.progress = "Downloaded"
            notifyItemChanged(position)
        }
    }

    fun deleteDownload(chapterNumber: MangaChapter) {
        downloadedChapters.remove(chapterNumber.uniqueNumber())
        downloadedChapters.remove(chapterNumber.number)
        activeDownloads.remove(chapterNumber.uniqueNumber())
        activeDownloads.remove(chapterNumber.number)
        chapterNumber.title?.let { downloadedChapters.remove(it) }
        val position = arr.indexOfFirst { it.uniqueNumber() == chapterNumber.uniqueNumber() }
        if (position != -1) {
            arr[position].progress = ""
            notifyItemChanged(position)
        }
    }

    fun purgeDownload(chapterNumber: String) {
        activeDownloads.remove(chapterNumber)
        downloadedChapters.remove(chapterNumber)
        val position = arr.indexOfFirst { it.uniqueNumber() == chapterNumber || it.number == chapterNumber }
        if (position != -1) {
            val chapter = arr[position]
            activeDownloads.remove(chapter.uniqueNumber())
            activeDownloads.remove(chapter.number)
            downloadedChapters.remove(chapter.uniqueNumber())
            downloadedChapters.remove(chapter.number)
            chapter.progress = ""
            notifyItemChanged(position)
        }
    }

    fun updateDownloadProgress(chapterNumber: String, progress: Int) {
        updateDownloadProgress(chapterNumber, progress, -1L, -1L)
    }

    fun updateDownloadProgress(
        chapterNumber: String,
        progress: Int,
        downloadedBytes: Long,
        estimatedTotalBytes: Long
    ) {
        // Find the position of the chapter and notify only that item
        val position = arr.indexOfFirst { it.uniqueNumber() == chapterNumber }
        if (position != -1) {
            arr[position].progress = buildDownloadProgressText(
                progress,
                downloadedBytes,
                estimatedTotalBytes
            )

            notifyItemChanged(position)
        }
    }

    private fun buildDownloadProgressText(
        progress: Int,
        downloadedBytes: Long,
        estimatedTotalBytes: Long
    ): String {
        val hasDownloaded = downloadedBytes > 0L
        val hasEstimatedTotal = estimatedTotalBytes > 0L
        return if (hasDownloaded && hasEstimatedTotal) {
            "Downloading: $progress% (${SizeFormatter.formatBytes(downloadedBytes)} / ${SizeFormatter.formatBytes(estimatedTotalBytes)} est.)"
        } else if (hasEstimatedTotal) {
            "Downloading: $progress% (~${SizeFormatter.formatBytes(estimatedTotalBytes)} est.)"
        } else {
            "Downloading: $progress%"
        }
    }

    fun downloadNChaptersFrom(position: Int, n: Int) {
        //download next n chapters
        if (position < 0 || position >= arr.size) return
        for (i in 0..<n) {
            if (position + i < arr.size) {
                val chapter = arr[position + i]
                val chapterNumber = chapter.uniqueNumber()
                if (activeDownloads.contains(chapterNumber)) {
                    //do nothing
                    continue
                } else if (isDownloaded(chapter)) {
                    //do nothing
                    continue
                } else {
                    fragment.onMangaChapterDownloadClick(chapter)
                }
            }
        }
    }

    fun deleteNChaptersFrom(position: Int, n: Int){
        //delete next n chapters
        if (position < 0 || position >= arr.size) return
        for (i in 0..<n) {
            if (position + i < arr.size) {
                val chapter = arr[position + i]
                val chapterNumber = chapter.uniqueNumber()
                if(activeDownloads.contains(chapterNumber) || activeDownloads.contains(chapter.number)){
                    fragment.onMangaChapterStopDownloadClick(chapter)
                }
                else if (isDownloaded(chapter)) {
                    fragment.onMangaChapterRemoveDownloadClick(chapter)
                }
            }
        }
    }

    inner class ChapterListViewHolder(val binding: ItemChapterListBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private val activeCoroutines = mutableSetOf<String>()
        fun bind(chapter: MangaChapter, progress: String?) {
            val chapterNumber = chapter.uniqueNumber()
            if (progress != null) {
                binding.itemChapterTitle.visibility = View.VISIBLE
                binding.itemChapterTitle.text = "$progress"
            } else {
                binding.itemChapterTitle.visibility = View.GONE
                binding.itemChapterTitle.text = ""
            }

            if (media.format == "LOCAL") {
                binding.itemDownload.visibility = View.GONE
                return
            }
            binding.itemDownload.visibility = View.VISIBLE

            if (activeDownloads.contains(chapterNumber) || activeDownloads.contains(chapter.number)) {
                // Show spinner
                binding.itemDownload.setImageResource(R.drawable.ic_sync)
                startOrContinueRotation(chapter) {
                    binding.itemDownload.rotation = 0f
                }
            } else if (isDownloaded(chapter)) {
                // Show delete icon
                binding.itemDownload.setImageResource(R.drawable.ic_round_delete_24)
                binding.itemDownload.rotation = 0f
            } else {
                // Show download icon
                binding.itemDownload.setImageResource(R.drawable.ic_download_24)
                binding.itemDownload.rotation = 0f
            }

        }

        private fun startOrContinueRotation(chapter: MangaChapter, resetRotation: () -> Unit) {
            val key = chapter.uniqueNumber()
            if (!isRotationCoroutineRunningFor(key)) {
                val scope = fragment.lifecycle.coroutineScope
                scope.launch {
                    activeCoroutines.add(key)
                    while (activeDownloads.contains(chapter.uniqueNumber()) || activeDownloads.contains(chapter.number)) {
                        binding.itemDownload.animate().rotationBy(360f).setDuration(1000)
                            .setInterpolator(
                                LinearInterpolator()
                            ).start()
                        delay(1000)
                    }
                    activeCoroutines.remove(key)
                    resetRotation()
                }
            }
        }

        private fun isRotationCoroutineRunningFor(chapterNumber: String): Boolean {
            return chapterNumber in activeCoroutines
        }

        init {
            itemView.setOnClickListener {
                if (0 <= bindingAdapterPosition && bindingAdapterPosition < arr.size) {
                    if (selectionMode) toggleSelection(bindingAdapterPosition)
                    else fragment.onMangaChapterClick(arr[bindingAdapterPosition])
                }
            }
            binding.itemDownload.setOnClickListener {
                if (selectionMode) {
                    if (0 <= bindingAdapterPosition && bindingAdapterPosition < arr.size)
                        toggleSelection(bindingAdapterPosition)
                    return@setOnClickListener
                }
                if (0 <= bindingAdapterPosition && bindingAdapterPosition < arr.size) {
                    val chapter = arr[bindingAdapterPosition]
                    val chapterNumber = chapter.uniqueNumber()
                    if (activeDownloads.contains(chapterNumber) || activeDownloads.contains(chapter.number)) {
                        fragment.onMangaChapterStopDownloadClick(chapter)
                        return@setOnClickListener
                    } else if (isDownloaded(chapter)) {
                        it.context.customAlertDialog().apply {
                            setTitle("Delete Chapter")
                            setMessage("Are you sure you want to delete ${chapter.number}?")
                            setPosButton(R.string.delete) {
                                fragment.onMangaChapterRemoveDownloadClick(chapter)
                            }
                            setNegButton(R.string.cancel)
                            show()
                        }
                        return@setOnClickListener
                    } else {
                        fragment.onMangaChapterDownloadClick(chapter)
                    }
                }
            }
            binding.itemDownload.setOnLongClickListener {
                if (0 <= bindingAdapterPosition && bindingAdapterPosition < arr.size) {
                    fragment.startChapterSelection(arr[bindingAdapterPosition])
                }
                true
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ChapterCompactViewHolder -> {
                val binding = holder.binding
                setAnimation(fragment.requireContext(), holder.binding.root)
                val ep = arr[position]
                paintSelection(holder.itemView, isSelected(ep))
                val parsedNumber = MediaNameAdapter.findChapterNumber(ep.number)?.toInt()
                binding.itemEpisodeNumber.text = parsedNumber?.toString() ?: ep.number
                if (media.userProgress != null) {
                    if ((MediaNameAdapter.findChapterNumber(ep.number)
                            ?: 9999f) <= media.userProgress!!.toFloat()
                    )
                        binding.itemEpisodeViewedCover.visibility = View.VISIBLE
                    else {
                        binding.itemEpisodeViewedCover.visibility = View.GONE
                        binding.itemEpisodeCont.setOnLongClickListener {
                            updateProgress(
                                media,
                                MediaNameAdapter.findChapterNumber(ep.number).toString()
                            )
                            true
                        }
                    }
                }
            }

            is ChapterListViewHolder -> {
                val binding = holder.binding
                val ep = arr[position]
                paintSelection(holder.itemView, isSelected(ep))
                holder.bind(ep, ep.progress)
                setAnimation(fragment.requireContext(), holder.binding.root)
                binding.itemChapterNumber.text = ep.number

                if (ep.date != null) {
                    binding.itemChapterDateLayout.visibility = View.VISIBLE
                    binding.itemChapterDate.text = formatDate(ep.date)
                }
                if (ep.scanlator != null) {
                    binding.itemChapterDateLayout.visibility = View.VISIBLE
                    binding.itemChapterScan.text = ep.scanlator.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(
                            Locale.ROOT
                        ) else it.toString()
                    }
                }
                if (formatDate(ep.date) == "" || ep.scanlator == null) {
                    binding.itemChapterDateDivider.visibility = View.GONE
                } else binding.itemChapterDateDivider.visibility = View.VISIBLE

                if (ep.progress.isNullOrEmpty()) {
                    binding.itemChapterTitle.visibility = View.GONE
                } else binding.itemChapterTitle.visibility = View.VISIBLE

                if (media.userProgress != null) {
                    if ((MediaNameAdapter.findChapterNumber(ep.number)
                            ?: 9999f) <= media.userProgress!!.toFloat()
                    ) {
                        binding.itemEpisodeViewedCover.visibility = View.VISIBLE
                        binding.itemEpisodeViewed.visibility = View.VISIBLE
                    } else {
                        binding.itemEpisodeViewedCover.visibility = View.GONE
                        binding.itemEpisodeViewed.visibility = View.GONE
                        binding.root.setOnLongClickListener {
                            updateProgress(
                                media,
                                MediaNameAdapter.findChapterNumber(ep.number).toString()
                            )
                            true
                        }
                    }
                } else {
                    binding.itemEpisodeViewedCover.visibility = View.GONE
                    binding.itemEpisodeViewed.visibility = View.GONE
                }
            }
        }
    }

    fun updateType(t: Int) {
        type = t
    }

    private fun formatDate(timestamp: Long?): String {
        timestamp ?: return "" // Return empty string if timestamp is null

        val targetDate = Date(timestamp)

        if (targetDate < Date(946684800000L)) { // January 1, 2000 (who want dates before that?)
            return ""
        }

        val currentDate = Date()
        val difference = currentDate.time - targetDate.time

        return when (val daysDifference = difference / (1000 * 60 * 60 * 24)) {
            0L -> {
                val hoursDifference = difference / (1000 * 60 * 60)
                val minutesDifference = (difference / (1000 * 60)) % 60

                when {
                    hoursDifference > 0 -> "$hoursDifference hour${if (hoursDifference > 1) "s" else ""} ago"
                    minutesDifference > 0 -> "$minutesDifference minute${if (minutesDifference > 1) "s" else ""} ago"
                    else -> "Just now"
                }
            }

            1L -> "1 day ago"
            in 2..6 -> "$daysDifference days ago"
            else -> SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(targetDate)
        }
    }

}
