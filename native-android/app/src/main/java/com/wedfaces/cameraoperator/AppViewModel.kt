package com.wedfaces.cameraoperator

import android.app.Application
import android.net.Network
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wedfaces.cameraoperator.data.TransferQueueRepository
import com.wedfaces.cameraoperator.model.Album
import com.wedfaces.cameraoperator.model.CameraPhoto
import com.wedfaces.cameraoperator.model.ImportStatus
import com.wedfaces.cameraoperator.model.PhotoSource
import com.wedfaces.cameraoperator.model.QueueState
import com.wedfaces.cameraoperator.model.TransferItem
import com.wedfaces.cameraoperator.network.WedFacesApiException
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppDestination(val title: String) {
    CAMERA("Camera"),
    QUEUE("Queue"),
    SETTINGS("Settings"),
}

data class AppUiState(
    val destination: AppDestination = AppDestination.CAMERA,
    val serverUrl: String = "",
    val email: String = "",
    val selectedAlbumId: String? = null,
    val selectedAlbumName: String? = null,
    val albums: List<Album> = emptyList(),
    val testMode: Boolean = false,
    val allowLocalHttp: Boolean = false,
    val signedIn: Boolean = false,
    val cameraConnected: Boolean = false,
    val cameraModel: String? = null,
    val cameraNetworkMessage: String? = null,
    val cameraBusy: Boolean = false,
    val wifiSsidInput: String = "",
    val wifiPasswordInput: String = "",
    val photos: List<CameraPhoto> = emptyList(),
    val selectedPhotoIds: Set<String> = emptySet(),
    val thumbnails: Map<String, ByteArray> = emptyMap(),
    val queue: List<TransferItem> = emptyList(),
    val importStatuses: Map<String, ImportStatus> = emptyMap(),
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val services = (application as CameraOperatorApp).services
    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()
    private var cameraNetwork: Network? = null

    init {
        val settings = services.settings.snapshot()
        _state.value = _state.value.copy(
            serverUrl = settings.serverUrl,
            email = settings.email,
            selectedAlbumId = settings.selectedAlbumId,
            selectedAlbumName = settings.selectedAlbumName,
            testMode = settings.testMode,
            allowLocalHttp = settings.allowLocalHttp,
            signedIn = services.tokens.accessToken() != null,
            queue = services.queue.getAll(),
        )
        _state.value = _state.value.copy(importStatuses = loadImportStatuses(_state.value.queue))
        if (_state.value.signedIn) refreshAlbums()
        viewModelScope.launch {
            while (true) {
                delay(1_500)
                val queue = services.queue.getAll()
                _state.value = _state.value.copy(queue = queue, importStatuses = loadImportStatuses(queue))
            }
        }
    }

    fun navigate(destination: AppDestination) {
        _state.value = _state.value.copy(destination = destination, message = null, error = null)
    }

    fun updateServerUrl(value: String) {
        _state.value = _state.value.copy(serverUrl = value)
    }

    fun updateEmail(value: String) {
        _state.value = _state.value.copy(email = value)
    }

    fun updateCameraWifiSsid(value: String) {
        _state.value = _state.value.copy(wifiSsidInput = value)
    }

    fun updateCameraWifiPassword(value: String) {
        _state.value = _state.value.copy(wifiPasswordInput = value)
    }

    fun onCameraPermissionDenied() {
        _state.value = _state.value.copy(
            cameraNetworkMessage = "Nearby Wi-Fi permission is needed only to connect to the camera. You can grant it in Android app settings.",
        )
    }

    fun saveServerSettings() {
        services.settings.saveServerUrl(_state.value.serverUrl)
        services.settings.saveEmail(_state.value.email)
        _state.value = _state.value.copy(message = "Server settings saved.", error = null)
    }

    fun setLocalHttpAllowed(enabled: Boolean) {
        services.settings.setAllowLocalHttp(enabled)
        _state.value = _state.value.copy(allowLocalHttp = enabled)
    }

    fun setTestMode(enabled: Boolean) {
        services.settings.setTestMode(enabled)
        _state.value = _state.value.copy(testMode = enabled, photos = emptyList(), selectedPhotoIds = emptySet())
        if (enabled) {
            _state.value = _state.value.copy(
                cameraConnected = false,
                cameraModel = null,
                cameraNetworkMessage = "TEST MODE — no camera connected. Choose actual images from this device.",
            )
            viewModelScope.launch {
                services.camera.disconnect()
                services.cameraNetwork.release()
                cameraNetwork = null
            }
        } else {
            _state.value = _state.value.copy(cameraNetworkMessage = "Test Mode is off. Connect to a supported Sony camera.")
        }
    }

    fun signIn(password: String) = viewModelScope.launch {
        val current = _state.value
        services.settings.saveServerUrl(current.serverUrl)
        services.settings.saveEmail(current.email)
        _state.value = current.copy(busy = true, error = null, message = null)
        try {
            val result = services.api.login(current.email, password)
            services.settings.saveEmail(result.email ?: current.email)
            _state.value = _state.value.copy(
                busy = false,
                signedIn = true,
                email = result.email ?: current.email,
                message = "Signed in to WED Faces.",
            )
            loadAlbums()
        } catch (error: Exception) {
            _state.value = _state.value.copy(busy = false, error = error.message ?: "Sign-in failed.")
        }
    }

    fun signOut() = viewModelScope.launch {
        services.api.clearSession()
        services.settings.saveAlbum(null, null)
        _state.value = _state.value.copy(
            signedIn = false,
            albums = emptyList(),
            selectedAlbumId = null,
            selectedAlbumName = null,
            message = "Signed out.",
            error = null,
        )
    }

    fun refreshAlbums() = viewModelScope.launch {
        if (!services.tokens.accessToken().isNullOrBlank()) loadAlbums()
    }

    private suspend fun loadAlbums() {
        _state.value = _state.value.copy(busy = true, error = null)
        try {
            val albums = services.api.listAlbums()
            val savedId = _state.value.selectedAlbumId
            val selected = albums.firstOrNull { it.id == savedId } ?: albums.firstOrNull()
            services.settings.saveAlbum(selected?.id, selected?.name)
            _state.value = _state.value.copy(
                busy = false,
                signedIn = true,
                albums = albums,
                selectedAlbumId = selected?.id,
                selectedAlbumName = selected?.name,
                message = "${albums.size} album${if (albums.size == 1) "" else "s"} loaded.",
            )
        } catch (error: Exception) {
            if (error is WedFacesApiException && error.statusCode == 401) {
                _state.value = _state.value.copy(signedIn = false)
            }
            _state.value = _state.value.copy(busy = false, error = error.message ?: "Could not load albums.")
        }
    }

    fun selectAlbum(album: Album) {
        services.settings.saveAlbum(album.id, album.name)
        _state.value = _state.value.copy(
            selectedAlbumId = album.id,
            selectedAlbumName = album.name,
            message = "Album selected: ${album.name}",
            error = null,
        )
    }

    fun connectCamera(network: Network, endpoint: String? = null) = viewModelScope.launch {
        _state.value = _state.value.copy(cameraBusy = true, cameraNetworkMessage = "Connecting to Sony camera…", error = null)
        try {
            val model = services.camera.connect(network, endpoint)
            cameraNetwork = network
            _state.value = _state.value.copy(
                cameraBusy = false,
                cameraConnected = true,
                cameraModel = model,
                cameraNetworkMessage = "$model connected.",
            )
            refreshCamera()
        } catch (error: Exception) {
            cameraNetwork = null
            _state.value = _state.value.copy(
                cameraBusy = false,
                cameraConnected = false,
                cameraModel = null,
                cameraNetworkMessage = error.message ?: "Camera connection failed.",
            )
        }
    }

    fun useCurrentWifi() {
        services.cameraNetwork.useCurrentWifi(
            onConnected = { network ->
                viewModelScope.launch {
                    connectCamera(network)
                }
            },
            onFailure = { message -> _state.value = _state.value.copy(cameraNetworkMessage = message) },
        )
    }

    fun requestCameraWifi(ssid: String, passphrase: String) {
        services.cameraNetwork.connectToCameraWifi(
            ssid,
            passphrase,
            onConnected = { network ->
                viewModelScope.launch { connectCamera(network) }
            },
            onNetworkLost = {
                viewModelScope.launch {
                    services.camera.disconnect()
                    cameraNetwork = null
                    _state.value = _state.value.copy(
                        cameraConnected = false,
                        cameraModel = null,
                        photos = emptyList(),
                        selectedPhotoIds = emptySet(),
                        cameraNetworkMessage = "Camera Wi-Fi disconnected.",
                    )
                }
            },
            onFailure = { message -> _state.value = _state.value.copy(cameraNetworkMessage = message) },
        )
    }

    fun refreshCamera() = viewModelScope.launch {
        if (_state.value.testMode) return@launch
        if (!_state.value.cameraConnected) {
            _state.value = _state.value.copy(cameraNetworkMessage = "Connect to the camera before browsing its card.")
            return@launch
        }
        _state.value = _state.value.copy(cameraBusy = true, error = null)
        try {
            val photos = services.camera.listObjects()
            _state.value = _state.value.copy(
                cameraBusy = false,
                photos = photos,
                selectedPhotoIds = _state.value.selectedPhotoIds.intersect(photos.map { it.objectId }.toSet()),
                cameraNetworkMessage = "Showing camera file information and thumbnails only. Originals are read only after you tap Send selected.",
            )
            photos.asSequence().filter { it.mimeType in SUPPORTED_MIME_TYPES }.take(30).forEach { photo ->
                viewModelScope.launch {
                    services.camera.getThumbnail(photo.objectId)?.let { bytes ->
                        _state.value = _state.value.copy(thumbnails = _state.value.thumbnails + (photo.objectId to bytes))
                    }
                }
            }
        } catch (error: Exception) {
            _state.value = _state.value.copy(cameraBusy = false, error = error.message ?: "Could not list camera images.")
        }
    }

    fun disconnectCamera() = viewModelScope.launch {
        services.camera.disconnect()
        services.cameraNetwork.release()
        cameraNetwork = null
        _state.value = _state.value.copy(
            cameraConnected = false,
            cameraModel = null,
            photos = emptyList(),
            selectedPhotoIds = emptySet(),
            thumbnails = emptyMap(),
            cameraNetworkMessage = "Camera disconnected.",
        )
    }

    fun openWifiSettings(context: android.content.Context) {
        services.cameraNetwork.openWifiSettings(context)
    }

    fun showTestImages(uris: List<Uri>) = viewModelScope.launch {
        if (!_state.value.testMode) {
            _state.value = _state.value.copy(error = "Enable Test Mode before choosing device images.")
            return@launch
        }
        val resolver = getApplication<Application>().contentResolver
        val imported = withContext(Dispatchers.IO) {
            uris.mapNotNull { uri ->
                val name = displayName(uri) ?: "test-image-${UUID.randomUUID()}.jpg"
                val mime = resolver.getType(uri)?.lowercase() ?: mimeTypeFor(name)
                val photo = CameraPhoto(
                    objectId = uri.toString(),
                    fileName = name,
                    sizeBytes = querySize(uri),
                    capturedAtMillis = null,
                    formatCode = null,
                    mimeType = mime,
                    source = PhotoSource.TEST_GALLERY,
                    testUri = uri,
                )
                photo
            }
        }
        _state.value = _state.value.copy(
            photos = imported,
            selectedPhotoIds = imported.filter(::isSupported).map { it.objectId }.toSet(),
            message = "${imported.size} device image${if (imported.size == 1) "" else "s"} loaded in TEST MODE.",
            error = null,
        )
    }

    fun togglePhoto(photo: CameraPhoto) {
        if (!isSupported(photo)) return
        val ids = _state.value.selectedPhotoIds
        _state.value = _state.value.copy(
            selectedPhotoIds = if (photo.objectId in ids) ids - photo.objectId else ids + photo.objectId,
        )
    }

    fun selectAllSupported() {
        _state.value = _state.value.copy(
            selectedPhotoIds = _state.value.photos.filter(::isSupported).map { it.objectId }.toSet(),
        )
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selectedPhotoIds = emptySet())
    }

    fun sendSelected() = viewModelScope.launch {
        val current = _state.value
        val albumId = current.selectedAlbumId
        val albumName = current.selectedAlbumName
        if (!current.signedIn || albumId.isNullOrBlank() || albumName.isNullOrBlank()) {
            _state.value = current.copy(error = "Sign in and select a WED Faces album in Settings first.")
            return@launch
        }
        val selected = current.photos.filter { it.objectId in current.selectedPhotoIds && isSupported(it) }
        if (selected.isEmpty()) {
            _state.value = current.copy(error = "Select at least one supported image.")
            return@launch
        }
        if (selected.any { it.source == PhotoSource.CAMERA } && !current.cameraConnected) {
            _state.value = current.copy(error = "Reconnect the camera before retrieving selected originals.")
            return@launch
        }
        val batchId = UUID.randomUUID().toString()
        _state.value = current.copy(busy = true, error = null, message = null, destination = AppDestination.QUEUE)
        for (photo in selected) {
            val item = TransferQueueRepository.newTransfer(
                batchId = batchId,
                albumId = albumId,
                albumName = albumName,
                source = photo.source,
                sourceObjectId = photo.objectId,
                fileName = safeFileName(photo.fileName),
                mimeType = mimeTypeFor(photo.fileName) ?: photo.mimeType.orEmpty(),
                sizeBytes = photo.sizeBytes,
                initialState = if (photo.source == PhotoSource.CAMERA) QueueState.DOWNLOADING else QueueState.READY_TO_UPLOAD,
            )
            services.queue.insert(item)
            val destination = File(getApplication<Application>().filesDir, "transfers/${item.id}_${item.fileName}")
            try {
                destination.parentFile?.mkdirs()
                if (photo.source == PhotoSource.CAMERA) {
                    services.queue.update(item.id, QueueState.DOWNLOADING, temporaryFilePath = destination.absolutePath)
                    services.camera.downloadSelectedOriginal(photo.objectId, destination) { sent, _ ->
                        services.queue.update(item.id, QueueState.DOWNLOADING, progressBytes = sent)
                    }
                    services.queue.update(
                        item.id,
                        QueueState.READY_TO_UPLOAD,
                        progressBytes = destination.length(),
                        temporaryFilePath = destination.absolutePath,
                    )
                } else {
                    val uri = photo.testUri ?: throw IllegalStateException("The selected device image is no longer available.")
                    withContext(Dispatchers.IO) {
                        getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
                            requireNotNull(input) { "Android could not open the selected image." }
                            FileOutputStream(destination).use(input::copyTo)
                        }
                    }
                    if (destination.length() == 0L) throw IllegalStateException("The selected device image is empty.")
                    services.queue.update(
                        item.id,
                        QueueState.READY_TO_UPLOAD,
                        progressBytes = destination.length(),
                        temporaryFilePath = destination.absolutePath,
                    )
                }
            } catch (error: Exception) {
                destination.delete()
                services.queue.update(item.id, QueueState.FAILED, lastError = error.message ?: "Could not prepare this original.")
            }
        }
        val batch = services.queue.getBatch(batchId)
        if (batch.any { it.state == QueueState.READY_TO_UPLOAD } && batch.none { it.state == QueueState.FAILED }) {
            services.enqueueBatch(batchId)
            _state.value = _state.value.copy(message = "Selected originals are queued. Upload starts when internet is available.")
        } else if (batch.any { it.state == QueueState.READY_TO_UPLOAD }) {
            services.enqueueBatch(batchId)
            _state.value = _state.value.copy(message = "Ready items are queued; failed camera retrievals need a retry.")
        } else {
            _state.value = _state.value.copy(error = "No originals could be prepared. Check the camera connection and try again.")
        }
        _state.value = _state.value.copy(busy = false, queue = services.queue.getAll(), selectedPhotoIds = emptySet())
    }

    fun retryBatch(batchId: String) {
        val batch = services.queue.getBatch(batchId)
        batch.filter {
            it.state == QueueState.FAILED ||
                it.state == QueueState.WAITING_FOR_INTERNET ||
                it.state == QueueState.IMPORT_REVIEW ||
                it.state == QueueState.AUTH_REQUIRED
        }
            .forEach { item ->
                val path = item.temporaryFilePath?.let(::File)
                if (path?.isFile == true) {
                    services.queue.update(
                        item.id,
                        QueueState.READY_TO_UPLOAD,
                        progressBytes = 0,
                        attempts = 0,
                        lastError = null,
                        importStarted = false,
                    )
                }
            }
        services.enqueueBatch(batchId)
        _state.value = _state.value.copy(queue = services.queue.getAll(), message = "Retry queued.", error = null)
    }

    fun cancelBatch(batchId: String) {
        services.cancelBatch(batchId)
        services.queue.getBatch(batchId)
            .filter { it.state != QueueState.IMPORTED && it.state != QueueState.CANCELLED }
            .forEach { services.queue.update(it.id, QueueState.CANCELLED, lastError = "Cancelled locally; any already accepted server upload is not rolled back.") }
        _state.value = _state.value.copy(queue = services.queue.getAll(), message = "Stopped local follow-up. Server-side staged files, if any, are unchanged.")
    }

    fun clearImported() {
        val queue = services.queue.getAll()
        val batches = queue.filter { it.state == QueueState.IMPORTED }.map { it.batchId }.toSet()
        queue.filter { it.state == QueueState.IMPORTED }.forEach { services.queue.deleteImported(it.id) }
        batches.forEach { batchId ->
            if (services.queue.getBatch(batchId).isEmpty()) services.queue.deleteImportStatus(batchId)
        }
        val updated = services.queue.getAll()
        _state.value = _state.value.copy(queue = updated, importStatuses = loadImportStatuses(updated))
    }

    fun diagnostics(): String {
        val current = _state.value
        val queueCounts = current.queue.groupingBy { it.state.title }.eachCount().entries
            .sortedBy { it.key }.joinToString("\n") { "${it.key}: ${it.value}" }
        return buildString {
            appendLine("WED Faces Camera Operator ${BuildConfig.VERSION_NAME}")
            appendLine("Server: ${current.serverUrl.ifBlank { "(not configured)" }}")
            appendLine("Signed in: ${current.signedIn}")
            appendLine("Album selected: ${current.selectedAlbumName ?: "(none)"}")
            appendLine("Test Mode: ${current.testMode}")
            appendLine("Camera connected: ${current.cameraConnected}")
            appendLine("Queue:")
            append(queueCounts.ifBlank { "(empty)" })
        }
    }

    private fun isSupported(photo: CameraPhoto): Boolean {
        val extension = photo.fileName.substringAfterLast('.', "").lowercase()
        return extension in SUPPORTED_EXTENSIONS
    }

    private fun safeFileName(value: String): String {
        val cleaned = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(140)
        return cleaned.takeIf { it.contains('.') && it.substringAfterLast('.').lowercase() in SUPPORTED_EXTENSIONS }
            ?: "image-${UUID.randomUUID()}.jpg"
    }

    private fun mimeTypeFor(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> null
    }

    private fun displayName(uri: Uri): String? =
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun querySize(uri: Uri): Long? =
        getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null }

    private fun loadImportStatuses(queue: List<TransferItem>): Map<String, ImportStatus> =
        queue.map { it.batchId }.distinct().mapNotNull { batchId ->
            services.queue.getImportStatus(batchId)?.let { batchId to it }
        }.toMap()

    private companion object {
        val SUPPORTED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        val SUPPORTED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}
