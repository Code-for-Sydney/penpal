package com.drawapp

import java.util.UUID

enum class NotebookType {
    NOTEBOOK, // Fixed size pages, vertical list
    WHITEBOARD // Infinite canvas, one page
}

data class Notebook(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var color: Int,
    var lastDisplayedPage: Int = 0,
    var defaultBackground: String = "RULED",
    var type: NotebookType = NotebookType.NOTEBOOK
)

