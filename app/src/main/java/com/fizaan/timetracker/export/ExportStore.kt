package com.fizaan.timetracker.export

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/**
 * Where a finished export lands.
 *
 * Downloads, so the file is somewhere a file manager or a mail client can pick
 * it up — an export nobody else can open would be a strange thing to have made.
 * On Android 10 and up that goes through MediaStore and needs no permission at
 * all; older devices, where writing there would mean asking for storage access,
 * get the app's own external folder instead.
 */
object ExportStore {

    /** Writes [body] out under [name] and returns a path to show the user. */
    suspend fun save(
        context: Context,
        name: String,
        mime: String,
        body: (OutputStream) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToDownloads(context, name, mime, body)
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            val file = File(dir, name)
            file.outputStream().use(body)
            file.absolutePath
        }
    }

    /** A throwaway copy in the cache, for handing to another part of Android. */
    suspend fun scratch(
        context: Context,
        name: String,
        body: (OutputStream) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, name)
        file.outputStream().use(body)
        file
    }

    private fun saveToDownloads(
        context: Context,
        name: String,
        mime: String,
        body: (OutputStream) -> Unit,
    ): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            // Hidden from other apps until the bytes are all there, so nothing
            // picks up a half-written spreadsheet.
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Downloads folder refused the file")
        try {
            resolver.openOutputStream(uri)?.use(body)
                ?: throw IllegalStateException("Couldn't open the file for writing")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
            null,
            null,
        )
        return "Downloads/$name"
    }
}
