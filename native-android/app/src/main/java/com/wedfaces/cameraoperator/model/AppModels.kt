package com.wedfaces.cameraoperator.model

import android.net.Uri

data class Album(
    val id: String,
    val name: String,
)

data class CameraPhoto(
    val objectId: String,
    val fileName: String,
    val sizeBytes: Long?,
    val capturedAtMillis: Long?,
    val formatCode: Int?,
    val mimeType: String?,
    val source: PhotoSource,
    val testUri: Uri? = null,
)

enum class PhotoSource {
    CAMERA,
    TEST_GALLERY,
}

enum class QueueState(val title: String) {
    SELECTED("Selected"),
    WAITING_FOR_CAMERA("Waiting for camera"),
    DOWNLOADING("Downloading original"),
    WAITING_FOR_INTERNET("Waiting for internet"),
    READY_TO_UPLOAD("Ready to upload"),
    UPLOADING("Uploading"),
    UPLOADED_WAITING_IMPORT("Uploaded · waiting for import"),
    IMPORTED("Imported"),
    FAILED("Failed · retry available"),
    UNSUPPORTED("Unsupported format"),
    CANCELLED("Cancelled"),
}

data class TransferItem(
    val id: String,
    val uploadId: String,
    val batchId: String,
    val albumId: String,
    val albumName: String,
    val source: PhotoSource,
    val sourceObjectId: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val temporaryFilePath: String?,
    val state: QueueState,
    val progressBytes: Long,
    val attempts: Int,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lastError: String?,
    val importStarted: Boolean,
)

data class ImportStatus(
    val state: String,
    val total: Int?,
    val imported: Int?,
    val duplicates: Int?,
    val failed: Int?,
    val failures: List<String>,
    val error: String?,
)

data class CameraConnection(
    val model: String,
    val address: String,
)
