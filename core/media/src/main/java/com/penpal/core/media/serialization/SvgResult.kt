package com.penpal.core.media.serialization

data class SvgResult(
    val items: List<SvgData>,
    val backgroundType: String = "RULED"
)