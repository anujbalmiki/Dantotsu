package eu.kanade.tachiyomi.extension

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ani.dantotsu.parsers.mangayomi.MangayomiExtensions
import ani.dantotsu.parsers.novel.NovelExtensionManager
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.util.Logger
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

/** Refreshes the available-extension lists and notifies when installed extensions have updates. */
class ExtensionUpdateWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        PrefManager.init(applicationContext)
        if (!PrefManager.getVal<Boolean>(PrefName.ExtensionUpdateCheck)) return Result.success()
        return try {
            val anime = Injekt.get<AnimeExtensionManager>()
            val manga = Injekt.get<MangaExtensionManager>()
            val novel = Injekt.get<NovelExtensionManager>()
            anime.awaitInitialized()
            manga.awaitInitialized()
            // NovelExtensionManager has no suspend wait, only a flag.
            withTimeoutOrNull(30_000) { while (!novel.isInitialized) delay(250) }
            anime.findAvailableExtensions() // also refreshes the JS (Mangayomi) sources
            manga.findAvailableExtensions()
            novel.findAvailableExtensions()

            val names = anime.installedExtensionsFlow.value.filter { it.hasUpdate }.map { it.name } +
                manga.installedExtensionsFlow.value.filter { it.hasUpdate }.map { it.name } +
                novel.installedExtensionsFlow.value.filter { it.hasUpdate }.map { it.name } +
                novel.lnReaderManager.installedPluginsFlow.value.filter { it.hasUpdate }.map { it.name } +
                MangayomiExtensions.installed.value.filter { MangayomiExtensions.hasUpdate(it) }.map { it.name }
            if (names.isNotEmpty()) ExtensionUpdateNotifier(applicationContext).promptUpdates(names.distinct())
            Result.success()
        } catch (e: Exception) {
            Logger.log("ExtensionUpdateWorker failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "eu.kanade.tachiyomi.extension.ExtensionUpdateWorker"

        fun schedule(context: Context, enabled: Boolean) {
            val workManager = WorkManager.getInstance(context)
            if (!enabled) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<ExtensionUpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
