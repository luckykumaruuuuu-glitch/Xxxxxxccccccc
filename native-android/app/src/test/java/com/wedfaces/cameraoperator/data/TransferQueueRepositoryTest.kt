package com.wedfaces.cameraoperator.data

import android.app.Application
import com.wedfaces.cameraoperator.model.ImportStatus
import com.wedfaces.cameraoperator.model.PhotoSource
import com.wedfaces.cameraoperator.model.QueueState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TransferQueueRepositoryTest {
    @Test
    fun serverCountersAndFailedFileNamesSurviveDatabaseReopen() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(DATABASE_NAME)
        var repository = TransferQueueRepository(context)
        val batchId = "batch-persisted-report"
        val item = TransferQueueRepository.newTransfer(
            batchId = batchId,
            albumId = "server-album-id",
            albumName = "Wedding",
            source = PhotoSource.TEST_GALLERY,
            sourceObjectId = "content://image/one",
            fileName = "photo-one.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1024L,
            initialState = QueueState.UPLOADED_WAITING_IMPORT,
        )
        val expectedStatus = ImportStatus(
            state = "COMPLETED",
            total = 5,
            imported = 1,
            duplicates = 0,
            failed = 4,
            failures = listOf(
                "photo-two.jpg — invalid image",
                "photo-three.jpg — corrupted data",
                "photo-four.jpg — unsupported encoding",
                "photo-five.jpg — import timeout",
            ),
            error = null,
        )

        try {
            repository.insert(item)
            repository.saveImportStatus(batchId, expectedStatus)
            repository.close()

            repository = TransferQueueRepository(context)

            assertEquals(expectedStatus, repository.getImportStatus(batchId))
            assertEquals(item.id, repository.getBatch(batchId).single().id)
            assertEquals(item.fileName, repository.getBatch(batchId).single().fileName)
        } finally {
            repository.close()
            context.deleteDatabase(DATABASE_NAME)
        }
    }

    private companion object {
        const val DATABASE_NAME = "camera_operator_queue.db"
    }
}
