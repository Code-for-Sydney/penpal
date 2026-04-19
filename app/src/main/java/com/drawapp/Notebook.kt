package com.drawapp

import java.util.UUID

data class Notebook(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var color: Int,
    var lastDisplayedPage: Int = 0,
    var defaultBackground: String = "RULED"
)

