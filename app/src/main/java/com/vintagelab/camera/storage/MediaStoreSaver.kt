package com.vintagelab.camera.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * MediaStore writer (API 29+, no storage permission): insert with IS_PENDING=1, write, clear
 * IS_PENDING; on any failure delete the pending row (SPEC §6.6).
 */
class MediaStoreSaver(
    private val resolver: ContentResolver,
    /** Our package name: cleanup only touches rows we own. */
    private val ownPackage: String,
) {

    private val collection: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /**
     * Streams the photo straight into the pending row: [writeJpeg] gets the row's output stream
     * (no intermediate byte array), then [writeExif] gets the seekable descriptor. Any failure,
     * including OutOfMemoryError, deletes the pending row before rethrowing.
     */
    fun save(time: LocalDateTime, writeJpeg: (OutputStream) -> Unit, writeExif: (FileDescriptor) -> Unit): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, StorageConfig.fileName(time))
            put(MediaStore.Images.Media.MIME_TYPE, StorageConfig.MIME_JPEG)
            put(MediaStore.Images.Media.RELATIVE_PATH, StorageConfig.RELATIVE_PATH)
            put(MediaStore.Images.Media.DATE_TAKEN, time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore insert failed")
        try {
            resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val out = FileOutputStream(pfd.fileDescriptor)
                writeJpeg(out)
                out.flush()
                out.fd.sync()
                writeExif(pfd.fileDescriptor)
            } ?: throw IOException("cannot open $uri")
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            val rows = resolver.update(uri, done, null, null)
            if (rows != 1) throw IOException("clearing IS_PENDING updated $rows rows")
            return uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    /** Newest finished photo we own in our folder (K9), or null. */
    fun newestOwn(): Uri? = try {
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?"
        val args = arrayOf(StorageConfig.RELATIVE_PATH + "%", ownPackage)
        val order = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        resolver.query(collection, arrayOf(MediaStore.Images.Media._ID), selection, args, order)?.use { c ->
            if (c.moveToFirst()) Uri.withAppendedPath(collection, c.getLong(0).toString()) else null
        }
    } catch (e: Exception) {
        Log.w(TAG, "newest photo query failed", e)
        null
    }

    /** Deletes our own rows left pending by a crash during a previous save. */
    fun cleanupPending() {
        try {
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val selection = "${MediaStore.Images.Media.IS_PENDING}=1 AND " +
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?"
            val args = arrayOf(StorageConfig.RELATIVE_PATH + "%", ownPackage)
            val query = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bundle = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                    putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                }
                resolver.query(collection, projection, bundle, null)
            } else {
                @Suppress("DEPRECATION")
                resolver.query(MediaStore.setIncludePending(collection), projection, selection, args, null)
            }
            query?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    resolver.delete(Uri.withAppendedPath(collection, id.toString()), null, null)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "pending cleanup failed", e)
        }
    }

    private companion object {
        const val TAG = "MediaStoreSaver"
    }
}
