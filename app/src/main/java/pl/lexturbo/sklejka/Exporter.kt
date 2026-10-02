package pl.lexturbo.sklejka

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import pl.lexturbo.sklejka.core.*
import java.io.BufferedOutputStream
import java.io.FilterOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExportOptions(val direction: Direction, val reverse: Boolean, val format: Format,
                         val background: Background, val gap: Int, val quality: Int)
data class SavedImage(val uri: Uri, val name: String, val mime: String, val size: Size, val bytes: Long, val location: String)

object Exporter {
    fun name(format: Format) = "Sklejka_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date())}.${format.extension}"
    fun export(context: Context, photos: List<Photo>, options: ExportOptions, target: Uri? = null,
               cancelled: () -> Boolean = { false }, progress: (Float) -> Unit = {}): SavedImage {
        val plan = Layout.plan(photos.map { it.size }, options.direction, options.reverse, options.gap)
        require(options.format == Format.PNG || options.background != Background.TRANSPARENT) { "JPG nie obsługuje przezroczystości. Wybierz białe lub czarne tło." }
        if (options.format == Format.PNG) Limits.png(plan.size)
        else {
            val r = Runtime.getRuntime()
            Limits.jpeg(plan.size, r.maxMemory() - (r.totalMemory() - r.freeMemory()))
        }
        val fileName = name(options.format)
        val resolver = context.contentResolver
        val uri = target ?: run {
            check(Build.VERSION.SDK_INT >= 29)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, options.format.mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Sklejka")
                put(MediaStore.Images.Media.IS_PENDING, 1)
                put(MediaStore.Images.Media.WIDTH, plan.size.width)
                put(MediaStore.Images.Media.HEIGHT, plan.size.height)
            }
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Nie można utworzyć pliku w Galerii.")
        }
        val sources = photos.map { AndroidPixelSource(context, it) }
        var fileBytes = 0L
        var active: AndroidPixelSource? = null
        val readers = sources.map { source -> object : PixelSource {
            override val size = source.size
            override fun read(box: Box): IntArray {
                if (active !== source) { active?.release(); active = source }
                return source.read(box)
            }
        } }
        try {
            resolver.openOutputStream(uri, "w")?.use { raw ->
                val counted = object : FilterOutputStream(raw) {
                    override fun write(value: Int) { out.write(value); fileBytes++ }
                    override fun write(bytes: ByteArray, offset: Int, count: Int) { out.write(bytes, offset, count); fileBytes += count }
                }
                BufferedOutputStream(counted, 65536).use { output ->
                    if (options.format == Format.PNG) {
                        PngWriter(output, plan.size).use { png ->
                            Stitcher.bands(readers, plan, options.background, cancelled) { y, rows, pixels ->
                                png.write(pixels, rows)
                                progress((y + rows).toFloat() / plan.size.height)
                            }
                            png.finish()
                        }
                    } else {
                        val bitmap = Bitmap.createBitmap(plan.size.width, plan.size.height, Bitmap.Config.ARGB_8888)
                        try {
                            Stitcher.bands(readers, plan, options.background, cancelled) { y, rows, pixels ->
                                bitmap.setPixels(pixels, 0, plan.size.width, 0, y, plan.size.width, rows)
                                progress((y + rows).toFloat() / plan.size.height)
                            }
                            if (cancelled()) throw InterruptedException("Anulowano sklejanie.")
                            check(bitmap.compress(Bitmap.CompressFormat.JPEG, options.quality.coerceIn(80, 100), output)) { "Nie udało się zakodować JPG." }
                        } finally { bitmap.recycle() }
                    }
                }
            } ?: error("Nie można zapisać pliku w wybranej lokalizacji.")
            if (target == null && Build.VERSION.SDK_INT >= 29) {
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1) { "Nie udało się zakończyć zapisu do Galerii." }
            }
            val actualName = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: fileName
            return SavedImage(uri, actualName, options.format.mime, plan.size, fileBytes,
                if (target == null) "Galeria · Pictures/Sklejka/$actualName" else uri.toString())
        } catch (e: Throwable) {
            // Never leave an incomplete public image behind after cancellation or errors.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        } finally { sources.forEach { it.close() } }
    }
}
