package com.wedfaces.cameraoperator

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.wedfaces.cameraoperator.model.QueueState
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CameraDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val services by lazy { (application as CameraOperatorApp).services }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val batchId = intent?.getStringExtra(KEY_BATCH_ID) ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val items = services.queue.getBatch(batchId).filter { it.state == QueueState.DOWNLOADING }
        startForeground(
            TransferNotifications.CAMERA_NOTIFICATION_ID,
            TransferNotifications.cameraNotification(this, batchId, 0, items.size),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            },
        )
        scope.launch {
            try {
                items.forEachIndexed { index, item ->
                    if (services.queue.getBatch(batchId).none { it.id == item.id && it.state == QueueState.DOWNLOADING }) {
                        return@forEachIndexed
                    }
                    val file = File(
                        filesDir,
                        "transfers/${item.id}_${item.fileName}",
                    )
                    try {
                        file.parentFile?.mkdirs()
                        services.queue.update(
                            item.id,
                            QueueState.DOWNLOADING,
                            progressBytes = 0,
                            temporaryFilePath = file.absolutePath,
                        )
                        services.camera.downloadSelectedOriginal(item.sourceObjectId, file) { sent, _ ->
                            services.queue.update(item.id, QueueState.DOWNLOADING, progressBytes = sent)
                        }
                        services.queue.update(
                            item.id,
                            QueueState.READY_TO_UPLOAD,
                            progressBytes = file.length(),
                            temporaryFilePath = file.absolutePath,
                        )
                        services.enqueueBatch(batchId)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        file.delete()
                        services.queue.update(
                            item.id,
                            QueueState.FAILED,
                            lastError = error.message ?: "Could not retrieve this original from the camera.",
                        )
                    }
                    val done = index + 1
                    val notification = TransferNotifications.cameraNotification(this@CameraDownloadService, batchId, done, items.size)
                    val manager = getSystemService(android.app.NotificationManager::class.java)
                    manager.notify(TransferNotifications.CAMERA_NOTIFICATION_ID, notification)
                }
            } catch (_: CancellationException) {
                return@launch
            } finally {
                services.queue.setBatchDownloadComplete(batchId, true)
                services.enqueueBatch(batchId)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val KEY_BATCH_ID = "batch_id"
    }
}
