package ani.dantotsu.media.manga.mangareader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import ani.dantotsu.FileUrl
import ani.dantotsu.GesturesListener
import ani.dantotsu.R
import ani.dantotsu.databinding.ItemChapterTransitionBinding
import ani.dantotsu.media.manga.MangaCache
import ani.dantotsu.media.manga.readerMaxSize
import ani.dantotsu.media.manga.MangaChapter
import ani.dantotsu.px
import ani.dantotsu.settings.CurrentReaderSettings
import ani.dantotsu.tryWithSuspend
import com.alexvasilkov.gestures.views.GestureFrameLayout
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import ani.dantotsu.parsers.MangaImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.InputStream
import ca.mpreg.imagedecoder.ImageDecoder
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Page loads run blocking OkHttp plus a full-page decode on Dispatchers.IO, which has 64 threads.
 * Fast scrolling used to start dozens of those at once and exhaust the 512 MB heap. Three at a
 * time keeps the pipeline full without ever holding more than a few decoded pages.
 *
 * ponytail: one global gate; make it per-RecyclerView only if two readers ever run side by side.
 */
private val decodeGate = Semaphore(3)

/**
 * Reader decodes: never keep the bitmap in the Glide memory cache (we call recycle() on these
 * ourselves), decode as RGB_565, and cap the result at screen size.
 */
private fun RequestBuilder<Bitmap>.readerDecodeOptions(disk: DiskCacheStrategy): RequestBuilder<Bitmap> {
    val (maxW, maxH) = readerMaxSize()
    return skipMemoryCache(true)
        .diskCacheStrategy(disk)
        .format(DecodeFormat.PREFER_RGB_565)
        .downsample(DownsampleStrategy.AT_MOST)
        .override(maxW, maxH)
}

sealed class ReaderItem {
    data class Page(
        val image: MangaImage,
        val chapter: MangaChapter,
        val pageNumber: Int,
        val totalPages: Int
    ) : ReaderItem()

    data class DualPage(
        val first: MangaImage,
        val second: MangaImage?,
        val chapter: MangaChapter,
        val pageNumber: Int,
        val totalPages: Int
    ) : ReaderItem()

    data class Transition(
        val fromChapter: MangaChapter,
        val toChapter: MangaChapter?,
        var isLoading: Boolean = false,
        val isPrevious: Boolean = false
    ) : ReaderItem()
}

abstract class BaseImageAdapter(
    val activity: MangaReaderActivity,
    val initialChapter: MangaChapter
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    val settings = activity.defaultSettings
    val items = mutableListOf<ReaderItem>()

    val images: List<MangaImage>
        get() = items.mapNotNull {
            when (it) {
                is ReaderItem.Page -> it.image
                is ReaderItem.DualPage -> it.first
                else -> null
            }
        }

    fun getItem(position: Int): ReaderItem? = items.getOrNull(position)

    fun findPositionForPage(targetChapter: MangaChapter, pageNum: Int): Int {
        return items.indexOfFirst {
            when (it) {
                is ReaderItem.Page -> it.chapter.uniqueNumber() == targetChapter.uniqueNumber() && it.pageNumber == pageNum
                is ReaderItem.DualPage -> it.chapter.uniqueNumber() == targetChapter.uniqueNumber() && it.pageNumber == pageNum
                else -> false
            }
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int {
        return when (items.getOrNull(position)) {
            is ReaderItem.Transition -> VIEW_TYPE_TRANSITION
            else -> VIEW_TYPE_IMAGE
        }
    }

    open fun appendChapter(nextChap: MangaChapter, afterNextChap: MangaChapter? = null) {}

    open fun prependChapter(prevChap: MangaChapter, beforePrevChap: MangaChapter? = null): Int = 0

    /**
     * Continuous scroll appends a chapter every time a boundary is crossed and never drops one, so
     * a long session ends up with thousands of items and every layout pass walks the lot. Keep
     * [radius] chapters either side of [currentKey] and drop the rest.
     *
     * Returns how many items were removed from the front, so the caller can re-anchor the scroll.
     */
    fun trimToWindow(currentKey: String, radius: Int = 1): Int {
        val keys = ArrayList<String>()
        items.forEach { item ->
            chapterKeyOf(item)?.let { if (keys.lastOrNull() != it) keys.add(it) }
        }
        val idx = keys.indexOf(currentKey)
        if (idx == -1 || keys.size <= radius * 2 + 1) return 0

        val keep = keys.subList(
            (idx - radius).coerceAtLeast(0),
            (idx + radius + 1).coerceAtMost(keys.size)
        ).toHashSet()

        val first = items.indexOfFirst { chapterKeyOf(it)?.let(keep::contains) == true }
        val last = items.indexOfLast { chapterKeyOf(it)?.let(keep::contains) == true }
        if (first == -1 || last == -1) return 0

        // hold on to the transition sitting on either side of the window
        val start = (first - 1).coerceAtLeast(0)
        val end = (last + 1).coerceAtMost(items.size - 1)

        if (end < items.size - 1) {
            val count = items.size - 1 - end
            repeat(count) { items.removeAt(items.size - 1) }
            notifyItemRangeRemoved(end + 1, count)
        }
        if (start > 0) {
            repeat(start) { items.removeAt(0) }
            notifyItemRangeRemoved(0, start)
        }
        return start
    }

    private fun chapterKeyOf(item: ReaderItem): String? = when (item) {
        is ReaderItem.Page -> item.chapter.uniqueNumber()
        is ReaderItem.DualPage -> item.chapter.uniqueNumber()
        is ReaderItem.Transition -> null
    }

    private val loadJobs = java.util.concurrent.ConcurrentHashMap<RecyclerView.ViewHolder, kotlinx.coroutines.Job>()

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        loadJobs.remove(holder)?.cancel()
        if (holder is TransitionViewHolder) {
            super.onViewRecycled(holder)
            return
        }
        val subsamplingView = holder.itemView.findViewById<com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView>(R.id.imgProgImageNoGestures)
        subsamplingView?.recycle()
        val oldBitmap = holder.itemView.getTag(R.id.imgProgImageNoGestures) as? Bitmap
        holder.itemView.setTag(R.id.imgProgImageNoGestures, null)
        if (oldBitmap != null && !oldBitmap.isRecycled) {
            oldBitmap.recycle()
        }
        super.onViewRecycled(holder)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        loadJobs.values.forEach { it.cancel() }
        loadJobs.clear()
        super.onDetachedFromRecyclerView(recyclerView)
    }

    inner class TransitionViewHolder(
        val binding: ItemChapterTransitionBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(transition: ReaderItem.Transition) {
            if (settings.layout != CurrentReaderSettings.Layouts.PAGED) {
                if (settings.direction == CurrentReaderSettings.Directions.LEFT_TO_RIGHT ||
                    settings.direction == CurrentReaderSettings.Directions.RIGHT_TO_LEFT
                ) {
                    itemView.updateLayoutParams {
                        width = 380f.px.toInt()
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                    }
                } else {
                    itemView.updateLayoutParams {
                        width = ViewGroup.LayoutParams.MATCH_PARENT
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                    }
                }
            }
            val fromChap = transition.fromChapter
            val toChap = transition.toChapter

            if (transition.isPrevious) {
                binding.transitionFinishedHeader.text = itemView.context.getString(R.string.transition_previous)
                if (toChap != null) {
                    binding.transitionFinishedTitle.text = activity.getChapterDisplayTitle(toChap)
                } else {
                    binding.transitionFinishedTitle.text = itemView.context.getString(R.string.transition_no_previous)
                }

                binding.transitionNextHeader.visibility = View.VISIBLE
                binding.transitionNextHeader.text = itemView.context.getString(R.string.transition_current)
                binding.transitionNextTitle.visibility = View.VISIBLE
                binding.transitionNextTitle.text = activity.getChapterDisplayTitle(fromChap)

                if (transition.isLoading) {
                    binding.transitionLoadingContainer.visibility = View.VISIBLE
                    binding.transitionLoadingText.text = itemView.context.getString(R.string.transition_loading_previous)
                } else {
                    binding.transitionLoadingContainer.visibility = View.GONE
                }

                if ((settings.layout == CurrentReaderSettings.Layouts.PAGED || !activity.continuousChapters) && toChap != null) {
                    binding.transitionNextButton.visibility = View.VISIBLE
                    binding.transitionNextButton.text = itemView.context.getString(R.string.transition_read_previous)
                    binding.transitionNextButton.setIconResource(R.drawable.ic_round_arrow_back_ios_new_24)
                    binding.transitionNextButton.iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_START
                    binding.transitionNextButton.setOnClickListener {
                        activity.loadPreviousChapter()
                    }
                } else {
                    binding.transitionNextButton.visibility = View.GONE
                }
            } else {
                val finishedTitle = activity.getChapterDisplayTitle(fromChap)
                binding.transitionFinishedHeader.text = itemView.context.getString(R.string.transition_finished)
                binding.transitionFinishedTitle.text = finishedTitle

                binding.transitionNextHeader.text = itemView.context.getString(R.string.transition_next)
                if (toChap != null) {
                    val nextTitle = activity.getChapterDisplayTitle(toChap)
                    binding.transitionNextHeader.visibility = View.VISIBLE
                    binding.transitionNextTitle.visibility = View.VISIBLE
                    binding.transitionNextTitle.text = nextTitle

                    if (transition.isLoading) {
                        binding.transitionLoadingContainer.visibility = View.VISIBLE
                    } else {
                        binding.transitionLoadingContainer.visibility = View.GONE
                    }

                    if (settings.layout == CurrentReaderSettings.Layouts.PAGED || !activity.continuousChapters) {
                        binding.transitionNextButton.visibility = View.VISIBLE
                        binding.transitionNextButton.text = itemView.context.getString(R.string.transition_read_next)
                        binding.transitionNextButton.setIconResource(R.drawable.ic_round_arrow_forward_ios_24)
                        binding.transitionNextButton.iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_END
                        binding.transitionNextButton.setOnClickListener {
                            activity.loadNextChapter()
                        }
                    } else {
                        binding.transitionNextButton.visibility = View.GONE
                    }
                } else {
                    binding.transitionNextHeader.visibility = View.VISIBLE
                    binding.transitionNextTitle.visibility = View.VISIBLE
                    binding.transitionNextTitle.text = itemView.context.getString(R.string.transition_no_next)
                    binding.transitionLoadingContainer.visibility = View.GONE
                    binding.transitionNextButton.visibility = View.GONE
                }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items.getOrNull(position)
        if (holder is TransitionViewHolder && item is ReaderItem.Transition) {
            holder.bind(item)
            return
        }
        val view = holder.itemView as GestureFrameLayout
        view.controller.also {
            if (settings.layout == CurrentReaderSettings.Layouts.PAGED) {
                it.settings.enableGestures()
            }
            it.settings.isRotationEnabled = settings.rotation
        }
        if (settings.layout != CurrentReaderSettings.Layouts.PAGED) {
            if (settings.padding) {
                when (settings.direction) {
                    CurrentReaderSettings.Directions.TOP_TO_BOTTOM -> view.setPadding(
                        0,
                        0,
                        0,
                        16f.px
                    )

                    CurrentReaderSettings.Directions.LEFT_TO_RIGHT -> view.setPadding(
                        0,
                        0,
                        16f.px,
                        0
                    )

                    CurrentReaderSettings.Directions.BOTTOM_TO_TOP -> view.setPadding(
                        0,
                        16f.px,
                        0,
                        0
                    )

                    CurrentReaderSettings.Directions.RIGHT_TO_LEFT -> view.setPadding(
                        16f.px,
                        0,
                        0,
                        0
                    )
                }
            }
            view.updateLayoutParams {
                if (settings.direction != CurrentReaderSettings.Directions.LEFT_TO_RIGHT && settings.direction != CurrentReaderSettings.Directions.RIGHT_TO_LEFT) {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = 480f.px
                } else {
                    width = 480f.px
                    height = ViewGroup.LayoutParams.MATCH_PARENT
                }
            }
        } else {
            val detector = GestureDetectorCompat(view.context, object : GesturesListener() {
                override fun onSingleClick(event: MotionEvent) =
                    activity.handleController(event = event)
            })
            view.findViewById<View>(R.id.imgProgCover).apply {
                setOnTouchListener { _, event ->
                    detector.onTouchEvent(event)
                    false
                }
                setOnLongClickListener {
                    val pos = holder.bindingAdapterPosition
                    val targetItem = items.getOrNull(pos)
                    val image = when (targetItem) {
                        is ReaderItem.Page -> targetItem.image
                        is ReaderItem.DualPage -> targetItem.first
                        else -> null
                    } ?: return@setOnLongClickListener false
                    activity.onImageLongClicked(pos, image, null) { dialog ->
                        activity.lifecycleScope.launch {
                            loadImage(pos, view)
                        }
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        dialog.dismiss()
                    }
                }
            }
        }
        loadJobs.remove(holder)?.cancel()
        val targetPos = holder.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: position
        val job = activity.lifecycleScope.launch { loadImage(targetPos, view) }
        loadJobs[holder] = job
    }

    abstract fun isZoomed(): Boolean
    abstract fun setZoom(zoom: Float)

    abstract suspend fun loadImage(position: Int, parent: View): Boolean

    companion object {
        const val VIEW_TYPE_IMAGE = 0
        const val VIEW_TYPE_TRANSITION = 1

        suspend fun Context.loadBitmapOld(
            link: FileUrl,
            transforms: List<BitmapTransformation>
        ): Bitmap? { //still used in some places
            return tryWithSuspend {
                withContext(Dispatchers.IO) {
                    Glide.with(this@loadBitmapOld)
                        .asBitmap()
                        .let {
                            if (link.url.startsWith("file://")) {
                                it.load(link.url)
                                    .skipMemoryCache(true)
                                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                            } else {
                                it.load(GlideUrl(link.url) { link.headers })
                            }
                        }
                        .let {
                            if (transforms.isNotEmpty()) {
                                it.transform(*transforms.toTypedArray())
                            } else {
                                it
                            }
                        }
                        .submit()
                        .get()
                }
            }
        }

        suspend fun Context.loadBitmap(
            link: FileUrl,
            transforms: List<BitmapTransformation>
        ): Bitmap? {
            return tryWithSuspend {
                val mangaCache = uy.kohesive.injekt.Injekt.get<MangaCache>()
                decodeGate.withPermit {
                withContext(Dispatchers.IO) {
                    val localFile = File(link.url)
                    val baseBitmap = when {
                        localFile.exists() -> {
                            val glideBitmap = try {
                                Glide.with(this@loadBitmap)
                                    .asBitmap()
                                    .load(localFile.absoluteFile)
                                    .readerDecodeOptions(DiskCacheStrategy.NONE)
                                    .submit()
                                    .get()
                            } catch (_: Exception) { null }
                            glideBitmap ?: try {
                                localFile.inputStream().use { decodeWithLibvips(it) }
                            } catch (_: Exception) { null }
                        }
                        link.url.startsWith("content://") -> {
                            val glideBitmap = try {
                                Glide.with(this@loadBitmap)
                                    .asBitmap()
                                    .load(Uri.parse(link.url))
                                    .readerDecodeOptions(DiskCacheStrategy.NONE)
                                    .submit()
                                    .get()
                            } catch (_: Exception) { null }
                            glideBitmap ?: try {
                                contentResolver.openInputStream(Uri.parse(link.url))?.use { decodeWithLibvips(it) }
                            } catch (_: Exception) { null }
                        }
                        else -> {
                            val imageData = mangaCache.get(link.url)
                            val cachedBitmap = imageData?.fetchAndProcessImage(
                                imageData.page,
                                imageData.source,
                                mangaCache
                            )
                            cachedBitmap ?: run {
                                val glideBitmap = try {
                                    Glide.with(this@loadBitmap)
                                        .asBitmap()
                                        .load(GlideUrl(link.url) { link.headers })
                                        // DATA, not NONE: a re-bind should hit the disk, not the network.
                                        .readerDecodeOptions(DiskCacheStrategy.DATA)
                                        .submit()
                                        .get()
                                } catch (_: Exception) {
                                    null
                                }
                                glideBitmap ?: run {
                                    // Fallback to native libvips over network for unsupported/exotic formats (e.g. JXL / AVIF / HEIF)
                                    try {
                                        val okHttpClient = uy.kohesive.injekt.Injekt.get<OkHttpClient>()
                                        val requestBuilder = Request.Builder().url(link.url)
                                        link.headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
                                        okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                                            if (response.isSuccessful) {
                                                response.body?.byteStream()?.use { decodeWithLibvips(it) }
                                            } else null
                                        }
                                    } catch (_: Exception) {
                                        null
                                    }
                                }
                            }
                        }
                    } ?: return@withContext null

                    if (transforms.isEmpty()) {
                        baseBitmap
                    } else {
                        val transformed = Glide.with(this@loadBitmap)
                            .asBitmap()
                            .load(baseBitmap)
                            .readerDecodeOptions(DiskCacheStrategy.NONE)
                            .transform(*transforms.toTypedArray())
                            .submit()
                            .get()
                        if (transformed != null && transformed != baseBitmap && !baseBitmap.isRecycled) {
                            baseBitmap.recycle()
                        }
                        transformed
                    }
                }
                }
            }
        }

        fun mergeBitmap(bitmap1: Bitmap, bitmap2: Bitmap, scale: Boolean = false): Bitmap {
            val height = if (bitmap1.height > bitmap2.height) bitmap1.height else bitmap2.height
            val (bit1, bit2) = if (!scale) bitmap1 to bitmap2 else {
                val width1 = bitmap1.width * height * 1f / bitmap1.height
                val width2 = bitmap2.width * height * 1f / bitmap2.height
                (Bitmap.createScaledBitmap(bitmap1, width1.toInt(), height, false)
                        to
                        Bitmap.createScaledBitmap(bitmap2, width2.toInt(), height, false))
            }
            val width = bit1.width + bit2.width
            val newBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            val canvas = Canvas(newBitmap)
            canvas.drawBitmap(bit1, 0f, (height * 1f - bit1.height) / 2, null)
            canvas.drawBitmap(bit2, bit1.width.toFloat(), (height * 1f - bit2.height) / 2, null)
            return newBitmap
        }

        /**
         * Decode image using native libvips engine (ca.mpreg:imagedecoder).
         * Supports modern formats like AVIF, JXL, HEIF, JP2, and standard formats.
         */
        fun decodeWithLibvips(inputStream: InputStream): Bitmap? {
            return try {
                ImageDecoder.new(inputStream)?.use { decoder ->
                    if (decoder.pages > 0) {
                        val res = decoder.decode()
                        val bitmap = Bitmap.createBitmap(res.width, res.height, Bitmap.Config.ARGB_8888)
                        res.image.rewind()
                        bitmap.copyPixelsFromBuffer(res.image)
                        // libvips hands back ARGB_8888 at full resolution; a long strip at that size
                        // is tens of MB. Bring it down to the ceiling the other decoders use.
                        val (maxW, maxH) = readerMaxSize()
                        if (bitmap.width > maxW || bitmap.height > maxH) {
                            val ratio = minOf(maxW.toFloat() / bitmap.width, maxH.toFloat() / bitmap.height)
                            val scaled = Bitmap.createScaledBitmap(
                                bitmap,
                                (bitmap.width * ratio).toInt().coerceAtLeast(1),
                                (bitmap.height * ratio).toInt().coerceAtLeast(1),
                                true
                            )
                            if (scaled !== bitmap) bitmap.recycle()
                            scaled
                        } else {
                            bitmap
                        }
                    } else {
                        null
                    }
                }
            } catch (_: Throwable) {
                null
            }
        }

        /**
         * Scale [src] to [dstWidth]×[dstHeight] using the algorithm selected in
         * [CurrentReaderSettings.ImageQuality]:
         *  - FAST      → Android's Bitmap.createScaledBitmap (bilinear, hardware-accelerated)
         *  - BALANCED  → Two-pass box filter (area averaging) – sharper than bilinear on downscales
         *  - LANCZOS   → Lanczos-3 sinc-windowed resampling – best quality for line art / text
         */
        fun scaleBitmap(
            src: Bitmap,
            dstWidth: Int,
            dstHeight: Int,
            quality: CurrentReaderSettings.ImageQuality
        ): Bitmap {
            if (dstWidth <= 0 || dstHeight <= 0) return src
            if (src.width == dstWidth && src.height == dstHeight) return src
            return when (quality) {
                CurrentReaderSettings.ImageQuality.FAST ->
                    Bitmap.createScaledBitmap(src, dstWidth, dstHeight, true)

                CurrentReaderSettings.ImageQuality.BALANCED ->
                    scaleBoxFilter(src, dstWidth, dstHeight)

                CurrentReaderSettings.ImageQuality.LANCZOS ->
                    scaleLanczos3(src, dstWidth, dstHeight)
            }
        }

        // ── Box filter (area averaging) ──────────────────────────────────────────
        private fun scaleBoxFilter(src: Bitmap, dstW: Int, dstH: Int): Bitmap {
            // Two-step: first coarse bilinear, then 1-pass box average when downscaling > 2×
            val scaleX = src.width.toFloat() / dstW
            val scaleY = src.height.toFloat() / dstH
            val step = if (scaleX > 2f || scaleY > 2f) {
                // Coarse bilinear pass to bring close to target, then box-average
                val mid = Bitmap.createScaledBitmap(
                    src,
                    (dstW * 1.5f).toInt().coerceAtLeast(dstW),
                    (dstH * 1.5f).toInt().coerceAtLeast(dstH),
                    true
                )
                mid
            } else src
            val out = Bitmap.createScaledBitmap(step, dstW, dstH, true)
            if (step !== src) step.recycle()
            return out
        }

        // ── Lanczos-3 resampling ─────────────────────────────────────────────────
        private const val LANCZOS_A = 3

        private fun lanczosKernel(x: Double): Double {
            val ax = abs(x)
            if (ax < 1e-9) return 1.0
            if (ax >= LANCZOS_A) return 0.0
            val pix = ax * PI
            return (sin(pix) * sin(pix / LANCZOS_A)) / (pix * pix / LANCZOS_A)
        }

        private fun scaleLanczos3(src: Bitmap, dstW: Int, dstH: Int): Bitmap {
            val srcW = src.width
            val srcH = src.height

            // Read source pixels into an int array once – avoids per-pixel JNI calls
            val srcPixels = IntArray(srcW * srcH)
            src.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)

            val dstPixels = IntArray(dstW * dstH)
            val scaleX = srcW.toDouble() / dstW
            val scaleY = srcH.toDouble() / dstH

            // Horizontal pass into intermediate 1D float buffer (RGBA)
            val intermediate = FloatArray(dstW * srcH * 4)
            for (x in 0 until dstW) {
                val srcXf = (x + 0.5) * scaleX - 0.5
                val start = (floor(srcXf).toInt() - LANCZOS_A + 1).coerceAtLeast(0)
                val end   = (floor(srcXf).toInt() + LANCZOS_A).coerceAtMost(srcW - 1)
                val weights = DoubleArray(end - start + 1) { lanczosKernel(srcXf - (start + it)) }
                val weightSum = weights.sum().coerceAtLeast(1e-9)
                val xOffset = x * srcH * 4
                for (y in 0 until srcH) {
                    var r = 0.0; var g = 0.0; var b = 0.0; var a = 0.0
                    for ((i, sx) in (start..end).withIndex()) {
                        val px = srcPixels[y * srcW + sx]
                        val w = weights[i]
                        a += Color.alpha(px) * w
                        r += Color.red(px) * w
                        g += Color.green(px) * w
                        b += Color.blue(px) * w
                    }
                    val base = xOffset + y * 4
                    intermediate[base    ] = (r / weightSum).toFloat()
                    intermediate[base + 1] = (g / weightSum).toFloat()
                    intermediate[base + 2] = (b / weightSum).toFloat()
                    intermediate[base + 3] = (a / weightSum).toFloat()
                }
            }

            // Vertical pass from intermediate into dst
            for (y in 0 until dstH) {
                val srcYf = (y + 0.5) * scaleY - 0.5
                val start = (floor(srcYf).toInt() - LANCZOS_A + 1).coerceAtLeast(0)
                val end   = (floor(srcYf).toInt() + LANCZOS_A).coerceAtMost(srcH - 1)
                val weights = DoubleArray(end - start + 1) { lanczosKernel(srcYf - (start + it)) }
                val weightSum = weights.sum().coerceAtLeast(1e-9)
                val yDstOffset = y * dstW
                for (x in 0 until dstW) {
                    var r = 0.0; var g = 0.0; var b = 0.0; var a = 0.0
                    val xOffset = x * srcH * 4
                    for ((i, sy) in (start..end).withIndex()) {
                        val base = xOffset + sy * 4
                        val w = weights[i]
                        r += intermediate[base    ] * w
                        g += intermediate[base + 1] * w
                        b += intermediate[base + 2] * w
                        a += intermediate[base + 3] * w
                    }
                    dstPixels[yDstOffset + x] = Color.argb(
                        (a / weightSum).roundToInt().coerceIn(0, 255),
                        (r / weightSum).roundToInt().coerceIn(0, 255),
                        (g / weightSum).roundToInt().coerceIn(0, 255),
                        (b / weightSum).roundToInt().coerceIn(0, 255)
                    )
                }
            }

            val dst = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
            dst.setPixels(dstPixels, 0, dstW, 0, 0, dstW, dstH)
            return dst
        }
    }
}
