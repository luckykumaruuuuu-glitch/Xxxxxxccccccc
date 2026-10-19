package com.wedfaces.cameraoperator.camera

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.fimagena.libptp.PtpConnection
import com.fimagena.libptp.PtpDataType
import com.fimagena.libptp.PtpSession
import com.fimagena.libptp.ptpip.PtpIpConnection
import com.wedfaces.cameraoperator.model.CameraPhoto
import com.wedfaces.cameraoperator.model.PhotoSource
import java.io.File
import java.io.FileOutputStream
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sony Wi-Fi PTP/IP adapter. Browsing performs only GetStorageIDs,
 * GetObjectHandles, and GetObjectInfo. GetObject is invoked only by the
 * explicit selected-original transfer method.
 */
class SonyPtpIpCameraAdapter(context: Context) : CameraGateway {
    private val connectivityManager = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)
    private val operationMutex = Mutex()
    private val downloadsWaiting = AtomicInteger(0)
    private var connection: PtpConnection? = null
    private var session: PtpSession? = null

    override suspend fun connect(network: Network, endpoint: String?): String =
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                closeCurrentConnection()
                val address = endpoint?.trim()?.takeIf(String::isNotBlank)
                    ?: defaultGatewayAddress(network)
                    ?: throw IllegalStateException(
                        "Android could not identify the camera Wi-Fi gateway. Connect to the camera Wi-Fi or enter its IP address.",
                    )

                try {
                    val transport = PtpIpConnection(null, network.socketFactory)
                    val ptp = PtpConnection(transport)
                    val host = PtpIpConnection.PtpIpHostId(
                        SONY_PTP_GUID,
                        "WED Faces Camera Operator",
                    )
                    ptp.connect(PtpIpConnection.PtpIpAddress(InetAddress.getByName(address), PTP_IP_PORT), host)
                    val openedSession = ptp.openSession()
                    connection = ptp
                    session = openedSession
                    ptp.deviceInfo?.mModel?.mString?.takeIf(String::isNotBlank) ?: "Sony camera"
                } catch (error: Exception) {
                    closeCurrentConnection()
                    throw IllegalStateException(
                        "Could not open a Sony PTP/IP session at $address:$PTP_IP_PORT. Confirm the camera is in Send to Smartphone mode and awake.",
                        error,
                    )
                }
            }
        }

    override suspend fun listObjects(): List<CameraPhoto> =
        operationMutex.withLock {
            withContext(Dispatchers.IO) {
                val activeSession = session
                    ?: throw IllegalStateException("No camera session is open.")
                val photos = mutableListOf<CameraPhoto>()
                val storageIds = activeSession.storageIDs
                for (storageId in storageIds) {
                    currentCoroutineContext().ensureActive()
                    val handles = activeSession.getObjectHandles(storageId)
                    for (handle in handles) {
                        currentCoroutineContext().ensureActive()
                        try {
                            val info = activeSession.getObjectInfo(handle)
                            val format = info.mObjectFormatCode.mValue
                            if (format == FMT_ASSOCIATION) continue
                            val name = info.mFilename.mString?.takeIf(String::isNotBlank)
                                ?: "CAM_${handle.mValue}"
                            photos += CameraPhoto(
                                objectId = handle.mValue.toString(),
                                fileName = name,
                                sizeBytes = info.mObjectCompressedSize.mValue.takeIf { it > 0 },
                                capturedAtMillis = info.mCaptureDate.mDate?.time,
                                formatCode = format,
                                mimeType = mimeTypeFor(name),
                                source = PhotoSource.CAMERA,
                            )
                        } catch (error: Exception) {
                            // A stale or unreadable object must not prevent other handles from
                            // appearing. Its original is never fetched as a fallback.
                            if (error is kotlinx.coroutines.CancellationException) throw error
                        }
                    }
                }
                photos.sortedByDescending { it.capturedAtMillis ?: 0L }
            }
        }

    override suspend fun getThumbnail(objectId: String): ByteArray? =
        if (downloadsWaiting.get() > 0) {
            null
        } else operationMutex.withLock {
            withContext(Dispatchers.IO) {
                if (downloadsWaiting.get() > 0) return@withContext null
                val activeSession = session ?: return@withContext null
                val handle = objectId.toLongOrNull() ?: return@withContext null
                try {
                    activeSession.getThumb(PtpDataType.ObjectHandle(handle))
                        ?.takeIf { it.isNotEmpty() && it.size <= MAX_THUMBNAIL_BYTES }
                } catch (_: Exception) {
                    null
                }
            }
        }

    override suspend fun downloadSelectedOriginal(
        objectId: String,
        destination: File,
        onProgress: (sent: Long, total: Long) -> Unit,
    ) {
        downloadsWaiting.incrementAndGet()
        try {
            operationMutex.withLock {
                withContext(Dispatchers.IO) {
                    val activeSession = session ?: throw IllegalStateException("Camera disconnected before the original was retrieved.")
                    val handle = objectId.toLongOrNull() ?: throw IllegalArgumentException("Camera object handle is invalid.")
                    destination.parentFile?.mkdirs()
                    try {
                        FileOutputStream(destination).use { output ->
                            activeSession.getObjectToStream(
                                PtpDataType.ObjectHandle(handle),
                                output,
                                object : PtpSession.DataLoadListener {
                                    override fun onDataLoaded(loaded: Long, expected: Long) {
                                        onProgress(loaded, expected)
                                    }
                                },
                            )
                        }
                        if (!destination.isFile || destination.length() == 0L) {
                            throw IllegalStateException("The camera returned an empty original file.")
                        }
                    } catch (error: Exception) {
                        destination.delete()
                        throw error
                    }
                }
            }
        } finally {
            downloadsWaiting.decrementAndGet()
        }
    }

    override suspend fun disconnect() {
        operationMutex.withLock {
            withContext(Dispatchers.IO) { closeCurrentConnection() }
        }
    }

    private fun closeCurrentConnection() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        try {
            connection?.close()
        } catch (_: Exception) {
        }
        session = null
        connection = null
    }

    private fun defaultGatewayAddress(network: Network): String? =
        connectivityManager.getLinkProperties(network)?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway != null }
            ?.gateway
            ?.hostAddress

    private fun mimeTypeFor(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "tif", "tiff" -> "image/tiff"
        "arw", "cr2", "cr3", "nef", "dng" -> "image/x-raw"
        "mp4", "mov", "mts", "m2ts", "avi" -> "video/*"
        else -> null
    }

    private companion object {
        const val PTP_IP_PORT = 15740
        const val FMT_ASSOCIATION = 0x3001
        const val MAX_THUMBNAIL_BYTES = 512 * 1024
        val SONY_PTP_GUID = shortArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0,
            0xff.toShort(), 0xff.toShort(), 0, 0, 0, 0, 0, 0,
        )
    }
}
