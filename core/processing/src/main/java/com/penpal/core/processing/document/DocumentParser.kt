package com.penpal.core.processing.document

import android.net.Uri
import com.penpal.core.ai.tools.web.RawChunk

interface DocumentParser {
    suspend fun parse(uri: Uri, rule: String): List<RawChunk>
}