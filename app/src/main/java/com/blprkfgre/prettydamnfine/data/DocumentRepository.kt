package com.blprkfgre.prettydamnfine.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PdfDocumentItem(val uri: Uri, val title: String, val lastAccessed: Long)

class DocumentRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("pdf_library", Context.MODE_PRIVATE)

    fun getDocuments(): List<PdfDocumentItem> {
        val docsStr = prefs.getString("documents", "[]")
        val list = try {
            val array = JSONArray(docsStr)
            val result = mutableListOf<PdfDocumentItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val uri = Uri.parse(obj.getString("uri"))
                val storedTitle = obj.optString("title")
                // Repair titles saved by older versions as raw provider IDs.
                val title = if (storedTitle.isBlank() || looksLikeOpaqueId(storedTitle)) {
                    getFileName(uri)
                } else {
                    storedTitle
                }
                result.add(
                    PdfDocumentItem(
                        uri = uri,
                        title = title,
                        lastAccessed = obj.getLong("lastAccessed")
                    )
                )
            }
            result
        } catch (e: Exception) {
            emptyList()
        }

        // Drop entries whose file/permission is no longer available. This keeps the
        // history from filling up with dead "random string" entries that cannot open.
        val available = list.filter { isUriAccessible(it.uri) }
        if (available.size != list.size) saveDocuments(available)
        return available.sortedByDescending { it.lastAccessed }
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

    fun addDocument(uri: Uri): PdfDocumentItem {
        try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: Exception) {
            // Some providers do not allow persistable grants; the file still works for
            // this session, so keep going rather than dropping the document entirely.
            e.printStackTrace()
        }

        val title = getFileName(uri)
        val existing = getDocuments().toMutableList()
        existing.removeAll { it.uri == uri }

        val newDoc = PdfDocumentItem(uri, title, System.currentTimeMillis())
        existing.add(newDoc)

        saveDocuments(existing)
        return newDoc
    }

    fun removeDocument(uri: Uri) {
        val existing = getDocuments().toMutableList()
        existing.removeAll { it.uri == uri }
        saveDocuments(existing)

        try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.releasePersistableUriPermission(uri, takeFlags)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveDocuments(docs: List<PdfDocumentItem>) {
        val array = JSONArray()
        docs.forEach {
            val obj = JSONObject()
            obj.put("uri", it.uri.toString())
            obj.put("title", it.title)
            obj.put("lastAccessed", it.lastAccessed)
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

    private fun looksLikeOpaqueId(segment: String): Boolean {
        val value = segment.substringAfterLast('/')
        if (value.isBlank()) return true
        if (value.all { it.isDigit() }) return true
        // Long IDs without a filename extension are almost always provider IDs.
        if (value.length > 32 && !value.contains('.')) return true
        return false
    }
}
