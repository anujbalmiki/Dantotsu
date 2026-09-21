package ani.dantotsu.media.manga

import android.content.ContentResolver
import android.content.ContentValues
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import ani.dantotsu.util.createDataSaver
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream

data class ImageData(
    val page: Page,
    val source: HttpSource
) {
    suspend fun fetchAndProcessImage(
        page: Page,
        httpSource: HttpSource,
        cache: MangaCache? = null
    ): Bitmap? {
        return withContext(Dispatchers.IO) {
            try {
                val originalUrl = page.imageUrl ?: ""

                // Re-binding a page used to re-download it every single time, which is what
                // kept the OkHttp dispatcher (and the heap) permanently busy while scrolling.
                cache?.getBytes(originalUrl)?.let { cached ->
                    decodeSampled(cached)?.let { return@withContext it }
                }

                val dataSaver = createDataSaver()
                val compressedUrl = dataSaver.compress(originalUrl)
                var bytes: ByteArray? = null

                if (compressedUrl != originalUrl) {
                    try {
                        page.imageUrl = compressedUrl
                        httpSource.getImage(page).use { response ->
                            Logger.log("DataSaver Response: ${response.code} - ${response.message}")
                            if (response.isSuccessful) bytes = response.body.bytes()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.log("DataSaver failed, falling back to original: ${e.message}")
                    } finally {
                        page.imageUrl = originalUrl
                    }
                }

                if (bytes == null) {
                    if (compressedUrl != originalUrl) {
                        Logger.log("DataSaver failed or was blocked by Cloudflare; falling back to original URL: $originalUrl")
                    }
                    httpSource.getImage(page).use { response ->
                        Logger.log("Response: ${response.code} - ${response.message}")
                        if (response.isSuccessful) bytes = response.body.bytes()
                    }
                }

                val data = bytes ?: return@withContext null
                cache?.putBytes(originalUrl, data)
                return@withContext decodeSampled(data)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.log("MangaCache image fetch error: ${e.message}")
                return@withContext null
            }
        }
    }
}

/** Anything above this many pixels is more than any phone screen can show. ~32 MB at RGB_565. */
private const val MAX_BITMAP_PIXELS = 16_000_000L

/**
 * Power-of-two subsample factor that brings a [width]x[height] image down to roughly twice the
 * screen width (enough headroom to zoom) while staying under [MAX_BITMAP_PIXELS].
 * Without this, long webtoon strips were decoded at full resolution and exhausted the heap.
 */
fun calcInSampleSize(width: Int, height: Int): Int {
    val targetWidth = (Resources.getSystem().displayMetrics.widthPixels * 2).coerceAtLeast(1080)
    var sample = 1
    while (sample < 32) {
        val w = width / sample
        val h = height / sample
        if (w <= targetWidth && w.toLong() * h.toLong() <= MAX_BITMAP_PIXELS) break
        sample *= 2
    }
    return sample
}

/**
 * Upper bound for a decoded reader page: twice the screen width, and whatever height keeps the
 * total under [MAX_BITMAP_PIXELS]. Used to cap Glide decodes the same way [calcInSampleSize]
 * caps the BitmapFactory ones.
 */
fun readerMaxSize(): Pair<Int, Int> {
    val w = (Resources.getSystem().displayMetrics.widthPixels * 2).coerceAtLeast(1080)
    return w to (MAX_BITMAP_PIXELS / w).toInt()
}

/** Decode compressed image [bytes] subsampled to screen size, in RGB_565 (half the bytes of ARGB_8888). */
fun decodeSampled(bytes: ByteArray): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = calcInSampleSize(bounds.outWidth, bounds.outHeight)
        })
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: Exception) {
        null
    }
}

fun saveImage(
    bitmap: Bitmap,
    contentResolver: ContentResolver,
    filename: String,
    format: Bitmap.CompressFormat,
    quality: Int
) {
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/${format.name.lowercase()}")
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/Dantotsu/Manga"
                )
            }

            val uri: Uri? =
                contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

            uri?.let {
                contentResolver.openOutputStream(it)?.use { os ->
                    bitmap.compress(format, quality, os)
                } ?: throw FileNotFoundException("Failed to open output stream for URI: $uri")
            }
        } else {
            val directory =
                File("${Environment.getExternalStorageDirectory()}${File.separator}Dantotsu${File.separator}Manga")
            if (!directory.exists()) {
                directory.mkdirs()
            }

            val file = File(directory, filename)

            // Check if the file already exists
            if (file.exists()) {
                println("File already exists: ${file.absolutePath}")
                return
            }

            FileOutputStream(file).use { outputStream ->
                bitmap.compress(format, quality, outputStream)
            }
        }
    } catch (e: FileNotFoundException) {
        println("File not found: ${e.message}")
    } catch (e: Exception) {
        println("Exception while saving image: ${e.message}")
    }
}

class MangaCache {
    private val maxEntries = 500
    private val cache = LruCache<String, ImageData>(maxEntries)
    // Compressed source bytes, not decoded bitmaps. A page is a few hundred KB here instead of
    // tens of MB, and nothing ever calls recycle() on a ByteArray, so it is safe to hand the same
    // entry to several callers.
    private val cacheSizeKb = ((Runtime.getRuntime().maxMemory() / 1024) / 16).toInt().coerceIn(16 * 1024, 48 * 1024)
    private val byteCache = object : LruCache<String, ByteArray>(cacheSizeKb) {
        override fun sizeOf(key: String, value: ByteArray): Int {
            return (value.size / 1024).coerceAtLeast(1)
        }
    }

    @Synchronized
    fun put(key: String, imageDate: ImageData) {
        cache.put(key, imageDate)
    }

    @Synchronized
    fun get(key: String): ImageData? = cache.get(key)

    @Synchronized
    fun remove(key: String) {
        cache.remove(key)
        byteCache.remove(key)
    }

    @Synchronized
    fun clear() {
        cache.evictAll()
        byteCache.evictAll()
    }

    @Synchronized
    fun putBytes(key: String, bytes: ByteArray) {
        if (key.isNotEmpty()) byteCache.put(key, bytes)
    }

    @Synchronized
    fun getBytes(key: String): ByteArray? = if (key.isEmpty()) null else byteCache.get(key)

    @Synchronized
    fun clearBytes() {
        byteCache.evictAll()
    }

    fun size(): Int = cache.size()


}
