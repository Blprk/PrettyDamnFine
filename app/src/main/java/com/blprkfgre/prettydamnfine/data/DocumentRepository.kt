package com.blprkfgre.prettydamnfine.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class PdfDocumentItem(
    val uri: Uri,
    val title: String,
    val lastAccessed: Long,
    /** Original provider URI (e.g. WhatsApp / Drive) this document was imported from. */
    val sourceUri: String? = null
)

/** A file in the app's private PDF directory, with its library status. */
data class PrivateCopy(
    val file: File,
    val sizeBytes: Long,
    val lastModified: Long,
    val title: String?,
    val inLibrary: Boolean
)

class DocumentRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("pdf_library", Context.MODE_PRIVATE)

    /**
     * Returns only entries whose file is still openable. Entries whose file is
     * missing are deliberately kept in storage so the Storage screen can offer
     * to re-link them instead of silently losing the history.
     */
    fun getDocuments(): List<PdfDocumentItem> {
        val (list, titlesRepaired) = readStoredDocuments()
        if (titlesRepaired) saveDocuments(list)
        return list.filter { isUriAccessible(it.uri) }.sortedByDescending { it.lastAccessed }
    }

    /** Every stored entry, including ones whose file is currently missing. */
    fun getDocumentsIncludingMissing(): List<PdfDocumentItem> =
        readStoredDocuments().first.sortedByDescending { it.lastAccessed }

    private fun readStoredDocuments(): Pair<List<PdfDocumentItem>, Boolean> {
        val docsStr = prefs.getString("documents", "[]")
        var titlesRepaired = false
        val list = try {
            val array = JSONArray(docsStr)
            val result = mutableListOf<PdfDocumentItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val uri = Uri.parse(obj.getString("uri"))
                val storedTitle = obj.optString("title")
                val source = obj.optString("sourceUri").takeIf { it.isNotBlank() }
                // Repair titles saved by older versions as raw provider IDs, or titles
                // overwritten with the hash filename of our private import copy.
                val title = when {
                    storedTitle.isNotBlank() && !looksLikeOpaqueId(storedTitle) &&
                        !looksLikeGeneratedName(storedTitle) -> storedTitle
                    source != null -> {
                        val fromSource = getFileName(Uri.parse(source))
                        if (fromSource.isNotBlank() && !looksLikeOpaqueId(fromSource) &&
                            fromSource != "Imported PDF"
                        ) fromSource else "Imported PDF"
                    }
                    else -> getFileName(uri).takeIf {
                        it.isNotBlank() && !looksLikeOpaqueId(it) && !looksLikeGeneratedName(it)
                    } ?: "Imported PDF"
                }
                if (title != storedTitle) titlesRepaired = true
                result.add(
                    PdfDocumentItem(
                        uri = uri,
                        title = title,
                        lastAccessed = obj.getLong("lastAccessed"),
                        sourceUri = source
                    )
                )
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
        return list to titlesRepaired
    }

    fun getRecentDocuments(limit: Int = 5): List<PdfDocumentItem> {
        val all = getDocuments()
        val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
        val recent = all.filter { System.currentTimeMillis() - it.lastAccessed < sevenDaysMs }
        return (if (recent.isNotEmpty()) recent else all).take(limit)
    }

    /**
     * A URI that once worked can stop working when its persisted read permission is
     * revoked or the underlying file is deleted/moved. Verify before keeping it.
     */
    fun isUriAccessible(uri: Uri): Boolean {
        return try {
            when (uri.scheme?.lowercase()) {
                "file" -> {
                    val path = uri.path
                    path != null && File(path).exists()
                }
                "content" -> context.contentResolver.persistedUriPermissions.any {
                    it.uri == uri && it.isReadPermission
                }
                else -> true
            }
        } catch (e: Exception) {
            false
        }
    }

    /** App-private directory that holds persistent copies of imported PDFs. */
    fun importsDir(): File = File(context.filesDir, "pdf").apply { mkdirs() }

    /** True when the URI points at a copy this app made itself. */
    fun isInternalCopy(uri: Uri): Boolean {
        if (uri.scheme != "file") return false
        val path = uri.path ?: return false
        return path.startsWith(importsDir().absolutePath + File.separator)
    }

    fun hasDocument(uri: Uri): Boolean = getDocuments().any { it.uri == uri }

    /**
     * Imports a PDF from any provider (WhatsApp, Gmail, Drive, a file manager) into
     * app-private storage and records a stable file:// URI in the library.
     *
     * This is essential: many providers only grant a temporary read permission, so
     * storing their content:// URI makes the entry disappear from Recent documents as
     * soon as the app restarts. Keeping our own copy fixes that and makes annotations
     * and sharing reliable too.
     */
    fun importDocument(source: Uri): PdfDocumentItem {
        val title = getFileName(source)
        val file = File(importsDir(), "${source.toString().hashCode()}.pdf")
        try {
            if (!file.exists() || file.length() == 0L) {
                val input = context.contentResolver.openInputStream(source)
                    ?: throw java.io.IOException("Cannot open the selected file")
                FileOutputStream(file).use { out -> input.use { it.copyTo(out, 64 * 1024) } }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // If the copy fails, keep the document for this session via its original URI.
            return addDocument(source, sourceUri = source.toString())
        }

        val localUri = Uri.fromFile(file)
        val newDoc = addDocument(localUri, titleOverride = title, sourceUri = source.toString())
        // Avoid a stale duplicate entry created by an older version for the same source.
        val all = getDocumentsIncludingMissing()
        val deduped = all.filter { it.uri == localUri || it.sourceUri != source.toString() }
        if (deduped.size != all.size) saveDocuments(deduped)
        return newDoc
    }

    fun addDocument(
        uri: Uri,
        titleOverride: String? = null,
        sourceUri: String? = null
    ): PdfDocumentItem {
        try {
            if (uri.scheme == "content") {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            }
        } catch (e: Exception) {
            // Some providers do not allow persistable grants; the caller imports a
            // private copy in that case, so the document still survives a restart.
            e.printStackTrace()
        }

        val existing = getDocumentsIncludingMissing().toMutableList()
        val previous = existing.firstOrNull { it.uri == uri }
        // Re-opening a library item passes no override. Never replace its real title with
        // the hash filename of our private copy.
        val title = titleOverride?.takeIf { it.isNotBlank() }
            ?: previous?.title?.takeIf {
                it.isNotBlank() && !looksLikeOpaqueId(it) && !looksLikeGeneratedName(it)
            }
            ?: getFileName(uri)
        existing.removeAll { it.uri == uri }

        val newDoc = PdfDocumentItem(
            uri = uri,
            title = title,
            lastAccessed = System.currentTimeMillis(),
            sourceUri = sourceUri ?: previous?.sourceUri
                ?: if (uri.scheme == "content") uri.toString() else null
        )
        existing.add(newDoc)

        saveDocuments(existing)
        return newDoc
    }

    fun removeDocument(uri: Uri) {
        // Remove only copies this app created. A user's original file is never deleted.
        if (isInternalCopy(uri)) {
            try {
                uri.path?.let { File(it).delete() }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        // Also clean up the engine's hidden working copy for this URI, if one exists.
        try {
            File(importsDir(), "${uri.toString().hashCode()}.pdf").delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val existing = getDocumentsIncludingMissing().toMutableList()
        existing.removeAll { it.uri == uri }
        saveDocuments(existing)

        try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.releasePersistableUriPermission(uri, takeFlags)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- Storage management -----------------------------------------------------

    /** Private copies on disk, annotated with whether a library entry still uses them. */
    fun listPrivateCopies(): List<PrivateCopy> {
        val docs = getDocumentsIncludingMissing()
        val byUri = docs.associateBy { it.uri.toString() }
        val files = importsDir().listFiles { f ->
            f.isFile && f.name.endsWith(".pdf", ignoreCase = true)
        } ?: emptyArray()
        return files.map { file ->
            val uri = Uri.fromFile(file).toString()
            PrivateCopy(
                file = file,
                sizeBytes = file.length(),
                lastModified = file.lastModified(),
                title = byUri[uri]?.title,
                inLibrary = byUri.containsKey(uri)
            )
        }.sortedByDescending { it.lastModified }
    }

    /** Library entries whose file is missing (e.g. a private copy was deleted). */
    fun getMissingDocuments(): List<PdfDocumentItem> =
        getDocumentsIncludingMissing().filter { !isUriAccessible(it.uri) }

    /** Human-readable source name for a missing entry's re-link prompt. */
    fun sourceName(item: PdfDocumentItem): String =
        item.sourceUri?.let { getFileName(Uri.parse(it)) }
            ?.takeIf { it.isNotBlank() && it != "Imported PDF" }
            ?: item.title

    /** Deletes a private copy. Never follows paths outside our own PDF directory. */
    fun deletePrivateCopy(file: File): Boolean = try {
        val dir = importsDir().canonicalPath + File.separator
        file.canonicalPath.startsWith(dir) && file.delete()
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }

    /** Removes library entries without touching any file, so they can be re-linked later. */
    fun forgetDocument(uri: Uri) {
        val remaining = getDocumentsIncludingMissing().filter { it.uri != uri }
        saveDocuments(remaining)
    }

    /**
     * Points a missing library entry at a newly picked file. A fresh private copy is
     * made first, then the original title and last-opened time are carried over.
     */
    fun relinkDocument(oldUri: Uri, newSource: Uri): PdfDocumentItem? {
        val old = getDocumentsIncludingMissing().firstOrNull { it.uri == oldUri } ?: return null
        val imported = importDocument(newSource)
        val updated = imported.copy(
            title = old.title,
            lastAccessed = old.lastAccessed,
            sourceUri = newSource.toString()
        )
        val finalDocs = getDocumentsIncludingMissing()
            .filter { it.uri != oldUri && it.uri != imported.uri } + updated
        saveDocuments(finalDocs)
        return updated
    }

    private fun saveDocuments(docs: List<PdfDocumentItem>) {
        val array = JSONArray()
        docs.forEach {
            val obj = JSONObject()
            obj.put("uri", it.uri.toString())
            obj.put("title", it.title)
            obj.put("lastAccessed", it.lastAccessed)
            it.sourceUri?.let { source -> obj.put("sourceUri", source) }
            array.put(obj)
        }
        prefs.edit().putString("documents", array.toString()).apply()
    }

    private fun getFileName(uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                // Use an explicit projection: some providers ignore a null projection
                // and return no DISPLAY_NAME column at all.
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) {
                            val name = cursor.getString(index)
                            if (!name.isNullOrBlank()) return cleanName(name)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Last resort: never surface an opaque provider ID as a document title.
        val segment = uri.lastPathSegment?.let { Uri.decode(it) }
        return if (segment.isNullOrBlank() || looksLikeOpaqueId(segment)) {
            "Imported PDF"
        } else {
            cleanName(segment)
        }
    }

    private fun cleanName(name: String): String = name.trim().take(120)

    /** Matches the hash-based names this app generates for private import copies. */
    private fun looksLikeGeneratedName(name: String): Boolean {
        val stem = name.replace(Regex("(?i)\\.pdf$"), "")
        return Regex("^-?\\d+$").matches(stem)
    }

    private fun looksLikeOpaqueId(segment: String): Boolean {
        val value = segment.substringAfterLast('/')
        if (value.isBlank()) return true
        if (value.all { it.isDigit() }) return true
        // Long IDs without a filename extension are almost always provider IDs.
        if (value.length > 32 && !value.contains('.')) return true
        return false
    }
}
