package fm.rizx.player.data.download

import java.security.MessageDigest

/** Extensions we understand, and the bare mime each maps to for MediaStore export. */
private val MIME_BY_EXTENSION = mapOf(
    "m4a" to "audio/mp4",
    "mp4" to "audio/mp4",
    "webm" to "audio/webm",
    "mp3" to "audio/mpeg",
    "ogg" to "audio/ogg",
    "opus" to "audio/ogg",
    "flac" to "audio/flac",
    "wav" to "audio/wav",
    "aac" to "audio/aac",
)

/** An identity key that is already safe to use as a filename once its `:` becomes `_`. */
private val PLAIN_KEY = Regex("[A-Za-z0-9_:.-]{1,60}")

/**
 * The on-disk name for a download, derived from its `ProviderRef.identityKey` (`"youtube:dQw4w9WgXcQ"`
 * → `"youtube_dQw4w9WgXcQ.m4a"`).
 *
 * Identity-derived, not title-derived: deterministic, collision-free, re-downloading overwrites in
 * place, and it honours the project rule that content is never identified by title/artist. This folder
 * is not user-facing — Export writes the human-readable name.
 *
 * A JS plugin provider (ADR 0014) can mint an id with slashes, spaces, unicode, or 300 characters, and
 * `getExternalFilesDir` can land on exFAT where several of those are illegal. So anything that isn't
 * plainly safe collapses to a truncated SHA-256, which is bounded and legal on every filesystem.
 */
fun downloadFileName(identityKey: String, extension: String): String {
    val stem = if (PLAIN_KEY.matches(identityKey)) identityKey.replace(':', '_') else hash(identityKey)
    return "$stem.$extension"
}

private fun hash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .take(16)
        .joinToString("") { "%02x".format(it) }

/**
 * The file extension to save these bytes under, most-trusted source first:
 * the resolved stream's own container, then its mime, then the response `Content-Type`
 * (**authoritative for Audius**, whose URL 302s to a CDN that names the real format), then `bin`.
 *
 * Getting this wrong never breaks local playback — ExoPlayer's `DefaultExtractorsFactory` sniffs the
 * container from the bytes. It matters for Export, where a foreign player trusts the extension.
 */
fun extensionFor(container: String?, mimeType: String?, contentType: String? = null): String =
    container?.lowercase()?.takeIf { it in MIME_BY_EXTENSION }
        ?: extensionForMime(mimeType)
        ?: extensionForMime(contentType)
        ?: "bin"

private fun extensionForMime(mime: String?): String? {
    val bare = bareMime(mime) ?: return null
    return MIME_BY_EXTENSION.entries.firstOrNull { it.value == bare }?.key
}

/**
 * Strips codec parameters off a mime type: NewPipe hands back `audio/mp4; codecs="mp4a.40.2"`, and
 * MediaStore rejects or misfiles anything that isn't a bare audio type.
 */
fun bareMime(mime: String?): String? =
    mime?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

/** The bare mime to declare for a file saved with [extension], for MediaStore export. */
fun mimeForExtension(extension: String): String? = MIME_BY_EXTENSION[extension.lowercase()]

/** Human-readable size for the Downloads tab, e.g. `4.2 MB`. */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%d KB".format(bytes / 1_000)
    else -> "$bytes B"
}

/**
 * The name an exported file gets in `Music/Rizx` — `"Artist - Title.m4a"`.
 *
 * This is the only metadata that reliably survives: YouTube's adaptive audio M4A carries no `ilst`
 * tags, and MediaStore's TITLE/ARTIST columns are database rows that MediaScanner overwrites from the
 * (absent) embedded tags on rescan, falling back to the display name. So the filename *is* the tags.
 */
fun exportFileName(artist: String?, title: String, extension: String, fallback: String): String {
    // Sanitise each part *before* joining: a title of "??" would otherwise sanitise to "" only after the
    // separator was added, leaving a stem of "-" that isn't blank and so never reaches the fallback.
    val stem = listOfNotNull(artist, title)
        .map(::sanitisePart)
        .filter { it.isNotEmpty() }
        .joinToString(" - ")
        .take(100)
        .trim()
    return "${stem.ifEmpty { fallback }}.$extension"
}

private fun sanitisePart(part: String): String = part
    .replace(Regex("""[/\\:*?"<>|\x00-\x1F]"""), "")
    .replace(Regex("\\s+"), " ")
    .trim()
