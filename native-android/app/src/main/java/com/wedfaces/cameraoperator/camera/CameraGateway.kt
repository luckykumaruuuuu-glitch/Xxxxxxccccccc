package com.wedfaces.cameraoperator.camera

import android.net.Network
import com.wedfaces.cameraoperator.model.CameraPhoto
import java.io.File

interface CameraGateway {
    suspend fun connect(network: Network, endpoint: String? = null): String
    suspend fun listObjects(): List<CameraPhoto>
    suspend fun getThumbnail(objectId: String): ByteArray?
    suspend fun downloadSelectedOriginal(
        objectId: String,
        destination: File,
        onProgress: (sent: Long, total: Long) -> Unit,
    )
    suspend fun disconnect()
}
