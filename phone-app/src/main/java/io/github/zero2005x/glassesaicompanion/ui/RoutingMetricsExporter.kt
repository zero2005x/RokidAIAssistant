package io.github.zero2005x.glassesaicompanion.ui

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import io.github.zero2005x.glassesaicompanion.data.db.AppDatabase
import io.github.zero2005x.glassesaicompanion.data.db.RoutingMetricsCsv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Writes the routing metrics to a CSV in the app cache and hands it to the share sheet. Nothing is uploaded. */
object RoutingMetricsExporter {
    private const val TAG = "RoutingMetricsExporter"
    const val FILE_NAME = "routing_metrics.csv"

    /** The send intent for the CSV, or null when it could not be written. */
    suspend fun createShareIntent(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ): Intent? = withContext(ioDispatcher) {
        try {
            val rows = AppDatabase.getInstance(context).routingMetricDao().getAll()
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, FILE_NAME)
            file.writeText(RoutingMetricsCsv.build(rows), Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to export routing metrics", e)
            null
        }
    }
}
