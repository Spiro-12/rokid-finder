package com.example.rokidfinder

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * SQLite-backed memory store. One row per tracked item; each new placement
 * overwrites the previous row for that item.
 *
 * Schema mirrors the second-brain plan: a single record per item that already
 * has the [gist] column we'll populate in Phase 3 (place classifier) and the
 * nullable [snapshot] path that will be cleared for everyday items at that
 * stage. For Phase 1 we always write a snapshot.
 */
class MemoryStore(private val context: Context) {

    private val helper = object : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE(
                  item       TEXT PRIMARY KEY,
                  kind       TEXT NOT NULL,
                  gist       TEXT,
                  snapshot   TEXT,
                  ts         INTEGER NOT NULL,
                  confidence REAL NOT NULL
                )
                """.trimIndent()
            )
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    data class Memory(
        val item: String,
        val kind: String,
        val gist: String?,
        val snapshotPath: String?,
        val tsMs: Long,
        val confidence: Float,
    )

    /**
     * Persist a memory. The snapshot is written as `<item>.jpg` in app filesDir
     * (overwriting any prior). Returns the resulting [Memory] or null on failure.
     */
    fun save(itemKey: String, snapshot: Bitmap?, confidence: Float, gist: String? = null): Memory? {
        val spec = TrackedItems.byKey(itemKey) ?: return null
        val now = System.currentTimeMillis()
        val snapPath: String? = snapshot?.let {
            try {
                val f = File(context.filesDir, "$itemKey.jpg")
                FileOutputStream(f).use { out ->
                    it.compress(Bitmap.CompressFormat.JPEG, 88, out)
                }
                f.absolutePath
            } catch (t: Throwable) {
                Log.e(TAG, "save snapshot for $itemKey failed", t)
                null
            }
        }
        val cv = ContentValues().apply {
            put("item", itemKey)
            put("kind", spec.kind)
            put("gist", gist)
            put("snapshot", snapPath)
            put("ts", now)
            put("confidence", confidence)
        }
        return try {
            helper.writableDatabase.insertWithOnConflict(
                TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE
            )
            Memory(itemKey, spec.kind, gist, snapPath, now, confidence)
        } catch (t: Throwable) {
            Log.e(TAG, "save row for $itemKey failed", t)
            null
        }
    }

    fun get(itemKey: String): Memory? {
        helper.readableDatabase.rawQuery(
            "SELECT item,kind,gist,snapshot,ts,confidence FROM $TABLE WHERE item = ?",
            arrayOf(itemKey)
        ).use { c ->
            return if (c.moveToFirst()) c.toMemory() else null
        }
    }

    /** All memories, newest first. */
    fun all(): List<Memory> {
        val out = mutableListOf<Memory>()
        helper.readableDatabase.rawQuery(
            "SELECT item,kind,gist,snapshot,ts,confidence FROM $TABLE ORDER BY ts DESC",
            null
        ).use { c ->
            while (c.moveToNext()) out += c.toMemory()
        }
        return out
    }

    private fun android.database.Cursor.toMemory() = Memory(
        item = getString(0),
        kind = getString(1),
        gist = if (isNull(2)) null else getString(2),
        snapshotPath = if (isNull(3)) null else getString(3),
        tsMs = getLong(4),
        confidence = getFloat(5),
    )

    fun delete(itemKey: String): Boolean {
        // Also remove the JPEG so a forgotten memory is gone end-to-end.
        File(context.filesDir, "$itemKey.jpg").takeIf { it.exists() }?.delete()
        return try {
            helper.writableDatabase.delete(TABLE, "item = ?", arrayOf(itemKey)) > 0
        } catch (t: Throwable) {
            Log.e(TAG, "delete $itemKey failed", t)
            false
        }
    }

    companion object {
        private const val TAG = "MemoryStore"
        private const val DB_NAME = "rokidfinder.db"
        private const val DB_VERSION = 1
        private const val TABLE = "memories"
    }
}
