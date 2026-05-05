package com.penpal.core.processing

import android.net.Uri
import com.penpal.core.ai.RawChunk

interface DocumentParser {
    suspend fun parse(uri: Uri, rule: String): List<RawChunk>
}