package com.drawapp

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

class NotebookSelectionActivity : AppCompatActivity() {

    private lateinit var rvNotebooks: RecyclerView
    private lateinit var fabAdd: FloatingActionButton
    private lateinit var adapter: NotebookAdapter

    private val colors = listOf(
        "#FF4081", "#F44336", "#FF9800", "#FFEB3B", "#4CAF50",
        "#00BCD4", "#2196F3", "#9C27B0", "#795548", "#607D8B"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notebook_selection)

        rvNotebooks = findViewById(R.id.rvNotebooks)
        fabAdd = findViewById(R.id.fabAddNotebook)

        setupRecyclerView()

        fabAdd.setOnClickListener {
            showAddEditDialog(null)
        }
    }

    private fun setupRecyclerView() {
        adapter = NotebookAdapter(
            NotebookManager.getNotebooks(this),
            onNotebookClick = { notebook ->
                val intent = Intent(this, MainActivity::class.java).apply {
                    putExtra("NOTEBOOK_ID", notebook.id)
                    putExtra("NOTEBOOK_NAME", notebook.name)
                }
                startActivity(intent)
            },
            onEditClick = { notebook ->
                showAddEditDialog(notebook)
            }
        )
        rvNotebooks.layoutManager = GridLayoutManager(this, 2)
        rvNotebooks.adapter = adapter
    }

    private fun showAddEditDialog(notebook: Notebook?) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_notebook, null)
        val etName = dialogView.findViewById<EditText>(R.id.etNotebookName)
        val colorGrid = dialogView.findViewById<GridLayout>(R.id.colorGrid)
        val btnDelete = dialogView.findViewById<Button>(R.id.btnDeleteNotebook)

        var selectedColor = notebook?.color ?: Color.parseColor(colors[0])
        etName.setText(notebook?.name ?: "")
        
        btnDelete.visibility = if (notebook != null) View.VISIBLE else View.GONE

        // Populate color grid
        colors.forEach { hex ->
            val color = Color.parseColor(hex)
            val swatch = View(this).apply {
                val size = resources.getDimensionPixelSize(R.dimen.color_swatch_size)
                val params = GridLayout.LayoutParams().apply {
                    width = size; height = size; setMargins(8, 8, 8, 8)
                }
                layoutParams = params
                background = createSwatchDrawable(color, color == selectedColor)
                setOnClickListener {
                    selectedColor = color
                    // Update all swatches to show selection
                    for (i in 0 until colorGrid.childCount) {
                        val child = colorGrid.getChildAt(i)
                        val childColor = child.tag as Int
                        child.background = createSwatchDrawable(childColor, childColor == selectedColor)
                    }
                }
                tag = color
            }
            colorGrid.addView(swatch)
        }

        val dialog = AlertDialog.Builder(this, R.style.DarkDialogTheme)
            .setTitle(if (notebook == null) "New Notebook" else "Edit Notebook")
            .setView(dialogView)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isEmpty()) {
                etName.error = "Name cannot be empty"
                return@setOnClickListener
            }

            if (notebook == null) {
                NotebookManager.addNotebook(this, Notebook(name = name, color = selectedColor))
            } else {
                notebook.name = name
                notebook.color = selectedColor
                NotebookManager.updateNotebook(this, notebook)
            }
            adapter.updateData(NotebookManager.getNotebooks(this))
            dialog.dismiss()
        }

        btnDelete.setOnClickListener {
            notebook?.let { 
                NotebookManager.deleteNotebook(this, it.id)
                adapter.updateData(NotebookManager.getNotebooks(this))
                dialog.dismiss()
            }
        }
    }

    private fun createSwatchDrawable(color: Int, isSelected: Boolean): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (isSelected) {
                setStroke(6, Color.WHITE)
            } else {
                setStroke(2, Color.parseColor("#40FFFFFF"))
            }
        }
    }
}
