package com.vintagelab.camera.editor

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.vintagelab.camera.capture.ExifWriter
import com.vintagelab.camera.storage.StorageConfig
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.time.LocalDateTime
import kotlin.math.max
import kotlin.math.roundToInt

/** A picked photo, decoded upright and capped (SPEC 6.7). [jpeg] has no metadata at all, so no GPS survives. */
class ImportedImage(
    val jpeg: ByteArray,
    val width: Int,
    val height: Int,
    /** Copied camera tags of the original (never GPS). */
    val exif: Map<String, String>,
    /** EXIF DateTimeOriginal of the original, if present. */
    val taken: LocalDateTime?,
)

/** Import of a Photo Picker URI. The original is only ever read. */
object ImageImporter {
    /**
     * Decodes [uri] (JPEG, HEIC, PNG, WebP...) with `ImageDecoder`, which applies the EXIF orientation itself, to sRGB,
     * scales it so the long side is at most [cap], and re-encodes it as JPEG q95.
     */
    @Throws(IOException::class)
    fun import(resolver: ContentResolver, uri: Uri, cap: Int): ImportedImage {
        val exif = (resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())).use { ExifWriter.readSource(it) }
        val decoded = try {
            decodeWithImageDecoder(resolver, uri, cap)
        } catch (e: IOException) {
            decodeWithBitmapFactory(resolver, uri, cap)
        } catch (e: ImageDecoder.DecodeException) {
            decodeWithBitmapFactory(resolver, uri, cap)
        }
        val bitmap = fit(argb(decoded), cap)
        try {
            val bos = ByteArrayOutputStream(max(64 * 1024, bitmap.byteCount / 10))
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, StorageConfig.NEGATIVE_JPEG_QUALITY, bos)) throw IOException("encode failed")
            return ImportedImage(bos.toByteArray(), bitmap.width, bitmap.height, exif, ExifWriter.dateTimeOriginal(exif))
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeWithImageDecoder(resolver: ContentResolver, uri: Uri, cap: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            // The long side does not change with the orientation, so the sample size is orientation-agnostic.
            val sample = max(1, max(info.size.width, info.size.height) / cap)
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }

    /** Fallback when ImageDecoder cannot read the file: BitmapFactory plus our own EXIF orientation handling. */
    private fun decodeWithBitmapFactory(resolver: ContentResolver, uri: Uri, cap: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("not an image")
        val opts = BitmapFactory.Options().apply {
            inSampleSize = max(1, max(bounds.outWidth, bounds.outHeight) / cap)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        val raw = (resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("decode failed")
        val orientation = (resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())).use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
        return orient(raw, orientation)
    }

    /** Applies EXIF [orientation] (1..8) to [src], recycling it when a new bitmap is made. */
    fun orient(src: Bitmap, orientation: Int): Bitmap {
        val spec = ExifOrientation.spec(orientation)
        if (spec.rotationDegrees == 0 && !spec.mirror) return src
        val m = Matrix()
        if (spec.mirror) m.postScale(-1f, 1f)
        m.postRotate(spec.rotationDegrees.toFloat())
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    private fun argb(b: Bitmap): Bitmap {
        if (b.config == Bitmap.Config.ARGB_8888) return b
        val c = b.copy(Bitmap.Config.ARGB_8888, false)
        if (c !== b) b.recycle()
        return c ?: throw IOException("copy failed")
    }

    private fun fit(b: Bitmap, cap: Int): Bitmap {
        val long = max(b.width, b.height)
        if (long <= cap) return b
        val k = cap.toFloat() / long
        val out = Bitmap.createScaledBitmap(b, max(1, (b.width * k).roundToInt()), max(1, (b.height * k).roundToInt()), true)
        if (out !== b) b.recycle()
        return out
    }
}
