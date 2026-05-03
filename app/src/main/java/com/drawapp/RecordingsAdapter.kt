package com.drawapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Adapter for displaying recorded audio files in a list.
 */
class RecordingsAdapter(
    private val recordings: MutableList<File>,
    private val audioRecorder: AudioRecorder,
    private val onPlayClicked: (File) -> Unit,
    private val onDeleteClicked: (File) -> Unit,
    private val onTranscribeClicked: ((File) -> Unit)? = null
) : RecyclerView.Adapter<RecordingsAdapter.RecordingViewHolder>() {

    private var playingFile: File? = null
    private val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

    fun setPlayingFile(file: File?) {
        val oldPlaying = playingFile
        playingFile = file

        // Update old playing item
        oldPlaying?.let { old ->
            val oldIndex = recordings.indexOf(old)
            if (oldIndex >= 0) notifyItemChanged(oldIndex)
        }

        // Update new playing item
        file?.let { new ->
            val newIndex = recordings.indexOf(new)
            if (newIndex >= 0) notifyItemChanged(newIndex)
        }
    }

    fun removeItem(file: File) {
        val index = recordings.indexOf(file)
        if (index >= 0) {
            recordings.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordingViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recording, parent, false)
        return RecordingViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecordingViewHolder, position: Int) {
        val file = recordings[position]
        holder.bind(file)
    }

    override fun getItemCount(): Int = recordings.size

    inner class RecordingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val btnPlay: ImageButton = itemView.findViewById(R.id.btnPlay)
        private val tvFileName: TextView = itemView.findViewById(R.id.tvFileName)
        private val tvDuration: TextView = itemView.findViewById(R.id.tvDuration)
        private val tvDate: TextView = itemView.findViewById(R.id.tvDate)
        private val ivPlaying: ImageView = itemView.findViewById(R.id.ivPlaying)
        private val btnTranscribe: ImageButton = itemView.findViewById(R.id.btnTranscribe)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)

        fun bind(file: File) {
            tvFileName.text = file.name

            // Duration
            val durationMs = audioRecorder.getDurationMs(file)
            tvDuration.text = formatDuration(durationMs)

            // Date
            tvDate.text = dateFormat.format(Date(file.lastModified()))

            // Playing state
            val isPlaying = file == playingFile
            ivPlaying.visibility = if (isPlaying) View.VISIBLE else View.GONE
            btnPlay.setImageResource(
                if (isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )

            // Transcribe button visibility
            btnTranscribe.visibility = if (onTranscribeClicked != null) View.VISIBLE else View.GONE

            // Click handlers
            btnPlay.setOnClickListener { onPlayClicked(file) }

            btnTranscribe.setOnClickListener {
                onTranscribeClicked?.invoke(file)
            }

            btnDelete.setOnClickListener {
                onDeleteClicked(file)
            }
        }

        private fun formatDuration(ms: Long): String {
            val seconds = (ms / 1000) % 60
            val minutes = (ms / 1000) / 60
            return String.format("%d:%02d", minutes, seconds)
        }
    }
}