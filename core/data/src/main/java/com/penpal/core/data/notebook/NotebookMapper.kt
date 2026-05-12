package com.penpal.core.data.notebook

object NotebookMapper {
    fun toEntity(
        id: String,
        name: String,
        color: Int,
        lastDisplayedPage: Int = 0,
        defaultBackground: String = "RULED",
        type: NotebookType = NotebookType.NOTEBOOK
    ): NotebookEntity {
        return NotebookEntity(
            id = id,
            name = name,
            color = color,
            lastDisplayedPage = lastDisplayedPage,
            defaultBackground = defaultBackground,
            type = type
        )
    }
}