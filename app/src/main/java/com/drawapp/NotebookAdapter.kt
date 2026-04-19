package com.drawapp

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class NotebookAdapter(
    private var notebooks: List<Notebook>,
    private val onNotebookClick: (Notebook) -> Unit,
    private val onEditClick: (Notebook) -> Unit
) : RecyclerView.Adapter<NotebookAdapter.NotebookViewHolder>() {

    class NotebookViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.notebookName)
        val colorView: View = view.findViewById(R.id.notebookColor)
        val editBtn: ImageButton = view.findViewById(R.id.btnEditNotebook)
        val card: View = view.findViewById(R.id.cardNotebook)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotebookViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_notebook, parent, false)
        return NotebookViewHolder(view)
    }

    override fun onBindViewHolder(holder: NotebookViewHolder, position: Int) {
        val notebook = notebooks[position]
        holder.name.text = notebook.name
        
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24f
            setColor(notebook.color)
        }
        holder.colorView.background = drawable

        holder.card.setOnClickListener { onNotebookClick(notebook) }
        holder.editBtn.setOnClickListener { onEditClick(notebook) }
    }

    override fun getItemCount() = notebooks.size

    fun updateData(newNotebooks: List<Notebook>) {
        notebooks = newNotebooks
        notifyDataSetChanged()
    }
}
