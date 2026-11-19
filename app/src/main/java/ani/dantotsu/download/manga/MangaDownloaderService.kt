package ani.dantotsu.download.manga

import android.Manifest
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import ani.dantotsu.R
import ani.dantotsu.connections.crashlytics.CrashlyticsInterface
import ani.dantotsu.download.DownloadedType
import ani.dantotsu.download.DownloadsManager
import ani.dantotsu.download.DownloadsManager.Companion.findSubDirectory
import ani.dantotsu.download.DownloadsManager.Companion.getSubDirectory
import ani.dantotsu.media.Media
import ani.dantotsu.media.MediaType
import ani.dantotsu.media.manga.ImageData
import ani.dantotsu.media.manga.decodeFullSize
import ani.dantotsu.media.manga.MangaReadFragment.Companion.ACTION_DOWNLOAD_FAILED
import ani.dantotsu.media.manga.MangaReadFragment.Companion.ACTION_DOWNLOAD_FINISHED
import ani.dantotsu.media.manga.MangaReadFragment.Companion.ACTION_DOWNLOAD_PROGRESS
import ani.dantotsu.media.manga.MangaReadFragment.Companion.ACTION_DOWNLOAD_STARTED
import ani.dantotsu.media.manga.MangaReadFragment.Companion.EXTRA_CHAPTER_NUMBER
import ani.dantotsu.media.manga.MangaReadFragment.Companion.EXTRA_DOWNLOADED_BYTES
import ani.dantotsu.media.manga.MangaReadFragment.Companion.EXTRA_ESTIMATED_TOTAL_BYTES
import ani.dantotsu.snackString
import ani.dantotsu.util.Logger
import ani.dantotsu.util.NumberConverter.Companion.ofLength
import ani.dantotsu.util.SizeFormatter
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.anggrayudi.storage.file.deleteRecursively
import com.anggrayudi.storage.file.forceDelete
import com.anggrayudi.storage.file.openOutputStream
import com.google.gson.GsonBuilder
import com.google.gson.InstanceCreator
import eu.kanade.tachiyomi.data.notification.Notifications.CHANNEL_DOWNLOADER_PROGRESS
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SChapterImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tachiyomi.core.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.HttpURLConnection
import java.net.URL
import java.util.Queue
import java.util.concurrent.ConcurrentLinkedQueue

class MangaDownloaderService : Service() {

    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var builder: NotificationCompat.Builder
    private val downloadsManager: DownloadsManager = Injekt.get<DownloadsManager>()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val downloadJobs = mutableMapOf<String, Job>()
    private val mutex = Mutex()
    private var queueJob: Job? = null
    private val queueMutex = Mutex()

    override fun onBind(intent: Intent?): IBinder? {
        // This is only required for bound services.
        return null
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = NotificationManagerCompat.from(this)
        builder = NotificationCompat.Builder(this, CHANNEL_DOWNLOADER_PROGRESS).apply {
            setContentTitle("Manga Download Progress")
            setSmallIcon(R.drawable.ic_download_24)
            priority = NotificationCompat.PRIORITY_DEFAULT
            setOnlyAlertOnce(true)
            setProgress(0, 0, false)
        }
        startForegroundNotification()
        ContextCompat.registerReceiver(
            this,
            cancelReceiver,
            IntentFilter(ACTION_CANCEL_DOWNLOAD),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                builder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, builder.build())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        MangaServiceDataSingleton.downloadQueue.clear()
        MangaServiceDataSingleton.currentTasks.clear()
        MangaServiceDataSingleton.progress.clear()
        downloadJobs.clear()
        MangaServiceDataSingleton.isServiceRunning = false
        notificationManager.cancel(NOTIFICATION_ID)
        runCatching { unregisterReceiver(cancelReceiver) }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 caps dataSync services at 6h; stop cleanly instead of crashing
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        snackString("Download started")
        startForegroundNotification()
        MangaServiceDataSingleton.isServiceRunning = true
        startQueueProcessing()
        return START_NOT_STICKY
    }

    private fun startQueueProcessing() {
        serviceScope.launch {
            queueMutex.withLock {
                if (queueJob == null || queueJob?.isActive == false) {
                    queueJob = serviceScope.launch {
                        processQueueLoop()
                    }
                }
            }
        }
    }

    private suspend fun processQueueLoop() {
        val maxParallel = PrefManager.getVal<Int>(PrefName.MaxParallelDownloads).coerceIn(0, 10)
        val concurrency = if (maxParallel > 0) maxParallel else 1
        val semaphore = Semaphore(concurrency)
        val activeJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

        while (serviceScope.isActive) {
            val task = MangaServiceDataSingleton.downloadQueue.poll()
            if (task != null) {
                if (PrefManager.getVal<Boolean>(PrefName.DownloadWifiOnly) && !ani.dantotsu.isWifiConnected(this@MangaDownloaderService)) {
                    broadcastDownloadFailed(task.uniqueName)
                    broadcastDownloadFailed(task.chapter)
                    withContext(Dispatchers.Main) {
                        snackString(getString(R.string.download_wifi_only_warning))
                    }
                    continue
                }
                val taskKey = downloadKey(task.title, task.chapter)
                val job = serviceScope.launch {
                    semaphore.withPermit {
                        MangaServiceDataSingleton.currentTasks.add(task)
                        updateNotification()
                        try {
                            download(task)
                        } finally {
                            mutex.withLock {
                                downloadJobs.remove(taskKey)
                                MangaServiceDataSingleton.currentTasks.remove(task)
                                MangaServiceDataSingleton.progress.remove(taskKey)
                            }
                            activeJobs.remove(taskKey)
                            updateNotification()
                        }
                    }
                }
                mutex.withLock {
                    downloadJobs[taskKey] = job
                }
                activeJobs[taskKey] = job
            } else {
                if (activeJobs.isEmpty()) {
                    if (MangaServiceDataSingleton.downloadQueue.isEmpty()) {
                        break
                    }
                } else {
                    delay(500)
                }
            }
        }

        activeJobs.values.joinAll()

        queueMutex.withLock {
            if (MangaServiceDataSingleton.downloadQueue.isEmpty() && MangaServiceDataSingleton.currentTasks.isEmpty()) {
                withContext(Dispatchers.Main) {
                    notificationManager.cancel(NOTIFICATION_ID)
                    stopSelf()
                }
            } else {
                queueJob = serviceScope.launch {
                    processQueueLoop()
                }
            }
        }
    }

    fun cancelDownload(key: String) {
        val tasks = MangaServiceDataSingleton.downloadQueue.filter { downloadKey(it.title, it.chapter) == key }
        tasks.forEach { task ->
            broadcastDownloadFailed(task.uniqueName)
            broadcastDownloadFailed(task.chapter)
        }
        serviceScope.launch {
            mutex.withLock {
                downloadJobs.remove(key)?.cancel()
                MangaServiceDataSingleton.downloadQueue.removeAll { downloadKey(it.title, it.chapter) == key }
                updateNotification()
            }
        }
    }

    private fun updateNotification() {
        val pendingDownloads = MangaServiceDataSingleton.downloadQueue.size + MangaServiceDataSingleton.currentTasks.size
        if (pendingDownloads <= 0 && downloadJobs.isEmpty()) {
            notificationManager.cancel(NOTIFICATION_ID)
            return
        }
        val text = if (pendingDownloads > 0) {
            "Pending downloads: $pendingDownloads"
        } else {
            "All downloads completed"
        }
        builder.setContentText(text)
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    suspend fun download(task: DownloadTask) {
        if (PrefManager.getVal<Boolean>(PrefName.DownloadWifiOnly) && !ani.dantotsu.isWifiConnected(this@MangaDownloaderService)) {
            broadcastDownloadFailed(task.uniqueName)
            broadcastDownloadFailed(task.chapter)
            withContext(Dispatchers.Main) {
                snackString(getString(R.string.download_wifi_only_warning))
            }
            return
        }
        var outputStarted = false
        try {
            withContext(Dispatchers.IO) {
                val notifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ContextCompat.checkSelfPermission(
                        this@MangaDownloaderService,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }

                val deferredMap = mutableMapOf<Int, Deferred<Unit>>()
                builder.setContentText("Downloading ${task.title} - ${task.chapter}")
                if (notifi) {
                    withContext(Dispatchers.Main) {
                        notificationManager.notify(NOTIFICATION_ID, builder.build())
                    }
                }

                val baseOutputDir = getSubDirectory(
                    this@MangaDownloaderService,
                    MediaType.MANGA,
                    false,
                    task.title
                ) ?: throw Exception("Base output directory not found")
                val outputDir = getSubDirectory(
                    this@MangaDownloaderService,
                    MediaType.MANGA,
                    false,
                    task.title,
                    task.chapter
                ) ?: throw Exception("Output directory not found")

                outputDir.deleteRecursively(this@MangaDownloaderService, true)
                outputStarted = true

                var farthest = 0
                var downloadedBytes = 0L
                for ((index, image) in task.imageData.withIndex()) {
                    if (deferredMap.size >= task.simultaneousDownloads) {
                        deferredMap.values.awaitAll()
                        deferredMap.clear()
                    }

                    deferredMap[index] = async(Dispatchers.IO) {
                        // Full resolution: the reader's decodeSampled() shrinks pages to save memory,
                        // which made downloaded chapters blurry.
                        var bitmap: Bitmap? = null
                        var retryCount = 0

                        // Wi-Fi can drop mid-chapter; fail instead of continuing on mobile data
                        if (PrefManager.getVal<Boolean>(PrefName.DownloadWifiOnly) && !ani.dantotsu.isWifiConnected(this@MangaDownloaderService)) {
                            throw Exception(getString(R.string.download_wifi_only_warning))
                        }

                        while (bitmap == null && retryCount < task.retries) {
                            bitmap = image.fetchImageBytes(
                                image.page,
                                image.source
                            )?.let { decodeFullSize(it) }
                            if (bitmap == null) {
                                snackString("${task.chapter} - Retrying to download page ${index.ofLength(3)}, attempt ${retryCount + 1}.")
                            }
                            retryCount++
                        }

                        if (bitmap == null) {
                            outputDir.deleteRecursively(this@MangaDownloaderService, false)
                            throw Exception("${task.chapter} - Unable to download all pages after $retryCount attempts. Try again.")
                        }

                        val writtenBytes = try {
                            saveToDisk("${index.ofLength(3)}.jpg", outputDir, bitmap)
                        } catch (e: Exception) {
                            // a page that did not reach disk fails the chapter, no partial chapter kept
                            outputDir.deleteRecursively(this@MangaDownloaderService, false)
                            throw e
                        } finally {
                            bitmap.recycle()
                        }
                        downloadedBytes += writtenBytes
                        farthest++

                        builder.setProgress(task.imageData.size, farthest, false)

                        val estimatedTotalBytes = SizeFormatter.estimateTotalBytesByFraction(
                            downloadedBytes,
                            farthest,
                            task.imageData.size
                        )
                        val progressPercent = farthest * 100 / task.imageData.size
                        MangaServiceDataSingleton.progress[downloadKey(task.title, task.chapter)] = progressPercent
                        broadcastDownloadProgress(
                            task.uniqueName,
                            progressPercent,
                            downloadedBytes,
                            estimatedTotalBytes
                        )
                        broadcastDownloadProgress(
                            task.chapter,
                            progressPercent,
                            downloadedBytes,
                            estimatedTotalBytes
                        )
                        if (notifi) {
                            withContext(Dispatchers.Main) {
                                notificationManager.notify(NOTIFICATION_ID, builder.build())
                            }
                        }
                        Unit
                    }
                }

                deferredMap.values.awaitAll()

                withContext(Dispatchers.Main) {
                    builder.setContentText("${task.title} - ${task.chapter} Download complete")
                        .setProgress(0, 0, false)
                    if (notifi) {
                        notificationManager.notify(NOTIFICATION_ID, builder.build())
                    }
                }

                saveMediaInfo(task, baseOutputDir)
                downloadsManager.addDownload(
                    DownloadedType(
                        task.title,
                        task.chapter,
                        MediaType.MANGA,
                        scanlator = task.scanlator,
                    )
                )
                broadcastDownloadFinished(task.uniqueName)
                broadcastDownloadFinished(task.chapter)
                snackString("${task.title} - ${task.chapter} Download finished")
            }
        } catch (e: Exception) {
            // failed or cancelled: drop this chapter's partial pages so they are never imported as complete
            if (outputStarted) runCatching {
                findSubDirectory(this, MediaType.MANGA, task.title, task.chapter)
                    ?.deleteRecursively(this, false)
            }
            Logger.log("Exception while downloading file: ${e.message}")
            snackString("Exception while downloading file: ${e.message}")
            Injekt.get<CrashlyticsInterface>().logException(e)
            broadcastDownloadFailed(task.uniqueName)
            broadcastDownloadFailed(task.chapter)
        }
    }


    private fun saveToDisk(
        fileName: String,
        directory: DocumentFile,
        bitmap: Bitmap
    ): Long {
        try {
            directory.findFile(fileName)?.forceDelete(this)
            val file =
                directory.createFile("image/jpeg", fileName) ?: throw Exception("File not created")

            file.openOutputStream(this, false).use { outputStream ->
                if (outputStream == null) throw Exception("Output stream is null")
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)) {
                    throw Exception("Image compression failed")
                }
            }
            return file.length()
        } catch (e: Exception) {
            println("Exception while saving image: ${e.message}")
            Injekt.get<CrashlyticsInterface>().logException(e)
            throw e
        }
    }

    // Chapters of one title download in parallel and share media.json; without this lock one
    // chapter deleted the file another was writing, and the uncaught error crashed the app.
    private val mediaInfoMutex = Mutex()

    @OptIn(DelicateCoroutinesApi::class)
    private fun saveMediaInfo(task: DownloadTask, directory: DocumentFile) {
        launchIO {
            try {
                mediaInfoMutex.withLock { writeMediaInfo(task, directory) }
            } catch (e: Exception) {
                Logger.log("Failed to save media info: ${e.message}")
            }
        }
    }

    private suspend fun writeMediaInfo(task: DownloadTask, directory: DocumentFile) {
        directory.findFile("media.json")?.forceDelete(this@MangaDownloaderService)
        val file = directory.createFile("application/json", "media.json")
            ?: throw Exception("File not created")
        val gson = GsonBuilder()
            .registerTypeAdapter(SChapter::class.java, InstanceCreator<SChapter> {
                SChapterImpl() // Provide an instance of SChapterImpl
            })
            .create()
        val mediaJson = gson.toJson(task.sourceMedia)
        val media = gson.fromJson(mediaJson, Media::class.java) ?: return
        media.cover = media.cover?.let { downloadImage(it, directory, "cover.jpg") }
        media.banner = media.banner?.let { downloadImage(it, directory, "banner.jpg") }
        val jsonString = gson.toJson(media)
        file.openOutputStream(this@MangaDownloaderService, false).use { output ->
            if (output == null) throw Exception("Output stream is null")
            output.write(jsonString.toByteArray())
        }
    }


    private suspend fun downloadImage(url: String, directory: DocumentFile, name: String): String? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            println("Downloading url $url")
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connect()
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("Server returned HTTP ${connection.responseCode} ${connection.responseMessage}")
                }
                directory.findFile(name)?.forceDelete(this@MangaDownloaderService)
                val file =
                    directory.createFile("image/jpeg", name) ?: throw Exception("File not created")
                file.openOutputStream(this@MangaDownloaderService, false).use { output ->
                    if (output == null) throw Exception("Output stream is null")
                    connection.inputStream.use { input ->
                        input.copyTo(output)
                    }
                }
                return@withContext file.uri.toString()
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MangaDownloaderService,
                        "Exception while saving ${name}: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                null
            } finally {
                connection?.disconnect()
            }
        }

    private fun broadcastDownloadStarted(chapterNumber: String) {
        val intent = Intent(ACTION_DOWNLOAD_STARTED).apply {
            putExtra(EXTRA_CHAPTER_NUMBER, chapterNumber)
        }
        sendBroadcast(intent.setPackage(packageName))
    }

    private fun broadcastDownloadFinished(chapterNumber: String) {
        val intent = Intent(ACTION_DOWNLOAD_FINISHED).apply {
            putExtra(EXTRA_CHAPTER_NUMBER, chapterNumber)
        }
        sendBroadcast(intent.setPackage(packageName))
    }

    private fun broadcastDownloadFailed(chapterNumber: String) {
        val intent = Intent(ACTION_DOWNLOAD_FAILED).apply {
            putExtra(EXTRA_CHAPTER_NUMBER, chapterNumber)
        }
        sendBroadcast(intent.setPackage(packageName))
    }

    private fun broadcastDownloadProgress(
        chapterNumber: String,
        progress: Int,
        downloadedBytes: Long,
        estimatedTotalBytes: Long
    ) {
        val intent = Intent(ACTION_DOWNLOAD_PROGRESS).apply {
            putExtra(EXTRA_CHAPTER_NUMBER, chapterNumber)
            putExtra("progress", progress)
            putExtra(EXTRA_DOWNLOADED_BYTES, downloadedBytes)
            putExtra(EXTRA_ESTIMATED_TOTAL_BYTES, estimatedTotalBytes)
        }
        sendBroadcast(intent.setPackage(packageName))
    }

    private val cancelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_CANCEL_DOWNLOAD) {
                intent.getStringExtra(EXTRA_TASK_KEY)?.let { cancelDownload(it) }
            }
        }
    }


    data class DownloadTask(
        val title: String,
        val chapter: String,
        val scanlator: String,
        val imageData: List<ImageData>,
        val sourceMedia: Media? = null,
        val retries: Int = 2,
        val simultaneousDownloads: Int = 2,
    ) {
        val uniqueName: String
            get() = "$chapter-$scanlator"
    }

    companion object {
        private const val NOTIFICATION_ID = 1104
        const val ACTION_CANCEL_DOWNLOAD = "ani.dantotsu.action_cancel_manga_download"
        const val EXTRA_TASK_KEY = "extra_task_key"

        // Key of a running or queued task: title + chapter, as downloadJobs is keyed
        fun downloadKey(title: String, chapter: String) = "${title}_$chapter"
    }
}

object MangaServiceDataSingleton {
    var imageData: List<ImageData> = listOf()
    var sourceMedia: Media? = null
    var downloadQueue: Queue<MangaDownloaderService.DownloadTask> = ConcurrentLinkedQueue()
    val currentTasks = java.util.Collections.synchronizedList(mutableListOf<MangaDownloaderService.DownloadTask>())
    val progress = java.util.concurrent.ConcurrentHashMap<String, Int>()

    @Volatile
    var isServiceRunning: Boolean = false
}
