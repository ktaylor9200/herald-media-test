package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Composable

/** Saving and sharing a picture or file the chat shows. */
interface MediaActions {
    /** Saves to the device's shared storage; returns a sentence saying where, or what went wrong. */
    suspend fun save(bytes: ByteArray, name: String): String

    /** Opens the system share sheet for the file. */
    fun share(bytes: ByteArray, name: String)

    /**
     * Opens the file in another app that views its type (a browser, a player, an office app); returns what
     * went wrong, or null when an app took it.
     */
    suspend fun open(bytes: ByteArray, name: String): String?
}

@Composable
expect fun rememberMediaActions(): MediaActions

/** A MIME type from a file name, for saving and sharing. */
internal fun mimeTypeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "heic" -> "image/heic"
    "heif" -> "image/heif"
    "avif" -> "image/avif"
    "ico" -> "image/x-icon"
    "tif", "tiff" -> "image/tiff"
    "svg" -> "image/svg+xml"
    "pdf" -> "application/pdf"
    "txt", "log", "md" -> "text/plain"
    "csv" -> "text/csv"
    "html", "htm" -> "text/html"
    "xhtml" -> "application/xhtml+xml"
    "json" -> "application/json"
    "zip" -> "application/zip"
    "mp3" -> "audio/mpeg"
    "wav" -> "audio/wav"
    "ogg", "oga" -> "audio/ogg"
    "opus" -> "audio/opus"
    "flac" -> "audio/flac"
    "m4a" -> "audio/mp4"
    "aac" -> "audio/aac"
    "amr" -> "audio/amr"
    "mid", "midi" -> "audio/midi"
    "mp4", "m4v" -> "video/mp4"
    "webm" -> "video/webm"
    "mkv" -> "video/x-matroska"
    "3gp" -> "video/3gpp"
    "mov" -> "video/quicktime"
    "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    else -> "application/octet-stream"
}
