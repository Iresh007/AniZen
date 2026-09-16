/*
 * Copyright 2024 Abdallah Mehiz
 * https://github.com/abdallahmehiz/mpvKt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.kanade.tachiyomi.ui.player

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.ui.unit.IntSize
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import `is`.xyz.mpv.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import logcat.LogPriority
import logcat.logcat
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

internal fun Uri.openContentFd(context: Context): String? {
    return context.contentResolver.openFileDescriptor(this, "r")?.detachFd()?.let { fd ->
        val path = Utils.findRealPath(fd)
        if (path != null && java.io.File(path).canRead()) {
            ParcelFileDescriptor.adoptFd(fd).close()
            path
        } else {
            "fd://$fd"
        }
    }
}

internal fun Uri.resolveUri(context: Context): String? {
    val filepath = when (scheme) {
        "file" -> path
        "content" -> openContentFd(context)
        "data" -> "data://$schemeSpecificPart"
        in Utils.PROTOCOLS -> toString()
        else -> null
    }

    if (filepath == null) logcat(LogPriority.ERROR) { "unknown scheme: $scheme" }
    return filepath
}

internal fun Uri.getFileName(context: Context): String? {
    return context.contentResolver.query(this, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        cursor.moveToFirst()
        cursor.getString(nameIndex)
    }
}

// ANZ -->
inline fun <reified T> MPVNode.toObject(json: Json): T = json.decodeFromString<T>(toJson())

fun <T> Flow<T>.collectAsState(scope: CoroutineScope, initialValue: T? = null) =
    object : ReadOnlyProperty<Any?, T?> {
        private var value: T? = initialValue
        init {
            scope.launch { collect { value = it } }
        }
        override fun getValue(thisRef: Any?, property: KProperty<*>) = value
    }

/**
 * Returns the width and height of the video as it appears on the screen at 1x zoom.
 * This takes into account the video's aspect ratio and the screen's aspect ratio.
 */
internal fun videoDisplaySize(screenSize: IntSize, mpv: MPV? = null): Pair<Float, Float> {
    val sw = screenSize.width.toFloat()
    val sh = screenSize.height.toFloat()
    // "video-params/aspect" tells us the video's actual ratio (e.g., 2.35:1)
    val va = mpv?.getPropertyDouble("video-params/aspect")?.toFloat() ?: (sw / sh)
    val sa = sw / sh
    return if (va >= sa) Pair(sw, sw / va) else Pair(sh * va, sh)
}
// ANZ <--

fun parseButtons(
    csv: String,
): List<PlayerButton> =
    csv
        .splitToSequence(',')
        .map { it.trim() }
        .mapNotNull { name ->
            try {
                PlayerButton.valueOf(name)
            } catch (_: IllegalArgumentException) {
                null
            }
        }.toList()
