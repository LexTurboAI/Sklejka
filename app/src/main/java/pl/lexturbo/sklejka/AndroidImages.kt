package pl.lexturbo.sklejka

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import pl.lexturbo.sklejka.core.*

data class Photo(val id: String, val uri: Uri, val name: String, val raw: Size, val orientation: Int) {
    val size: Size get() = Orientation.size(raw, orientation)
}

fun inspectPhoto(context: Context, uri: Uri): Photo {
    require(uri.scheme == "content" || uri.scheme == "file") { "Obsługiwane są tylko lokalne obrazy." }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Nie można odczytać tego obrazu." }
    val orientation = context.contentResolver.openInputStream(uri)?.use {
        // Unsupported metadata means normal orientation; image decoding is checked separately.
        runCatching { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
    } ?: 1
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: "Zdjęcie"
    return Photo(java.util.UUID.randomUUID().toString(), uri, name, Size(bounds.outWidth, bounds.outHeight), orientation)
}

@Suppress("DEPRECATION")
class AndroidPixelSource(private val context: Context, private val photo: Photo) : PixelSource {
    override val size = photo.size
    // Open only the current source, so a list does not retain many native decoder buffers.
    private var decoder: BitmapRegionDecoder? = null
    private var fallback: Bitmap? = null
    private var opened = false
    private fun options() = BitmapFactory.Options().apply {
        inScaled = false; inSampleSize = 1; inPreferredConfig = Bitmap.Config.ARGB_8888
        inPremultiplied = false
        inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
    }
    private fun open() {
        if (opened) return
        opened = true
        decoder = runCatching {
            context.contentResolver.openInputStream(photo.uri)?.use { BitmapRegionDecoder.newInstance(it, false) }
        }.getOrNull()
        if (decoder == null) {
            val bytes = photo.raw.width.toLong() * photo.raw.height * 4
            val runtime = Runtime.getRuntime()
            val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
            require(bytes <= minOf(32L * 1024 * 1024, available / 4)) {
                "Format zdjęcia „${photo.name}” nie obsługuje odczytu pasami. ${photo.size.width} × ${photo.size.height} px przekracza bezpieczny limit odczytu. Nie zmniejszono zdjęcia."
            }
            fallback = context.contentResolver.openInputStream(photo.uri)?.use { BitmapFactory.decodeStream(it, null, options()) }
                ?: error("Nie można odczytać zdjęcia „${photo.name}”.")
        }
    }
    override fun read(box: Box): IntArray {
        open()
        val points = listOf(
            Orientation.rawPoint(box.x, box.y, photo.raw, photo.orientation),
            Orientation.rawPoint(box.x + box.width - 1, box.y, photo.raw, photo.orientation),
            Orientation.rawPoint(box.x, box.y + box.height - 1, photo.raw, photo.orientation),
            Orientation.rawPoint(box.x + box.width - 1, box.y + box.height - 1, photo.raw, photo.orientation)
        )
        val left = points.minOf { it.first }; val top = points.minOf { it.second }
        val rw = points.maxOf { it.first } - left + 1; val rh = points.maxOf { it.second } - top + 1
        val bitmap = decoder?.decodeRegion(Rect(left, top, left + rw, top + rh), options())
        val raw = IntArray(rw * rh)
        try {
            if (bitmap != null) bitmap.getPixels(raw, 0, rw, 0, 0, rw, rh)
            else (fallback ?: error("Nie można odczytać fragmentu zdjęcia.")).getPixels(raw, 0, rw, left, top, rw, rh)
        } finally { bitmap?.recycle() }
        val result = IntArray(box.width * box.height)
        for (y in 0 until box.height) for (x in 0 until box.width) {
            result[y * box.width + x] = raw[Orientation.regionIndex(box.x + x, box.y + y, photo.raw, photo.orientation, left, top, rw)]
        }
        return result
    }
    // The exporter retains only one active decoder across adjacent bands.
    fun release() { decoder?.recycle(); decoder = null; fallback?.recycle(); fallback = null; opened = false }
    override fun close() = release()
}

fun thumbnail(context: Context, uri: Uri, maxEdge: Int = 600, exif: Int? = null): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxEdge) sample *= 2
    val bitmap = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample; inScaled = false })
    } ?: return null
    if (exif == null || exif !in 2..8) return bitmap
    val rawSize = Size(bitmap.width, bitmap.height); val oriented = Orientation.size(rawSize, exif)
    val input = IntArray(bitmap.width * bitmap.height); bitmap.getPixels(input, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val pixels = IntArray(input.size)
    for (y in 0 until oriented.height) for (x in 0 until oriented.width) {
        val (rx, ry) = Orientation.rawPoint(x, y, rawSize, exif)
        pixels[y * oriented.width + x] = input[ry * rawSize.width + rx]
    }
    val result = Bitmap.createBitmap(pixels, oriented.width, oriented.height, Bitmap.Config.ARGB_8888)
    bitmap.recycle(); return result
}
