package com.drawapp

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
                Notebook(name = "My Sketches", color = Color.parseColor("#FF4081")),
                Notebook(name = "Ideas", color = Color.parseColor("#2196F3"))
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
            deleteNotebookImage(context, notebook.name)
            notebooks.removeAll { it.id == notebookId }
            saveNotebooks(context, notebooks)
        }
    }

    fun deleteNotebookImage(context: Context, notebookName: String) {
        val fileName = "${notebookName}.png"
        val relativePath = Environment.DIRECTORY_PICTURES + "/Penpal"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // MediaStore deletion for Android 10+
                val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND (${MediaStore.Images.Media.RELATIVE_PATH} = ? OR ${MediaStore.Images.Media.RELATIVE_PATH} = ?)"
                val selectionArgs = arrayOf(fileName, relativePath, "$relativePath/")
                context.contentResolver.delete(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    selection,
                    selectionArgs
                )
            } else {
                // Legacy file deletion for older versions
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Penpal")
                val file = File(dir, fileName)
                if (file.exists()) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
