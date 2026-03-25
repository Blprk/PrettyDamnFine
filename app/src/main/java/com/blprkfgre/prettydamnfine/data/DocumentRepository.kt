package com.blprkfgre.prettydamnfine.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject

data class PdfDocumentItem(val uri: Uri, val title: String, val lastAccessed: Long)

class DocumentRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("pdf_library", Context.MODE_PRIVATE)

    fun getDocuments(): List<PdfDocumentItem> {
        val docsStr = prefs.getString("documents", "[]")
        return try {
            val array = JSONArray(docsStr)
            val list = mutableListOf<PdfDocumentItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    PdfDocumentItem(
                        uri = Uri.parse(obj.getString("uri")),
                        title = obj.getString("title"),
                        lastAccessed = obj.getLong("lastAccessed")
                    )
                )
            }
            list.sortedByDescending { it.lastAccessed }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getRecentDocuments(limit: Int = 5): List<PdfDocumentItem> {
        val all = getDocuments()
        val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
        val recent = all.filter { System.currentTimeMillis() - it.lastAccessed < sevenDaysMs }
        return (if (recent.isNotEmpty()) recent else all).take(limit)
    }

    fun addDocument(uri: Uri): PdfDocumentItem {
        try {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, takeFlags)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val title = getFileName(uri) ?: "Unknown Document"
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

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0) {
                            result = cursor.getString(index)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return result ?: uri.path?.substringAfterLast('/')
    }
}
