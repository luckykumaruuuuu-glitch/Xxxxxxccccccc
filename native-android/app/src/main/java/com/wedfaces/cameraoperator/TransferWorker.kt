package com.wedfaces.cameraoperator

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.wedfaces.cameraoperator.model.QueueState
import com.wedfaces.cameraoperator.network.WedFacesApiException
import java.io.File
import java.io.IOException
import kotlinx.coroutines.delay

class TransferWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private val services = (applicationContext as CameraOperatorApp).services

    override suspend fun doWork(): Result {
        val batchId = inputData.getString(KEY_BATCH_ID) ?: return Result.failure()
        val items = services.queue.getBatch(batchId)
        if (items.isEmpty()) return Result.success()

        try {
            for (item in items) {
                if (isStopped) return Result.success()
                val current = services.queue.getBatch(batchId).firstOrNull { it.id == item.id } ?: continue
                if (current.state == QueueState.IMPORTED ||
                    current.state == QueueState.CANCELLED ||
                    current.state == QueueState.UPLOADED_WAITING_IMPORT
                ) continue
                val localFile = current.temporaryFilePath?.let(::File)
                if (localFile == null || !localFile.isFile || localFile.length() == 0L) {
                    services.queue.update(
                        current.id,
                        QueueState.FAILED,
                        lastError = "The local original is missing. Reconnect the camera or reselect this image.",
                    )
                    continue
                }
                services.queue.update(current.id, QueueState.UPLOADING, progressBytes = 0, attempts = current.attempts + 1)
                services.api.uploadSelectedPhoto(
                    albumId = current.albumId,
                    uploadId = current.uploadId,
                    file = localFile,
                    mimeType = current.mimeType,
                ) { sent, _ ->
                    services.queue.update(current.id, QueueState.UPLOADING, progressBytes = sent)
                }
                services.queue.update(
                    current.id,
                    QueueState.UPLOADED_WAITING_IMPORT,
                    progressBytes = localFile.length(),
                )
            }
        } catch (error: WedFacesApiException) {
            val currentItems = services.queue.getBatch(batchId)
            currentItems.filter { it.state == QueueState.UPLOADING }.forEach { item ->
                if (error.retryable) {
                    services.queue.update(item.id, QueueState.WAITING_FOR_INTERNET, lastError = error.message)
                } else {
                    services.queue.update(item.id, QueueState.FAILED, lastError = error.message)
                }
            }
            return if (error.retryable) Result.retry() else Result.failure()
        } catch (error: IOException) {
            services.queue.getBatch(batchId)
                .filter { it.state == QueueState.UPLOADING }
                .forEach { services.queue.update(it.id, QueueState.WAITING_FOR_INTERNET, lastError = error.message ?: "Network request failed.") }
            return Result.retry()
        } catch (error: Exception) {
            services.queue.getBatch(batchId)
                .filter { it.state == QueueState.UPLOADING || it.state == QueueState.DOWNLOADING }
                .forEach { services.queue.update(it.id, QueueState.FAILED, lastError = error.message ?: "Transfer failed.") }
            return Result.failure()
        }

        val remaining = services.queue.getBatch(batchId).filter { it.state != QueueState.IMPORTED && it.state != QueueState.CANCELLED }
        if (remaining.isEmpty()) return Result.success()
        if (remaining.any { it.state == QueueState.FAILED }) return Result.failure()
        if (remaining.any { it.state != QueueState.UPLOADED_WAITING_IMPORT }) return Result.retry()

        try {
            val albumId = remaining.first().albumId
            var status = services.api.getAlbumImportStatus(albumId)
            services.queue.saveImportStatus(batchId, status)
            val importAlreadyStarted = remaining.any { it.importStarted }
            if (!importAlreadyStarted) {
                if (status.state == "RUNNING") {
                    for (attempt in 0 until 24) {
                        if (status.state != "RUNNING") break
                        delay(5_000)
                        status = services.api.getAlbumImportStatus(albumId)
                        services.queue.saveImportStatus(batchId, status)
                    }
                }
                if (status.state == "RUNNING") return Result.retry()
                services.api.startAlbumImport(albumId)
                services.queue.updateBatch(batchId, QueueState.UPLOADED_WAITING_IMPORT, importStarted = true)
                // Avoid mistaking a previous completed album import for this newly started batch.
                delay(1_500)
                status = services.api.getAlbumImportStatus(albumId)
                services.queue.saveImportStatus(batchId, status)
            } else {
                when (status.state) {
                    "COMPLETED" -> {
                        finishImported(batchId, status)
                        return Result.success()
                    }
                    "FAILED" -> {
                        val message = status.error ?: status.failures.firstOrNull() ?: "WED Faces import failed."
                        services.queue.updateBatch(batchId, QueueState.FAILED, error = message)
                        return Result.failure()
                    }
                    "NONE" -> {
                        services.api.startAlbumImport(albumId)
                        services.queue.updateBatch(batchId, QueueState.UPLOADED_WAITING_IMPORT, importStarted = true)
                    }
                    "RUNNING" -> Unit
                    else -> return Result.retry()
                }
            }

            if (status.state == "COMPLETED") {
                finishImported(batchId, status)
                return Result.success()
            }
            if (status.state == "FAILED") {
                val message = status.error ?: status.failures.firstOrNull() ?: "WED Faces import failed."
                services.queue.updateBatch(batchId, QueueState.FAILED, error = message)
                return Result.failure()
            }
            if (status.state == "NONE") {
                // The server may briefly report NONE while the import worker starts.
                delay(2_000)
                status = services.api.getAlbumImportStatus(albumId)
                services.queue.saveImportStatus(batchId, status)
                if (status.state == "NONE") return Result.retry()
            }

            repeat(24) {
                if (isStopped) return Result.success()
                delay(5_000)
                status = services.api.getAlbumImportStatus(albumId)
                services.queue.saveImportStatus(batchId, status)
                when (status.state) {
                    "COMPLETED" -> {
                        finishImported(batchId, status)
                        return Result.success()
                    }
                    "FAILED" -> {
                        val message = status.error ?: status.failures.firstOrNull() ?: "WED Faces import failed."
                        services.queue.updateBatch(batchId, QueueState.FAILED, error = message)
                        return Result.failure()
                    }
                }
            }
            return Result.retry()
        } catch (error: WedFacesApiException) {
            services.queue.updateBatch(
                batchId,
                if (error.retryable) QueueState.UPLOADED_WAITING_IMPORT else QueueState.FAILED,
                error.message,
            )
            return if (error.retryable) Result.retry() else Result.failure()
        } catch (error: IOException) {
            services.queue.updateBatch(
                batchId,
                QueueState.UPLOADED_WAITING_IMPORT,
                error.message ?: "Could not read import status.",
            )
            return Result.retry()
        }
    }

    private fun finishImported(batchId: String, status: com.wedfaces.cameraoperator.model.ImportStatus) {
        val items = services.queue.getBatch(batchId).filter { it.state != QueueState.CANCELLED }
        val namedFailures = items.associateWith { item ->
            status.failures.firstOrNull { failure -> failure.contains(item.fileName, ignoreCase = true) }
        }.filterValues { it != null }
        val reportedFailureCount = maxOf(status.failed ?: 0, namedFailures.size)
        val unmatchedFailureCount = (reportedFailureCount - namedFailures.size).coerceAtLeast(0)

        if (unmatchedFailureCount > 0) {
            val error = "WED Faces reports $reportedFailureCount failed import(s), but the response did not identify every file. Verify the result in WED Faces before retrying."
            namedFailures.forEach { (item, message) ->
                services.queue.update(item.id, QueueState.FAILED, lastError = message)
            }
            items.filter { it !in namedFailures.keys }.forEach { item ->
                services.queue.update(item.id, QueueState.IMPORT_REVIEW, lastError = error)
            }
            return
        }

        namedFailures.forEach { (item, message) ->
            services.queue.update(item.id, QueueState.FAILED, lastError = message)
        }
        items.filter { it !in namedFailures.keys }.forEach { item ->
            services.queue.update(item.id, QueueState.IMPORTED, lastError = null, importStarted = true)
            item.temporaryFilePath?.let(::File)?.delete()
        }
    }

    companion object {
        const val KEY_BATCH_ID = "batch_id"
    }
}
