package com.wedfaces.cameraoperator.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.wedfaces.cameraoperator.model.ImportStatus
import com.wedfaces.cameraoperator.model.PhotoSource
import com.wedfaces.cameraoperator.model.QueueState
import com.wedfaces.cameraoperator.model.TransferItem
import java.util.UUID
import org.json.JSONArray

class TransferQueueRepository(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE transfers (
              id TEXT PRIMARY KEY NOT NULL,
              upload_id TEXT NOT NULL UNIQUE,
              batch_id TEXT NOT NULL,
              album_id TEXT NOT NULL,
              album_name TEXT NOT NULL,
              source TEXT NOT NULL,
              source_object_id TEXT NOT NULL,
              file_name TEXT NOT NULL,
              mime_type TEXT NOT NULL,
              size_bytes INTEGER,
              temporary_file_path TEXT,
              state TEXT NOT NULL,
              progress_bytes INTEGER NOT NULL,
              attempts INTEGER NOT NULL,
              created_at INTEGER NOT NULL,
              updated_at INTEGER NOT NULL,
              last_error TEXT,
              import_started INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX transfers_batch_idx ON transfers(batch_id)")
        db.execSQL("CREATE INDEX transfers_updated_idx ON transfers(updated_at DESC)")
        createImportStatusTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createImportStatusTable(db)
        }
    }

    @Synchronized
    fun saveImportStatus(batchId: String, status: ImportStatus) {
        val values = ContentValues().apply {
            put("batch_id", batchId)
            put("state", status.state)
            putNullable("total", status.total)
            putNullable("imported", status.imported)
            putNullable("duplicates", status.duplicates)
            putNullable("failed", status.failed)
            put("failures_json", JSONArray(status.failures).toString())
            put("error", status.error)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            STATUS_TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    @Synchronized
    fun getImportStatus(batchId: String): ImportStatus? =
        readableDatabase.query(
            STATUS_TABLE,
            null,
            "batch_id = ?",
            arrayOf(batchId),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val rawFailures = cursor.getString(cursor.getColumnIndexOrThrow("failures_json"))
            val failures = try {
                val json = JSONArray(rawFailures)
                (0 until json.length()).mapNotNull { json.optString(it).takeIf(String::isNotBlank) }
            } catch (_: Exception) {
                emptyList()
            }
            ImportStatus(
                state = cursor.getString(cursor.getColumnIndexOrThrow("state")),
                total = cursor.nullableInt("total"),
                imported = cursor.nullableInt("imported"),
                duplicates = cursor.nullableInt("duplicates"),
                failed = cursor.nullableInt("failed"),
                failures = failures,
                error = cursor.getString(cursor.getColumnIndexOrThrow("error")),
            )
        }

    @Synchronized
    fun deleteImportStatus(batchId: String) {
        writableDatabase.delete(STATUS_TABLE, "batch_id = ?", arrayOf(batchId))
    }

    @Synchronized
    fun insert(item: TransferItem) {
        writableDatabase.insertOrThrow(TABLE, null, item.toContentValues())
    }

    @Synchronized
    fun getAll(): List<TransferItem> =
        readableDatabase.query(TABLE, null, null, null, null, null, "created_at DESC")
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toTransferItem()) } }

    @Synchronized
    fun getBatch(batchId: String): List<TransferItem> =
        readableDatabase.query(
            TABLE,
            null,
            "batch_id = ?",
            arrayOf(batchId),
            null,
            null,
            "created_at ASC",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toTransferItem()) } }

    @Synchronized
    fun update(
        id: String,
        state: QueueState,
        progressBytes: Long? = null,
        attempts: Int? = null,
        temporaryFilePath: String? = null,
        lastError: String? = null,
        importStarted: Boolean? = null,
    ) {
        val values = ContentValues().apply {
            put("state", state.name)
            put("updated_at", System.currentTimeMillis())
            if (progressBytes != null) put("progress_bytes", progressBytes)
            if (attempts != null) put("attempts", attempts)
            if (temporaryFilePath != null) put("temporary_file_path", temporaryFilePath)
            put("last_error", lastError)
            if (importStarted != null) put("import_started", if (importStarted) 1 else 0)
        }
        writableDatabase.update(TABLE, values, "id = ?", arrayOf(id))
    }

    @Synchronized
    fun updateBatch(batchId: String, state: QueueState, error: String? = null, importStarted: Boolean? = null) {
        val values = ContentValues().apply {
            put("state", state.name)
            put("updated_at", System.currentTimeMillis())
            put("last_error", error)
            if (importStarted != null) put("import_started", if (importStarted) 1 else 0)
        }
        writableDatabase.update(TABLE, values, "batch_id = ?", arrayOf(batchId))
    }

    @Synchronized
    fun incrementAttempt(id: String) {
        writableDatabase.execSQL(
            "UPDATE transfers SET attempts = attempts + 1, updated_at = ? WHERE id = ?",
            arrayOf(System.currentTimeMillis(), id),
        )
    }

    @Synchronized
    fun deleteImported(id: String) {
        writableDatabase.delete(TABLE, "id = ? AND state = ?", arrayOf(id, QueueState.IMPORTED.name))
    }

    private fun TransferItem.toContentValues() = ContentValues().apply {
        put("id", id)
        put("upload_id", uploadId)
        put("batch_id", batchId)
        put("album_id", albumId)
        put("album_name", albumName)
        put("source", source.name)
        put("source_object_id", sourceObjectId)
        put("file_name", fileName)
        put("mime_type", mimeType)
        if (sizeBytes == null) putNull("size_bytes") else put("size_bytes", sizeBytes)
        put("temporary_file_path", temporaryFilePath)
        put("state", state.name)
        put("progress_bytes", progressBytes)
        put("attempts", attempts)
        put("created_at", createdAtMillis)
        put("updated_at", updatedAtMillis)
        put("last_error", lastError)
        put("import_started", if (importStarted) 1 else 0)
    }

    private fun ContentValues.putNullable(key: String, value: Int?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun createImportStatusTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS import_status (
              batch_id TEXT PRIMARY KEY NOT NULL,
              state TEXT NOT NULL,
              total INTEGER,
              imported INTEGER,
              duplicates INTEGER,
              failed INTEGER,
              failures_json TEXT NOT NULL,
              error TEXT,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun android.database.Cursor.toTransferItem(): TransferItem {
        fun string(column: String) = getString(getColumnIndexOrThrow(column))
        fun nullableString(column: String): String? {
            val index = getColumnIndexOrThrow(column)
            return if (isNull(index)) null else getString(index)
        }
        fun nullableLong(column: String): Long? {
            val index = getColumnIndexOrThrow(column)
            return if (isNull(index)) null else getLong(index)
        }
        return TransferItem(
            id = string("id"),
            uploadId = string("upload_id"),
            batchId = string("batch_id"),
            albumId = string("album_id"),
            albumName = string("album_name"),
            source = PhotoSource.valueOf(string("source")),
            sourceObjectId = string("source_object_id"),
            fileName = string("file_name"),
            mimeType = string("mime_type"),
            sizeBytes = nullableLong("size_bytes"),
            temporaryFilePath = nullableString("temporary_file_path"),
            state = QueueState.valueOf(string("state")),
            progressBytes = getLong(getColumnIndexOrThrow("progress_bytes")),
            attempts = getInt(getColumnIndexOrThrow("attempts")),
            createdAtMillis = getLong(getColumnIndexOrThrow("created_at")),
            updatedAtMillis = getLong(getColumnIndexOrThrow("updated_at")),
            lastError = nullableString("last_error"),
            importStarted = getInt(getColumnIndexOrThrow("import_started")) == 1,
        )
    }

    private fun android.database.Cursor.nullableInt(column: String): Int? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getInt(index)
    }

    companion object {
        private const val DATABASE_NAME = "camera_operator_queue.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE = "transfers"
        private const val STATUS_TABLE = "import_status"

        fun newTransfer(
            batchId: String,
            albumId: String,
            albumName: String,
            source: PhotoSource,
            sourceObjectId: String,
            fileName: String,
            mimeType: String,
            sizeBytes: Long?,
            initialState: QueueState,
        ) = TransferItem(
            id = UUID.randomUUID().toString(),
            uploadId = UUID.randomUUID().toString(),
            batchId = batchId,
            albumId = albumId,
            albumName = albumName,
            source = source,
            sourceObjectId = sourceObjectId,
            fileName = fileName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            temporaryFilePath = null,
            state = initialState,
            progressBytes = 0,
            attempts = 0,
            createdAtMillis = System.currentTimeMillis(),
            updatedAtMillis = System.currentTimeMillis(),
            lastError = null,
            importStarted = false,
        )
    }
}
