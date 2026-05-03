package com.drawapp

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.*
import java.io.File

object ModelDownloadHelper {

    private val pollScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun showDownloadDialog(
        activity: Activity,
        alreadyDownloading: Boolean,
        onDownloadIdAcquired: (Long) -> Unit,
        onDialogDismissed: () -> Unit,
        onModelLoaded: (String) -> Unit
    ): Dialog {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_model_download, null)

        val progressArea  = dialogView.findViewById<View>(R.id.dlProgressArea)
        val readyArea     = dialogView.findViewById<View>(R.id.dlReadyArea)
        val progressBar   = dialogView.findViewById<ProgressBar>(R.id.dlProgressBar)
        val progressLabel = dialogView.findViewById<TextView>(R.id.dlProgressLabel)
        val progressPct   = dialogView.findViewById<TextView>(R.id.dlProgressPercent)
        val errorText     = dialogView.findViewById<TextView>(R.id.dlErrorText)
        val btnStart      = dialogView.findViewById<Button>(R.id.btnStartDownload)
        val btnSkip       = dialogView.findViewById<Button>(R.id.btnSkipDownload)
        val etToken       = dialogView.findViewById<EditText>(R.id.etHfToken)
        val btnAcceptLic  = dialogView.findViewById<TextView>(R.id.btnAcceptLicense)
        val btnGetToken   = dialogView.findViewById<TextView>(R.id.btnGetToken)

        // Web links
        btnAcceptLic.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/google/gemma-4-E2B-it"))
            activity.startActivity(intent)
        }
        btnGetToken.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/settings/tokens"))
            activity.startActivity(intent)
        }

        // Pre-fill any previously saved token
        val savedToken = ModelManager.savedHfToken(activity)
        if (savedToken.isNotBlank()) etToken.setText(savedToken)

        val dialog = AlertDialog.Builder(activity, R.style.DarkDialogTheme)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        var pollJob: Job? = null
        var currentDownloadId = -1L

        fun pollDownload() {
            pollJob = pollScope.launch {
                val dlId = if (currentDownloadId != -1L) currentDownloadId else ModelManager.savedDownloadId(activity)
                if (dlId == -1L) return@launch

                val status = ModelManager.queryDownload(activity, dlId)

                when (status.state) {
                    ModelManager.DownloadState.RUNNING,
                    ModelManager.DownloadState.PAUSED -> {
                        progressBar.progress = status.progressPercent
                        progressLabel.text   = status.progressDisplay
                        progressPct.text     = "${status.progressPercent}%"
                        delay(1000)
                        pollDownload()
                    }
                    ModelManager.DownloadState.DONE -> {
                        val path = ModelManager.modelFile(activity).absolutePath
                        if (File(path).exists()) {
                            ModelManager.saveModelPath(activity, path)
                            dialog.dismiss()
                            onModelLoaded(path)
                        }
                    }
                    ModelManager.DownloadState.FAILED -> {
                        progressArea.visibility = View.GONE
                        readyArea.visibility    = View.VISIBLE
                        errorText.visibility    = View.VISIBLE
                        errorText.text          =
                            "Download failed (reason ${status.reason}). Check your internet connection and try again."
                    }
                    else -> {}
                }
            }
        }

        fun showProgress() {
            readyArea.visibility    = View.GONE
            progressArea.visibility = View.VISIBLE
            progressBar.progress    = 0
            progressLabel.text      = "Connecting…"
            progressPct.text        = "0%"
            pollDownload()
        }

        // ── If already downloading, jump straight to progress view ──────
        if (alreadyDownloading) {
            showProgress()
        } else {
            readyArea.visibility    = View.VISIBLE
            progressArea.visibility = View.GONE
        }

        // ── Buttons ─────────────────────────────────────────────────────
        btnStart.setOnClickListener {
            val token = etToken.text.toString().trim()
            if (token.isBlank()) {
                errorText.visibility = View.VISIBLE
                errorText.text = "Please enter your HuggingFace access token first."
                return@setOnClickListener
            }
            errorText.visibility = View.GONE
            btnStart.isEnabled = false
            btnStart.text = "Connecting..."

            ModelManager.startDownloadHFAsync(
                context = activity,
                hfToken = token,
                onSuccess = { id ->
                    currentDownloadId = id
                    onDownloadIdAcquired(id)
                    btnStart.isEnabled = true
                    btnStart.text = "⬇  Download Model"
                    showProgress()
                },
                onError = { msg ->
                    btnStart.isEnabled = true
                    btnStart.text = "⬇  Download Model"
                    errorText.visibility = View.VISIBLE
                    errorText.text = msg
                }
            )
        }

        btnSkip.setOnClickListener {
            pollJob?.cancel()
            dialog.dismiss()
            onDialogDismissed()
            showManualPathDialog(activity, onModelLoaded, onCancel = {
                showDownloadDialog(activity, false, onDownloadIdAcquired, onDialogDismissed, onModelLoaded)
            })
        }

        dialog.setOnDismissListener {
            pollJob?.cancel()
        }

        dialog.show()
        return dialog
    }

    private fun showManualPathDialog(activity: Activity, onModelLoaded: (String) -> Unit, onCancel: () -> Unit) {
        val input = EditText(activity).apply {
            hint = "/sdcard/Download/gemma-4-E2B-it.litertlm"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#55FFFFFF"))
            setPadding(32, 24, 32, 24)
        }
        AlertDialog.Builder(activity, R.style.DarkDialogTheme)
            .setTitle("Model File Path")
            .setMessage("Enter the full path to a Gemma .litertlm file already on this device:")
            .setView(input)
            .setPositiveButton("Load") { _, _ ->
                val path = input.text.toString().trim()
                if (path.isNotBlank() && File(path).exists()) {
                    ModelManager.saveModelPath(activity, path)
                    onModelLoaded(path)
                } else {
                    Toast.makeText(activity, "File not found at that path", Toast.LENGTH_LONG).show()
                    onCancel()
                }
            }
            .setNegativeButton("Back") { _, _ ->
                onCancel()
            }
            .show()
    }
}
