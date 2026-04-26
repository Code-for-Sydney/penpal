package com.drawapp

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

object NotebookManager {
    private const val PREFS_NAME = "NotebookPrefs"
    private const val KEY_NOTEBOOKS = "notebooks"
    private val gson = Gson()

    fun getNotebooks(context: Context): MutableList<Notebook> {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_NOTEBOOKS, null)
        return if (json != null) {
            val type = object : TypeToken<MutableList<Notebook>>() {}.type
            gson.fromJson(json, type)
        } else {
            // Default notebooks if none exist
            mutableListOf(
                Notebook(name = "My Sketches", color = Color.parseColor("#FF4081"), type = NotebookType.NOTEBOOK),
                Notebook(name = "Whiteboard", color = Color.parseColor("#2196F3"), type = NotebookType.WHITEBOARD)
            )
        }
    }

    fun saveNotebooks(context: Context, notebooks: List<Notebook>) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = gson.toJson(notebooks)
        prefs.edit().putString(KEY_NOTEBOOKS, json).apply()
    }

    fun addNotebook(context: Context, notebook: Notebook) {
        val notebooks = getNotebooks(context)
        notebooks.add(notebook)
        saveNotebooks(context, notebooks)
    }

    fun updateNotebook(context: Context, updatedNotebook: Notebook) {
        val notebooks = getNotebooks(context)
        val index = notebooks.indexOfFirst { it.id == updatedNotebook.id }
        if (index != -1) {
            notebooks[index] = updatedNotebook
            saveNotebooks(context, notebooks)
        }
    }

    fun deleteNotebook(context: Context, notebookId: String) {
        val notebooks = getNotebooks(context)
        val notebook = notebooks.find { it.id == notebookId }
        if (notebook != null) {
            deleteNotebookSvg(context, notebook.name)
            notebooks.removeAll { it.id == notebookId }
            saveNotebooks(context, notebooks)
        }
    }

    fun deleteNotebookSvg(context: Context, notebookName: String) {
        try {
            val dir = File(context.filesDir, "notebooks")
            val files = dir.listFiles { _, name -> 
                name.startsWith("${notebookName}_page_") && (name.endsWith(".svg") || name.endsWith(".png"))
            }
            files?.forEach { it.delete() }
            
            // Also delete the old format if it exists
            val oldFile = File(dir, "${notebookName}.svg")
            if (oldFile.exists()) oldFile.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
