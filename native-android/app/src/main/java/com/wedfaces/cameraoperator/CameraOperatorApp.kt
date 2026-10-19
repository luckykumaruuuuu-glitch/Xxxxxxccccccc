package com.wedfaces.cameraoperator

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.wedfaces.cameraoperator.camera.CameraNetworkController
import com.wedfaces.cameraoperator.camera.SonyPtpIpCameraAdapter
import com.wedfaces.cameraoperator.data.AndroidTokenStore
import com.wedfaces.cameraoperator.data.SettingsRepository
import com.wedfaces.cameraoperator.data.TokenStore
import com.wedfaces.cameraoperator.data.TransferQueueRepository
import com.wedfaces.cameraoperator.network.WedFacesApi
import java.util.concurrent.TimeUnit

class CameraOperatorApp : Application() {
    lateinit var services: AppServices
        private set

    override fun onCreate() {
        super.onCreate()
        services = AppServices(this)
    }

    internal fun replaceServicesForTest(testServices: AppServices) {
        services.queue.close()
        services = testServices
    }
}

class AppServices(
    application: Application,
    tokenStore: TokenStore = AndroidTokenStore(application),
    queueDatabaseName: String = "camera_operator_queue.db",
    private val retrySchedulerForTest: ((batchId: String, delayMillis: Long) -> Unit)? = null,
) {
    val settings = SettingsRepository(application)
    val tokens = tokenStore
    val queue = TransferQueueRepository(application, queueDatabaseName)
    val cameraNetwork = CameraNetworkController(application)
    val camera = SonyPtpIpCameraAdapter(application)
    val api = WedFacesApi(
        baseUrl = { settings.snapshot().serverUrl },
        allowLocalHttp = { settings.snapshot().allowLocalHttp },
        tokenStore = tokenStore,
    )

    private val workManager by lazy { WorkManager.getInstance(application) }

    fun enqueueBatch(batchId: String) {
        workManager.enqueueUniqueWork(
            workName(batchId),
            ExistingWorkPolicy.KEEP,
            transferRequest(batchId).build(),
        )
    }

    fun scheduleBatchRetry(batchId: String, delayMillis: Long) {
        retrySchedulerForTest?.let {
            it(batchId, delayMillis.coerceAtLeast(0L))
            return
        }
        val request = transferRequest(batchId)
            .setInitialDelay(delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .build()
        workManager.enqueueUniqueWork(
            workName(batchId),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    private fun transferRequest(batchId: String) =
        OneTimeWorkRequestBuilder<TransferWorker>()
            .setInputData(workDataOf(TransferWorker.KEY_BATCH_ID to batchId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                30,
                TimeUnit.SECONDS,
            )

    private fun workName(batchId: String) = "wedfaces-transfer-$batchId"

    fun cancelBatch(batchId: String) {
        workManager.cancelUniqueWork(workName(batchId))
    }
}
