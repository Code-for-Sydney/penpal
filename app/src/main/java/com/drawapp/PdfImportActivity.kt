package com.drawapp

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.*

class PdfImportActivity : AppCompatActivity() {

    private lateinit var rvPages: RecyclerView
    private lateinit var btnBack: ImageButton
    private lateinit var btnConfirm: Button
    private lateinit var btnSelectAll: Button
    private lateinit var btnDeselectAll: Button
    private lateinit var tvTitle: TextView
    
    private var pdfRenderer: PdfRenderer? = null
    private var pdfUri: Uri? = null
    private val selectedPages = mutableSetOf<Int>()
    private var pageCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pdf_import)

        rvPages = findViewById(R.id.rvPdfPages)
        btnBack = findViewById(R.id.btnImportBack)
        btnConfirm = findViewById(R.id.btnConfirmImport)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnDeselectAll = findViewById(R.id.btnDeselectAll)
        tvTitle = findViewById(R.id.tvImportTitle)

        val uriString = intent.getStringExtra("PDF_URI")
        if (uriString == null) {
            finish()
            return
        }
        pdfUri = Uri.parse(uriString)
        
        setupPdfRenderer(pdfUri!!)
        
        btnBack.setOnClickListener { finish() }
        btnConfirm.setOnClickListener { confirmSelection() }

        btnSelectAll.setOnClickListener {
            for (i in 0 until pageCount) selectedPages.add(i)
            rvPages.adapter?.notifyDataSetChanged()
        }

        btnDeselectAll.setOnClickListener {
            selectedPages.clear()
            rvPages.adapter?.notifyDataSetChanged()
        }

        setupRecyclerView()
    }

    private fun setupPdfRenderer(uri: Uri) {
        try {
            val pfd = contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                pdfRenderer = PdfRenderer(pfd)
                pageCount = pdfRenderer?.pageCount ?: 0
                // Default select all
                for (i in 0 until pageCount) selectedPages.add(i)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error opening PDF", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun setupRecyclerView() {
        rvPages.layoutManager = GridLayoutManager(this, 3)
        rvPages.adapter = PageAdapter()
    }

    private fun confirmSelection() {
        if (selectedPages.isEmpty()) {
            Toast.makeText(this, "Select at least one page", Toast.LENGTH_SHORT).show()
            return
        }
        
        val resultIntent = android.content.Intent()
        resultIntent.putExtra("SELECTED_PAGES", selectedPages.toIntArray())
        resultIntent.putExtra("PDF_URI", pdfUri.toString())
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        pdfRenderer?.close()
    }

    inner class PageAdapter : RecyclerView.Adapter<PageAdapter.PageViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_pdf_page_import, parent, false)
            return PageViewHolder(view)
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            holder.bind(position)
        }

        override fun getItemCount(): Int = pageCount

        inner class PageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val imgPreview: ImageView = view.findViewById(R.id.imgPagePreview)
            private val cbSelect: CheckBox = view.findViewById(R.id.cbSelectPage)
            private val tvPageNum: TextView = view.findViewById(R.id.tvPageNum)

            fun bind(index: Int) {
                tvPageNum.text = "Page ${index + 1}"
                
                cbSelect.setOnCheckedChangeListener(null)
                cbSelect.isChecked = selectedPages.contains(index)
                
                // Load preview (low res for grid)
                pdfRenderer?.let { renderer ->
                    val page = renderer.openPage(index)
                    val width = 300 // Increased slightly for better visibility
                    val height = (width * 1.414).toInt()
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    
                    // Fill with white before rendering
                    val canvas = android.graphics.Canvas(bitmap)
                    canvas.drawColor(android.graphics.Color.WHITE)
                    
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    imgPreview.setImageBitmap(bitmap)
                    page.close()
                }

                cbSelect.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedPages.add(index)
                    else selectedPages.remove(index)
                }
                
                itemView.setOnClickListener {
                    cbSelect.isChecked = !cbSelect.isChecked
                }
            }
        }
    }
}
