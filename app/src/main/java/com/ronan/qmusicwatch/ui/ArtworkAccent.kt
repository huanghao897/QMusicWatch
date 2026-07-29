package com.ronan.qmusicwatch.ui

import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun cachedAccent(value: String, artworkUrl: String): Int? {
    val separator = value.lastIndexOf('|')
    if (separator <= 0 || value.substring(0, separator) != artworkUrl) return null
    return value.substring(separator + 1).toLongOrNull()?.toInt()
}

private fun extractArtworkAccent(pixels: IntArray): Int? {
    var red = 0L
    var green = 0L
    var blue = 0L
    var count = 0L
    val hsv = FloatArray(3)
    for (index in pixels.indices step 3) {
        val pixel = pixels[index]
        AndroidColor.colorToHSV(pixel, hsv)
        if (hsv[1] < .28f || hsv[2] < .22f || hsv[2] > .94f) continue
        red += AndroidColor.red(pixel)
        green += AndroidColor.green(pixel)
        blue += AndroidColor.blue(pixel)
        count++
    }
    if (count == 0L) return null
    return AndroidColor.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
}

@Composable
fun rememberArtworkAccent(
    artworkUrl: String,
    cachedValue: String,
    onResolved: (String, Long) -> Unit,
): Color {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cached = remember(artworkUrl, cachedValue) { cachedAccent(cachedValue, artworkUrl) }
    var accent by remember(artworkUrl) { mutableStateOf(cached?.let(::Color) ?: WatchAccent) }
    LaunchedEffect(artworkUrl, cached) {
        if (artworkUrl.isBlank() || cached != null) return@LaunchedEffect
        val resolved = withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context)
                .data(artworkUrl)
                .size(64)
                .allowHardware(false)
                .build()
            val result = context.imageLoader.execute(request) as? SuccessResult ?: return@withContext null
            val bitmap = result.drawable.toBitmap(64, 64)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            extractArtworkAccent(pixels)
        } ?: return@LaunchedEffect
        accent = Color(resolved)
        onResolved(artworkUrl, resolved.toLong() and 0xffffffffL)
    }
    return accent
}
