package owntally.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** The background sync job (decision B1, M7). Android decides the time; 15 minutes is the shortest it allows. */
object BackgroundSync {
    const val NAME = "owntally-background-sync"
    const val EVERY_MINUTES = 15L

    /** One job, with the network, and nothing else: no charging or idle condition, so a phone in a pocket still syncs. */
    fun request(): PeriodicWorkRequest =
        PeriodicWorkRequestBuilder<SyncWorker>(EVERY_MINUTES, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()

    /** Keeps the job already scheduled (so starting the app does not push its next run back). */
    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request())
    }
}

/** Runs one pass over every group and the leaves to deliver. A failure is not retried at once: the next run is soon enough. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as OwnTallyApplication).container
        try {
            container.backgroundPass()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Whatever went wrong, the entries are on this phone and the next run tries again.
        }
        return Result.success()
    }
}
