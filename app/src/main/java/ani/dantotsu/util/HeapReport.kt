package ani.dantotsu.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Debug
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.FileProvider
import ani.dantotsu.R
import shark.HeapAnalysisFailure
import shark.HeapAnalyzer
import shark.HeapGraph
import shark.HeapObject.HeapInstance
import shark.HprofHeapGraph.Companion.openHeapGraph
import shark.LeakingObjectFinder
import shark.OnAnalysisProgressListener
import shark.AndroidObjectInspectors
import shark.AndroidReferenceMatchers
import java.io.File
import java.util.PriorityQueue
import kotlin.concurrent.thread

/**
 * Dump the heap, then parse it with Shark in a separate process (the reader's own heap is too
 * full to do it there) and write a plain-text report: what takes the space, and the reference
 * path that keeps the biggest things alive. Reached from a long-press on the reader page counter.
 */
object HeapReport {
    const val EXTRA_HEADER = "header"

    fun dumpFile(context: Context) = File(context.cacheDir, "heap.hprof")
    fun reportFile(context: Context) = File(context.cacheDir, "heap_report.txt")

    fun statusFile(context: Context) = File(context.cacheDir, "heap_status.txt")

    fun isRunning(context: Context) = dumpFile(context).exists() && !reportFile(context).exists()

    /** Seconds since the analysis last wrote progress, or null if it never started. */
    fun statusAgeSec(context: Context): Long? {
        val f = statusFile(context)
        if (!f.exists()) return null
        return (System.currentTimeMillis() - f.lastModified()) / 1000
    }

    fun status(context: Context): String {
        val f = statusFile(context)
        return if (f.exists()) "${f.readText()} (${statusAgeSec(context)}s ago)" else "waiting to start"
    }

    internal fun setStatus(context: Context, text: String) {
        statusFile(context).writeText(text)
    }

    /** Every thread with its stack, busy ones first: shows what is still working while the reader sits idle. */
    fun threadDump(): String {
        val out = StringBuilder()
        Thread.getAllStackTraces().entries
            .sortedBy { if (it.key.state == Thread.State.RUNNABLE) 0 else 1 }
            .forEach { (t, stack) ->
                if (stack.isEmpty()) return@forEach
                out.append("\n\"${t.name}\" ${t.state}\n")
                stack.take(30).forEach { out.append("    at ").append(it).append('\n') }
            }
        return out.toString()
    }

    fun dumpStatus(context: Context): String {
        val dump = dumpFile(context)
        val ageSec = (System.currentTimeMillis() - dump.lastModified()) / 1000
        return "dump ${dump.length() / MB} MB, ${ageSec}s ago"
    }

    fun memoryLine(): String {
        val rt = Runtime.getRuntime()
        val javaUsed = (rt.totalMemory() - rt.freeMemory()) / MB
        val javaMax = rt.maxMemory() / MB
        val native = Debug.getNativeHeapAllocatedSize() / MB
        return "heap $javaUsed/$javaMax MB | native $native MB"
    }

    /** Freezes the app for a few seconds while the runtime writes the dump. */
    fun capture(context: Context, header: String) {
        val app = context.applicationContext
        reportFile(app).delete()
        statusFile(app).delete()
        thread(name = "heap-dump") {
            try {
                val dump = dumpFile(app)
                dump.delete()
                Debug.dumpHprofData(dump.absolutePath)
                app.startForegroundService(
                    Intent(app, HeapReportService::class.java).putExtra(EXTRA_HEADER, header)
                )
            } catch (e: Throwable) {
                dumpFile(app).delete()
                reportFile(app).writeText("$header\n\nHeap dump failed: ${e.stackTraceToString()}")
            }
        }
    }

    fun share(context: Context) {
        val report = reportFile(context)
        if (report.exists()) shareText(context, report.readText(), report)
    }

    fun shareText(context: Context, text: String, file: File? = null) {
        val report = file ?: File(context.cacheDir, "threads.txt").also { it.writeText(text) }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Dantotsu memory report")
            putExtra(Intent.EXTRA_TEXT, text.take(90_000))
            try {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", report)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
        context.startActivity(Intent.createChooser(intent, "Share"))
    }

    fun analyze(context: Context, hprof: File, header: String): String {
        val out = StringBuilder(header).append("\n\n")
        setStatus(context, "indexing ${hprof.length() / MB} MB dump")
        hprof.openHeapGraph().use { graph ->
            val picks = LinkedHashMap<Long, String>()
            setStatus(context, "counting objects")
            histogram(graph, out, picks)
            watched(graph, out, picks)
            bitmaps(graph, out, picks)
            // If the path search below gets the process killed, the counts are still worth having.
            File(context.cacheDir, "heap_partial.txt").writeText(out.toString() + "\n(paths not finished)\n")

            setStatus(context, "finding paths for ${picks.size} objects")
            out.append("\n=== Paths to GC root (${picks.size} objects) ===\n")
            picks.forEach { (id, why) -> out.append("@$id  $why\n") }
            val analysis = HeapAnalyzer(OnAnalysisProgressListener.NO_OP).analyze(
                heapDumpFile = hprof,
                graph = graph,
                leakingObjectFinder = object : LeakingObjectFinder {
                    override fun findLeakingObjectIds(graph: HeapGraph) = picks.keys
                },
                referenceMatchers = AndroidReferenceMatchers.appDefaults,
                computeRetainedHeapSize = false,
                objectInspectors = AndroidObjectInspectors.appDefaults,
            )
            if (analysis is HeapAnalysisFailure) {
                out.append(analysis.exception.stackTraceToString())
            } else {
                out.append(analysis.toString())
            }
        }
        return out.toString()
    }

    private class Bucket(var count: Long = 0, var bytes: Long = 0, var sample: Long = 0)

    private fun histogram(graph: HeapGraph, out: StringBuilder, picks: MutableMap<Long, String>) {
        val buckets = HashMap<String, Bucket>()
        val bigArrays = topN<Long>(8)

        fun add(name: String, id: Long, size: Long) {
            val b = buckets.getOrPut(name) { Bucket() }
            b.count++
            b.bytes += size
            // one instance per class is enough to show who holds that class
            b.sample = id
        }

        graph.instances.forEach { add(it.instanceClassName, it.objectId, it.byteSize.toLong()) }
        graph.objectArrays.forEach {
            val size = it.readByteSize().toLong()
            add(it.arrayClassName, it.objectId, size)
            bigArrays.offer(it.objectId, size)
        }
        graph.primitiveArrays.forEach {
            val size = it.readByteSize().toLong()
            add(it.arrayClassName, it.objectId, size)
            bigArrays.offer(it.objectId, size)
        }

        val total = buckets.values.sumOf { it.bytes }
        out.append("=== Heap by class (shallow), total ${total / MB} MB, ${graph.objectCount} objects ===\n")
        val top = buckets.entries.sortedByDescending { it.value.bytes }.take(35)
        top.forEach { (name, b) ->
            out.append("%8d KB  %9d x  %s\n".format(b.bytes / 1024, b.count, name))
        }
        top.take(6).forEach { (name, b) -> picks[b.sample] = "sample $name" }
        bigArrays.sorted().forEach { (id, size) ->
            val name = graph.findObjectById(id).let { o ->
                when (o) {
                    is shark.HeapObject.HeapObjectArray -> o.arrayClassName
                    is shark.HeapObject.HeapPrimitiveArray -> o.arrayClassName
                    else -> "?"
                }
            }
            picks[id] = "largest array $name ${size / 1024} KB"
        }
    }

    private val watchList = listOf(
        "ani.dantotsu.media.manga.mangareader.MangaReaderActivity",
        "ani.dantotsu.media.manga.mangareader.BaseImageAdapter",
        "ani.dantotsu.media.manga.mangareader.ReaderItem\$Page",
        "ani.dantotsu.media.manga.MangaChapter",
        "ani.dantotsu.parsers.MangaImage",
        "ani.dantotsu.media.manga.ImageData",
        "com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView",
        "com.alexvasilkov.gestures.views.GestureFrameLayout",
        "androidx.recyclerview.widget.RecyclerView",
        "android.app.Activity",
        "androidx.fragment.app.Fragment",
        "com.bumptech.glide.request.SingleRequest",
        "com.bumptech.glide.request.RequestFutureTarget",
        "com.bumptech.glide.load.engine.EngineResource",
        "kotlinx.coroutines.JobSupport",
        "android.content.SharedPreferencesImpl",
        "java.lang.Thread",
    )

    private fun watched(graph: HeapGraph, out: StringBuilder, picks: MutableMap<Long, String>) {
        out.append("\n=== Watched classes (instances incl. subclasses) ===\n")
        watchList.forEach { name ->
            val cls = graph.findClassByName(name)
            if (cls == null) {
                out.append("       -  $name\n")
                return@forEach
            }
            val list = cls.instances.toList()
            out.append("%8d  %s\n".format(list.size, name))
            if (name.endsWith("MangaReaderActivity") && list.size > 1) {
                list.take(3).forEach { picks[it.objectId] = "extra MangaReaderActivity" }
            }
        }
    }

    private fun bitmaps(graph: HeapGraph, out: StringBuilder, picks: MutableMap<Long, String>) {
        val cls = graph.findClassByName("android.graphics.Bitmap") ?: return
        var live = 0
        var recycled = 0
        var livePixels = 0L
        val biggest = topN<Long>(3)
        val liveIds = ArrayList<Long>()
        val sizes = HashMap<String, Int>()
        cls.instances.forEach { bmp ->
            if (bmp.bool("mRecycled") == true) {
                recycled++
                return@forEach
            }
            val w = bmp.int("mWidth") ?: 0
            val h = bmp.int("mHeight") ?: 0
            live++
            livePixels += w.toLong() * h
            biggest.offer(bmp.objectId, w.toLong() * h)
            liveIds.add(bmp.objectId)
            val key = "${w}x$h"
            sizes[key] = (sizes[key] ?: 0) + 1
        }
        out.append("\n=== Bitmaps: $live live (${livePixels / 1_000_000} Mpx), $recycled recycled ===\n")
        sizes.entries.sortedByDescending { it.value }.take(12).forEach { (k, v) ->
            out.append("%6d x  %s\n".format(v, k))
        }
        biggest.sorted().forEach { (id, px) -> picks[id] = "largest live Bitmap ${px / 1000} Kpx" }
        if (liveIds.size > 3) {
            listOf(liveIds.size / 4, liveIds.size / 2, liveIds.size * 3 / 4).forEach {
                picks[liveIds[it]] = "sample live Bitmap"
            }
        }
    }

    private fun HeapInstance.int(field: String) =
        this["android.graphics.Bitmap", field]?.value?.asInt

    private fun HeapInstance.bool(field: String) =
        this["android.graphics.Bitmap", field]?.value?.asBoolean

    private class TopN<T>(private val n: Int) {
        private val queue = PriorityQueue<Pair<T, Long>>(compareBy { it.second })
        fun offer(item: T, size: Long) {
            queue.add(item to size)
            if (queue.size > n) queue.poll()
        }
        fun sorted() = queue.sortedByDescending { it.second }
    }

    private fun <T> topN(n: Int) = TopN<T>(n)

    private const val MB = 1024L * 1024L
}

class HeapReportService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground so the low-memory killer leaves this process alone while the reader hogs RAM.
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Heap report", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_round_info_24)
            .setContentTitle("Building heap report")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        val header = intent?.getStringExtra(HeapReport.EXTRA_HEADER) ?: ""
        thread(name = "heap-report") {
            val dump = HeapReport.dumpFile(this)
            val report = try {
                HeapReport.analyze(this, dump, header)
            } catch (e: Throwable) {
                val partial = File(cacheDir, "heap_partial.txt").takeIf { it.exists() }?.readText() ?: header
                "$partial\n\nAnalysis failed: ${e.stackTraceToString()}"
            }
            HeapReport.reportFile(this).writeText(report)
            HeapReport.setStatus(this, "done")
            dump.delete()
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    this,
                    "Heap report ready. Long-press the page counter to share it.",
                    Toast.LENGTH_LONG
                ).show()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private companion object {
        const val CHANNEL = "heap_report"
        const val NOTIFICATION_ID = 7342
    }
}
