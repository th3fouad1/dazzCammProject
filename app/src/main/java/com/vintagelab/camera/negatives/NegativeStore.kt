package com.vintagelab.camera.negatives

import android.util.Log
import java.io.File

/** A negative read back from disk. */
class Negative(val id: Long, val settings: NegativeSettings, val source: ByteArray, val blend: ByteArray?)

/**
 * App-private "negatives" (SPEC 6.9): `filesDir/negatives/<mediaId>/{source.jpg, settings.json[, blend.jpg]}`.
 * `settings.json` is written last and is the commit marker; folders without it are junk and get removed.
 * Never throws to its callers for IO problems: a missing negative only disables "Re-develop" for that photo.
 */
class NegativeStore(
    private val root: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    private fun dir(id: Long) = File(root, id.toString())

    fun has(id: Long): Boolean = File(dir(id), SETTINGS).isFile

    /** Writes a negative, then enforces the cap. Returns false when nothing could be stored. */
    fun write(id: Long, settings: NegativeSettings, source: ByteArray, blend: ByteArray?): Boolean = synchronized(lock) {
        val target = dir(id)
        try {
            root.mkdirs()
            target.deleteRecursively()
            if (!target.mkdirs()) return false
            File(target, SOURCE).writeBytes(source)
            if (blend != null) File(target, BLEND).writeBytes(blend)
            val tmp = File(target, "$SETTINGS.tmp")
            tmp.writeText(NegativeCodec.encode(settings.copy(createdAtMs = nowMs(), hasBlend = blend != null)))
            if (!tmp.renameTo(File(target, SETTINGS))) throw java.io.IOException("rename failed")
            enforceCap()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "negative $id not stored", t)
            runCatching { target.deleteRecursively() }
            false
        }
    }

    /** Loads a negative and marks it as recently used. Null when missing, unreadable or from a newer schema. */
    fun read(id: Long): Negative? = synchronized(lock) {
        try {
            val d = dir(id)
            val settingsFile = File(d, SETTINGS)
            val settings = NegativeCodec.decode(settingsFile.readText()) ?: return null
            val source = File(d, SOURCE).takeIf { it.isFile }?.readBytes() ?: return null
            val blend = if (settings.hasBlend) File(d, BLEND).takeIf { it.isFile }?.readBytes() ?: return null else null
            settingsFile.setLastModified(nowMs())
            Negative(id, settings, source, blend)
        } catch (t: Throwable) {
            Log.w(TAG, "negative $id unreadable", t)
            null
        }
    }

    fun delete(id: Long) {
        synchronized(lock) { runCatching { dir(id).deleteRecursively() } }
    }

    /** Startup housekeeping: drops negatives whose photo is gone (not in [liveIds]), junk folders, then applies the cap. */
    fun prune(liveIds: Set<Long>) {
        synchronized(lock) {
            val dirs = root.listFiles()?.filter { it.isDirectory } ?: return
            for (d in dirs) {
                val id = d.name.toLongOrNull()
                if (id == null || id !in liveIds || !File(d, SETTINGS).isFile) runCatching { d.deleteRecursively() }
            }
            enforceCap()
        }
    }

    fun entries(): List<NegativeEntry> {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { d ->
            val id = d.name.toLongOrNull() ?: return@mapNotNull null
            val s = File(d, SETTINGS).takeIf { it.isFile } ?: return@mapNotNull null
            NegativeEntry(id, d.listFiles()?.sumOf { it.length() } ?: 0L, s.lastModified())
        }
    }

    private fun enforceCap() {
        for (id in NegativeLru.evictions(entries())) runCatching { dir(id).deleteRecursively() }
    }

    private companion object {
        const val TAG = "NegativeStore"
        const val SOURCE = "source.jpg"
        const val BLEND = "blend.jpg"
        const val SETTINGS = "settings.json"
    }
}
