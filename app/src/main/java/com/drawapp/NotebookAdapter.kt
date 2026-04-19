package com.drawapp

import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class NotebookAdapter(
    private var notebooks: List<Notebook>,
    private val onNotebookClick: (Notebook) -> Unit,
    private val onEditClick: (Notebook) -> Unit
) : RecyclerView.Adapter<NotebookAdapter.NotebookViewHolder>() {

    class NotebookViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.notebookName)
        val imagePreview: ImageView = view.findViewById(R.id.notebookImagePreview)
        val editBtn: ImageButton = view.findViewById(R.id.btnEditNotebook)
        val card: com.google.android.material.card.MaterialCardView = view.findViewById(R.id.cardNotebook)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotebookViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_notebook, parent, false)
        return NotebookViewHolder(view)
    }

    override fun onBindViewHolder(holder: NotebookViewHolder, position: Int) {
        val notebook = notebooks[position]
        holder.name.text = notebook.name
        
        holder.card.strokeColor = notebook.color

        val thumbFile = File(holder.itemView.context.filesDir, "notebooks/${notebook.name}_page_${notebook.lastDisplayedPage}_thumb.png")
        if (thumbFile.exists()) {
            val bmp = BitmapFactory.decodeFile(thumbFile.absolutePath)
            holder.imagePreview.setImageBitmap(bmp)
        } else {
            holder.imagePreview.setImageDrawable(null)
        }

        holder.card.setOnClickListener { onNotebookClick(notebook) }
        holder.editBtn.setOnClickListener { onEditClick(notebook) }
    }

    override fun getItemCount() = notebooks.size

    fun updateData(newNotebooks: List<Notebook>) {
        notebooks = newNotebooks
        notifyDataSetChanged()
    }
}
