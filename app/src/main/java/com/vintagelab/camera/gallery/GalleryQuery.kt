package com.vintagelab.camera.gallery

import android.provider.MediaStore

/**
 * Pure description of the gallery query (SPEC 6.8): our own finished photos in `Pictures/<SAVE_FOLDER>`,
 * newest first, paged. No Android calls, so it is unit tested.
 */
object GalleryQuery {
    const val PAGE_SIZE = 60

    val PROJECTION = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.DATE_TAKEN,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.Files.FileColumns.MIME_TYPE,
    )

    const val SELECTION =
        "(${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?) AND " +
            "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0 AND " +
            "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE},${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"

    /** [relativePath] such as `Pictures/VintageLab` (trailing slash optional); sub-folders and look-alike names do not match. */
    fun selectionArgs(imagePath: String, videoPath: String, ownerPackage: String): Array<String> =
        arrayOf(imagePath.trimEnd('/') + "/%", videoPath.trimEnd('/') + "/%", ownerPackage)

    const val SORT = "${MediaStore.MediaColumns.DATE_TAKEN} DESC, ${MediaStore.MediaColumns._ID} DESC"

    /**
     * How to ask for one page. API 29 accepts LIMIT/OFFSET inside the sort string; API 30+ rejects that and needs
     * the query Bundle ([useBundle]).
     */
    data class Plan(val useBundle: Boolean, val sortOrder: String, val limit: Int, val offset: Int)

    fun plan(sdk: Int, offset: Int, limit: Int = PAGE_SIZE): Plan =
        if (sdk >= 30) Plan(true, SORT, limit, offset)
        else Plan(false, "$SORT LIMIT $limit OFFSET $offset", limit, offset)
}
