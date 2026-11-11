package com.vintagelab.camera.gallery

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import com.vintagelab.camera.storage.StorageConfig
import com.vintagelab.camera.video.VideoRecorder

data class GalleryItem(
    val id: Long,
    val uri: Uri,
    val dateTaken: Long,
    val isVideo: Boolean = false,
    val mimeType: String = "image/jpeg",
)

/**
 * MediaStore reads and deletes for the in-app gallery (SPEC 6.8). Only rows this install owns in
 * `Pictures/<SAVE_FOLDER>` are visible, so READ_MEDIA_IMAGES is never needed.
 */
class GalleryRepository(
    private val resolver: ContentResolver,
    private val ownerPackage: String,
) {
    private val collection: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val args = GalleryQuery.selectionArgs(StorageConfig.RELATIVE_PATH, VideoRecorder.VIDEO_RELATIVE_PATH, ownerPackage)

    fun uriFor(id: Long, video: Boolean = false): Uri = Uri.withAppendedPath(
        if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        id.toString(),
    )

    /** One page of our photos, newest first; an empty list means the end (or a failed query). */
    fun page(offset: Int, limit: Int = GalleryQuery.PAGE_SIZE): List<GalleryItem> = try {
        val plan = GalleryQuery.plan(Build.VERSION.SDK_INT, offset, limit)
        val cursor = if (plan.useBundle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, GalleryQuery.SELECTION)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, plan.sortOrder)
                putInt(ContentResolver.QUERY_ARG_LIMIT, plan.limit)
                putInt(ContentResolver.QUERY_ARG_OFFSET, plan.offset)
            }
            resolver.query(collection, GalleryQuery.PROJECTION, b, null)
        } else {
            resolver.query(collection, GalleryQuery.PROJECTION, GalleryQuery.SELECTION, args, plan.sortOrder)
        }
        cursor?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val video = c.getInt(2) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    add(
                        GalleryItem(
                            id, uriFor(id, video), c.getLong(1), video,
                            c.getString(3) ?: if (video) "video/mp4" else "image/jpeg",
                        ),
                    )
                }
            }
        } ?: emptyList()
    } catch (e: Exception) {
        Log.w(TAG, "gallery page failed", e)
        emptyList()
    }

    /** Ids of every photo we own (videos never participate in negatives housekeeping). */
    fun allIds(): Set<Long>? = try {
        val imageOnly = GalleryQuery.SELECTION + " AND ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}"
        resolver.query(collection, arrayOf(MediaStore.Files.FileColumns._ID), imageOnly, args, null)?.use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0)) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "id query failed", e)
        null
    }

    /** Newest photo we own, or null. */
    fun newest(): Uri? = page(0, 1).firstOrNull()?.uri

    private companion object {
        const val TAG = "GalleryRepository"
    }
}
