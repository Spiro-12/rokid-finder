package com.example.rokidfinder

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/**
 * Single-record store: the most recent photo of where the phone was set down,
 * plus the timestamp it was captured. Each new placement overwrites the old.
 */
class LastSeenStore(private val context: Context) {

    private val photoFile: File get() = File(context.filesDir, PHOTO_NAME)
    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(bitmap: Bitmap): Boolean {
        return try {
            FileOutputStream(photoFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            prefs.edit().putLong(KEY_TS, System.currentTimeMillis()).apply()
            true
        } catch (t: Throwable) {
            false
        }
    }

    fun timestampMs(): Long = prefs.getLong(KEY_TS, 0L)

    fun photoPath(): String? = if (photoFile.exists()) photoFile.absolutePath else null

    companion object {
        private const val PREFS = "rokidfinder"
        private const val KEY_TS = "phone_last_seen_ts"
        private const val PHOTO_NAME = "phone_last_seen.jpg"
    }
}
