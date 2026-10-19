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
}

class AppServices(application: Application) {
    val settings = SettingsRepository(application)
    val tokens = AndroidTokenStore(application)
    val queue = TransferQueueRepository(application)
    val cameraNetwork = CameraNetworkController(application)
    val camera = SonyPtpIpCameraAdapter(application)
    val api = WedFacesApi(
        baseUrl = { settings.snapshot().serverUrl },
        allowLocalHttp = { settings.snapshot().allowLocalHttp },
        tokenStore = tokens,
    )

    private val workManager = WorkManager.getInstance(application)

    fun enqueueBatch(batchId: String) {
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
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
            .build()
        workManager.enqueueUniqueWork("wedfaces-transfer-$batchId", ExistingWorkPolicy.KEEP, request)
    }

    fun cancelBatch(batchId: String) {
        workManager.cancelUniqueWork("wedfaces-transfer-$batchId")
    }
}
