package com.vintagelab.camera.tools

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/** Opaque/ARGB pixel image, row-major, `0xAARRGGBB`. */
class ArgbImage(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {
    init {
        require(pixels.size == width * height)
    }

    fun getRGB(x: Int, y: Int): Int = pixels[y * width + x]
}

/**
 * Minimal PNG codec for the LUT tool. Android unit tests compile against android.jar, which has
 * no java.awt / javax.imageio, so this uses java.util.zip only.
 * Decodes 8-bit RGB / RGBA, non-interlaced (what t3mujinpack ships); encodes 8-bit RGB.
 */
object MiniPng {
    private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    fun read(file: File): ArgbImage = decode(file.readBytes())

    fun decode(bytes: ByteArray): ArgbImage {
        val inp = DataInputStream(bytes.inputStream())
        val sig = ByteArray(8).also { inp.readFully(it) }
        require(sig.contentEquals(SIGNATURE)) { "not a PNG" }
        var width = 0
        var height = 0
        var channels = 0
        val idat = ByteArrayOutputStream()
        while (true) {
            val len = inp.readInt()
            val type = String(ByteArray(4).also { inp.readFully(it) }, Charsets.US_ASCII)
            val data = ByteArray(len).also { inp.readFully(it) }
            inp.readInt() // CRC
            when (type) {
                "IHDR" -> {
                    val h = DataInputStream(data.inputStream())
                    width = h.readInt(); height = h.readInt()
                    val depth = h.readUnsignedByte()
                    val colorType = h.readUnsignedByte()
                    h.readUnsignedByte(); h.readUnsignedByte()
                    val interlace = h.readUnsignedByte()
                    require(depth == 8 && interlace == 0) { "only 8-bit non-interlaced PNG" }
                    channels = when (colorType) {
                        2 -> 3
                        6 -> 4
                        else -> error("unsupported colour type $colorType")
                    }
                }
                "IDAT" -> idat.write(data)
                "IEND" -> break
            }
        }
        val raw = InflaterInputStream(idat.toByteArray().inputStream()).readBytes()
        val stride = width * channels
        require(raw.size == height * (stride + 1)) { "bad image data size" }
        val prev = ByteArray(stride)
        val cur = ByteArray(stride)
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val base = y * (stride + 1)
            val filter = raw[base].toInt()
            for (i in 0 until stride) {
                val x = raw[base + 1 + i].toInt() and 0xFF
                val a = if (i >= channels) cur[i - channels].toInt() and 0xFF else 0
                val b = prev[i].toInt() and 0xFF
                val c = if (i >= channels) prev[i - channels].toInt() and 0xFF else 0
                val v = when (filter) {
                    0 -> x
                    1 -> x + a
                    2 -> x + b
                    3 -> x + ((a + b) ushr 1)
                    4 -> x + paeth(a, b, c)
                    else -> error("bad filter $filter")
                }
                cur[i] = v.toByte()
            }
            for (px in 0 until width) {
                val o = px * channels
                val r = cur[o].toInt() and 0xFF
                val g = cur[o + 1].toInt() and 0xFF
                val bl = cur[o + 2].toInt() and 0xFF
                val al = if (channels == 4) cur[o + 3].toInt() and 0xFF else 0xFF
                out[y * width + px] = (al shl 24) or (r shl 16) or (g shl 8) or bl
            }
            cur.copyInto(prev)
        }
        return ArgbImage(width, height, out)
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    fun encodeRgb(img: ArgbImage): ByteArray {
        val raw = ByteArrayOutputStream()
        for (y in 0 until img.height) {
            raw.write(0)
            for (x in 0 until img.width) {
                val p = img.getRGB(x, y)
                raw.write((p shr 16) and 0xFF); raw.write((p shr 8) and 0xFF); raw.write(p and 0xFF)
            }
        }
        val z = ByteArrayOutputStream()
        DeflaterOutputStream(z).use { it.write(raw.toByteArray()) }
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        val d = DataOutputStream(out)
        val ihdr = ByteArrayOutputStream().also {
            val h = DataOutputStream(it)
            h.writeInt(img.width); h.writeInt(img.height)
            h.writeByte(8); h.writeByte(2); h.writeByte(0); h.writeByte(0); h.writeByte(0)
        }.toByteArray()
        chunk(d, "IHDR", ihdr)
        chunk(d, "IDAT", z.toByteArray())
        chunk(d, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    fun writeRgb(img: ArgbImage, file: File) = file.writeBytes(encodeRgb(img))

    private fun chunk(d: DataOutputStream, type: String, data: ByteArray) {
        val t = type.toByteArray(Charsets.US_ASCII)
        d.writeInt(data.size)
        d.write(t)
        d.write(data)
        val crc = CRC32()
        crc.update(t)
        crc.update(data)
        d.writeInt(crc.value.toInt())
    }
}
