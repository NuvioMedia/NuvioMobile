package com.nuvio.app.features.livetv.data

/**
 * The files Live TV keeps on the device: downloaded playlists and guides (gzip-compressed on
 * disk, like the TV app's), and small per-device state (the Drive sync base copy). Each file is
 * named by an id; the platform decides where they live.
 */
internal expect object LiveTvFiles {
    /** Whether anything is stored under [name] in [dir]. */
    fun exists(dir: String, name: String): Boolean

    /** When the file was last written (epoch ms), or 0 when it doesn't exist. */
    fun lastModified(dir: String, name: String): Long

    /** The file's size on disk, or 0. */
    fun size(dir: String, name: String): Long

    /** Deletes the file (no error when it isn't there). */
    fun delete(dir: String, name: String)

    /** Names of the files in [dir]. */
    fun list(dir: String): List<String>

    /**
     * Downloads [url] into [dir]/[name] (compressed on disk), returning true when the content
     * differs from what was stored before (or there was nothing before). Throws on failure, with
     * a plain-English message; the previous file is kept then.
     */
    suspend fun download(
        url: String,
        dir: String,
        name: String,
        userAgent: String,
        onProgress: ((bytes: Long) -> Unit)? = null,
    ): Boolean

    /**
     * Writes [text] to [dir]/[name] (compressed), returning true when it differs from what was
     * stored before.
     */
    fun writeText(dir: String, name: String, text: String): Boolean

    /**
     * Reads the stored file as text, in pieces of a few hundred KB (large guides are 50–200 MB,
     * so they're never held whole). Null when the file doesn't exist.
     */
    fun <T> readChunks(dir: String, name: String, block: (Sequence<String>) -> T): T?

    /** Small plain-text files (sync state); null when missing. */
    fun readSmall(dir: String, name: String): String?
    fun writeSmall(dir: String, name: String, text: String)

    /** A name for this device shown in backups ("Pixel 8"). */
    val deviceName: String
}

internal object LiveTvDirs {
    const val PLAYLISTS = "playlists"
    const val EPG = "epg"
    const val STATE = "state"
    /** The Sports hub's saved feeds, tables and channel matches. */
    const val SPORTS = "sports"
    /** Change logo's lists of library logos (kept a week). */
    const val LOGOS = "logos"
}
