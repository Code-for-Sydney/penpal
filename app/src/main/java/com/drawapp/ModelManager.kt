package com.drawapp

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Handles locating, downloading, and tracking the Gemma model file.
 *
 * Download source: HuggingFace LiteRT community models (no account required for public models).
 * The model is saved to the app's private external files dir — no storage permission needed.
 */
object ModelManager {

    // Model info
    const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"

    // HuggingFace URL for Gemma 4 E2B MediaPipe litertlm file (from LiteRT community)
    const val MODEL_DOWNLOAD_URL_HF =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"

    // Kaggle URL for the same model
    // Requires: kaggle.com account + accepting license at kaggle.com/models/google/gemma-4
    const val MODEL_DOWNLOAD_URL_KAGGLE =
        "https://www.kaggle.com/api/v1/models/google/gemma-4/tfLite/gemma4-e2b-it-web/1/download"

    // Back-compat default
    const val MODEL_DOWNLOAD_URL = MODEL_DOWNLOAD_URL_HF

    // SharedPreferences keys for credentials
    const val KEY_HF_TOKEN       = "hf_token"
    const val KEY_KAGGLE_USER    = "kaggle_user"
    const val KEY_KAGGLE_KEY     = "kaggle_key"

    // Approx size shown in UI (~2.6 GB)
    const val MODEL_SIZE_DISPLAY = "~2.6 GB"

    // SharedPreferences keys
    private const val PREFS_NAME   = "penpal_prefs"
    private const val KEY_DL_ID    = "download_id"
    private const val KEY_MODEL_PATH = "gemma_model_path"

    // ── File location ─────────────────────────────────────────────────────

    /** Returns the target File where the model will be saved. */
    fun modelFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, MODEL_FILE_NAME)
    }

    /** Returns the model path if the file already exists, otherwise null. */
    fun findExistingModel(context: Context): String? {
        // 1. App's own external directory (preferred — no permissions needed)
        val appFile = modelFile(context)
        if (appFile.exists() && appFile.length() > 1_000_000L) return appFile.absolutePath

        // 2. Previously saved path in SharedPreferences
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedPath = prefs.getString(KEY_MODEL_PATH, null)
        if (!savedPath.isNullOrBlank() && File(savedPath).exists()) return savedPath

        // 3. Common user download locations
        val candidates = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), MODEL_FILE_NAME),
            File("/sdcard/Download/$MODEL_FILE_NAME"),
            File("/sdcard/$MODEL_FILE_NAME")
        )
        for (f in candidates) {
            if (f.exists() && f.length() > 1_000_000L) {
                saveModelPath(context, f.absolutePath)
                return f.absolutePath
            }
        }

        return null
    }

    fun saveModelPath(context: Context, path: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_MODEL_PATH, path).apply()
    }

    fun clearModelPath(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_MODEL_PATH).remove(KEY_DL_ID).apply()
    }

    // ── Download ──────────────────────────────────────────────────────────

    /** Resolves redirects manually to prevent DownloadManager from passing auth headers to S3, which causes 403s. */
    fun startDownloadAsync(
        context: Context,
        url: String,
        authHeader: String?,
        onSuccess: (Long) -> Unit,
        onError: (String) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                var currentUrl = url
                var redirectCount = 0
                val maxRedirects = 5

                while (redirectCount < maxRedirects) {
                    val connection = java.net.URL(currentUrl).openConnection() as java.net.HttpURLConnection
                    connection.instanceFollowRedirects = false

                    // Only send auth to the primary domains. If it redirects to AWS/GCP, drop the header!
                    if (authHeader != null && (currentUrl.contains("huggingface.co") || currentUrl.contains("kaggle.com"))) {
                        connection.setRequestProperty("Authorization", authHeader)
                    }

                    connection.connect()
                    val code = connection.responseCode

                    if (code in 300..399) {
                        val location = connection.getHeaderField("Location")
                        if (location != null) {
                            currentUrl = location
                            redirectCount++
                            connection.disconnect()
                            continue
                        }
                    } else if (code == 401 || code == 403) {
                        val domain = if (currentUrl.contains("kaggle")) "Kaggle" else "HuggingFace"
                        withContext(Dispatchers.Main) {
                            onError("Access Denied (403). Have you accepted the Gemma license on $domain? Also check your token.")
                        }
                        return@launch
                    } else if (code >= 400) {
                        withContext(Dispatchers.Main) {
                            onError("Server error $code")
                        }
                        return@launch
                    }
                    connection.disconnect()
                    break
                }

                withContext(Dispatchers.Main) {
                    val destFile = modelFile(context)
                    if (destFile.exists()) destFile.delete()

                    val request = DownloadManager.Request(Uri.parse(currentUrl))
                        .setTitle("Penpal — Downloading Gemma model")
                        .setDescription("$MODEL_SIZE_DISPLAY · This may take several minutes on Wi-Fi")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationUri(Uri.fromFile(destFile))
                        .setAllowedOverMetered(false)
                        .setAllowedOverRoaming(false)

                    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    val id = dm.enqueue(request)

                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putLong(KEY_DL_ID, id).apply()

                    onSuccess(id)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onError("Network error: ${e.message}")
                }
            }
        }
    }

    fun startDownloadHFAsync(context: Context, hfToken: String, onSuccess: (Long) -> Unit, onError: (String) -> Unit) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_HF_TOKEN, hfToken).apply()
        startDownloadAsync(context, MODEL_DOWNLOAD_URL_HF, "Bearer $hfToken", onSuccess, onError)
    }

    fun startDownloadKaggleAsync(context: Context, username: String, apiKey: String, onSuccess: (Long) -> Unit, onError: (String) -> Unit) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_KAGGLE_USER, username)
            .putString(KEY_KAGGLE_KEY, apiKey)
            .apply()
        val credentials = android.util.Base64.encodeToString("$username:$apiKey".toByteArray(), android.util.Base64.NO_WRAP)
        startDownloadAsync(context, MODEL_DOWNLOAD_URL_KAGGLE, "Basic $credentials", onSuccess, onError)
    }


    fun savedHfToken(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HF_TOKEN, "") ?: ""

    fun savedKaggleUser(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_KAGGLE_USER, "") ?: ""

    fun savedKaggleKey(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_KAGGLE_KEY, "") ?: ""

    /** Returns the saved download ID, or -1 if none. */
    fun savedDownloadId(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_DL_ID, -1L)

    /**
     * Queries DownloadManager for the status/progress of [downloadId].
     * Returns a [DownloadStatus] data class.
     */
    fun queryDownload(context: Context, downloadId: Long): DownloadStatus {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor: Cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))

        if (!cursor.moveToFirst()) {
            cursor.close()
            return DownloadStatus(DownloadState.NOT_FOUND, 0, 0)
        }

        val statusCol    = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
        val bytesCol     = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
        val totalCol     = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
        val reasonCol    = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)

        val status   = cursor.getInt(statusCol)
        val bytes    = cursor.getLong(bytesCol)
        val total    = cursor.getLong(totalCol)
        val reason   = cursor.getInt(reasonCol)
        cursor.close()

        val state = when (status) {
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PENDING  -> DownloadState.RUNNING
            DownloadManager.STATUS_PAUSED   -> DownloadState.PAUSED
            DownloadManager.STATUS_SUCCESSFUL -> DownloadState.DONE
            DownloadManager.STATUS_FAILED   -> DownloadState.FAILED
            else -> DownloadState.NOT_FOUND
        }
        return DownloadStatus(state, bytes, total, reason)
    }

    // ── Data ──────────────────────────────────────────────────────────────

    enum class DownloadState { NOT_FOUND, RUNNING, PAUSED, DONE, FAILED }

    data class DownloadStatus(
        val state: DownloadState,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val reason: Int = 0
    ) {
        val progressPercent: Int get() =
            if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt() else 0

        val progressDisplay: String get() {
            val dl  = bytesDownloaded / (1024 * 1024)
            val tot = if (totalBytes > 0) totalBytes / (1024 * 1024) else 0
            return "${dl} MB / ${tot} MB"
        }
    }
}
