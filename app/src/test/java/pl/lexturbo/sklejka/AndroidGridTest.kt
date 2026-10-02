package pl.lexturbo.sklejka

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import pl.lexturbo.sklejka.core.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidGridTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private fun alphaInput(): File = File.createTempFile("grid-alpha", ".png", context.cacheDir).apply {
        // Independent RGBA fixture, including hidden RGB in transparent pixels.
        writeBytes(Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAYAAACddGYaAAAAI0lEQVR4nGP4z8Dwn+E/w38Ghv//GQSVjBmETMIaVp99zwgAg1oJ5eaSGiYAAAAASUVORK5CYII="))
    }
    private fun read(file: File) = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inPremultiplied = false })!!
    private fun write(bitmap: Bitmap, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): File {
        val file = File.createTempFile("grid-source", if (format == Bitmap.CompressFormat.PNG) ".png" else ".jpg", context.cacheDir)
        file.outputStream().use { assertTrue(bitmap.compress(format, 100, it)) }
        return file
    }
    @Test fun completeTransparentPngGridPreservesSourcePixelsAndEmptyCells() {
        val photo = inspectPhoto(context, Uri.fromFile(alphaInput()))
        val target = File.createTempFile("grid-output", ".png", context.cacheDir)
        val saved = Exporter.export(context, List(3) { photo },
            ExportOptions(Direction.GRID, false, Format.PNG, Background.TRANSPARENT, 1, 100, 2), Uri.fromFile(target))
        assertEquals(Size(7, 5), saved.size); assertEquals(target.length(), saved.bytes)
        val image = read(target)
        val colors = intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0x00112233, 0x80123456.toInt(), 0x01abcdef)
        try {
            for ((x, y) in listOf(0 to 0, 4 to 0, 0 to 3)) {
                val actual = IntArray(6); image.getPixels(actual, 0, 3, x, y, 3, 2)
                assertArrayEquals(colors, actual)
            }
            for (x in 0 until 7) assertEquals(0, image.getPixel(x, 2))
            for (y in 3 until 5) for (x in 4 until 7) assertEquals(0, image.getPixel(x, y))
        } finally { image.recycle() }
    }
    @Test fun jpegGridUsesExactlyTheChosenEncoderQuality() {
        val colors = IntArray(32 * 24) { i -> 0xff000000.toInt() or ((i * 7919) and 0x00ffffff) }
        val bitmap = Bitmap.createBitmap(colors, 32, 24, Bitmap.Config.ARGB_8888)
        val file = try { write(bitmap) } finally { bitmap.recycle() }
        val photo = inspectPhoto(context, Uri.fromFile(file))
        // Independent expected canvas, including both gaps and the empty last cell.
        val expected = Bitmap.createBitmap(66, 50, Bitmap.Config.ARGB_8888)
        expected.eraseColor(Background.WHITE.argb)
        for ((x, y) in listOf(0 to 0, 34 to 0, 0 to 26)) expected.setPixels(colors, 0, 32, x, y, 32, 24)
        try {
            val sizes = mutableListOf<Long>()
            for (quality in listOf(80, 91, 100)) {
                val target = File.createTempFile("grid-quality-$quality", ".jpg", context.cacheDir)
                val saved = Exporter.export(context, List(3) { photo },
                    ExportOptions(Direction.GRID, false, Format.JPG, Background.WHITE, 2, quality, 2), Uri.fromFile(target))
                assertEquals(Size(66, 50), saved.size); assertEquals(target.length(), saved.bytes)
                val reference = ByteArrayOutputStream()
                assertTrue(expected.compress(Bitmap.CompressFormat.JPEG, quality, reference))
                assertArrayEquals("JPEG quality $quality", reference.toByteArray(), target.readBytes())
                val output = read(target)
                assertEquals(66, output.width); assertEquals(50, output.height); output.recycle()
                sizes.add(target.length())
            }
            assertTrue(sizes.last() > sizes.first())
        } finally { expected.recycle() }
    }
    @Test fun gridUsesExifCorrectedDimensionsAndPixels() {
        val bitmap = Bitmap.createBitmap(intArrayOf(0xffff0000.toInt(), -1, 0xff0000ff.toInt(), -1, 0xff00ff00.toInt(), -1), 3, 2, Bitmap.Config.ARGB_8888)
        val file = try { write(bitmap, Bitmap.CompressFormat.JPEG) } finally { bitmap.recycle() }
        ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "6"); saveAttributes() }
        val rotated = inspectPhoto(context, Uri.fromFile(file))
        val other = inspectPhoto(context, Uri.fromFile(alphaInput()))
        val target = File.createTempFile("grid-exif", ".png", context.cacheDir)
        val saved = Exporter.export(context, listOf(rotated, other),
            ExportOptions(Direction.GRID, false, Format.PNG, Background.TRANSPARENT, 0, 100, 2), Uri.fromFile(target))
        assertEquals(Size(6, 3), saved.size)
        val image = read(target); val raw = read(file)
        try {
            for (y in 0 until 3) for (x in 0 until 2) {
                val (rx, ry) = Orientation.rawPoint(x, y, Size(3, 2), 6)
                assertEquals(raw.getPixel(rx, ry), image.getPixel(x, y))
            }
            assertEquals(0, image.getPixel(2, 0)); assertEquals(0, image.getPixel(3, 2))
        } finally { image.recycle(); raw.recycle() }
    }
    @Test fun fourLargeScreenshotsExportAsNativeGridWithGaps() {
        val bitmap = Bitmap.createBitmap(1440, 5000, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff123456.toInt())
        val file = try { write(bitmap) } finally { bitmap.recycle() }
        val photo = inspectPhoto(context, Uri.fromFile(file))
        val target = File.createTempFile("grid-large", ".png", context.cacheDir)
        val saved = Exporter.export(context, List(4) { photo },
            ExportOptions(Direction.GRID, false, Format.PNG, Background.BLACK, 4, 100, 2), Uri.fromFile(target))
        assertEquals(Size(2884, 10004), saved.size); assertEquals(target.length(), saved.bytes)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.absolutePath, bounds)
        assertEquals(2884, bounds.outWidth); assertEquals(10004, bounds.outHeight)
        AndroidPixelSource(context, Photo("result", Uri.fromFile(target), "result", saved.size, 1)).use { source ->
            for ((x, y) in listOf(1439 to 4999, 2883 to 4999, 1439 to 10003, 2883 to 10003))
                assertEquals(0xff123456.toInt(), source.read(Box(x, y, 1, 1))[0])
            assertEquals(Background.BLACK.argb, source.read(Box(1440, 0, 1, 1))[0])
            assertEquals(Background.BLACK.argb, source.read(Box(0, 5000, 1, 1))[0])
        }
    }
    @Test fun columnSelectionUpdatesDimensionsAndSurvivesStateRestoration() {
        val state = SavedStateHandle()
        val model = SklejkaModel(context, state)
        model.direction(Direction.GRID); model.columns(2); model.gap(2)
        model.format(Format.JPG); model.background(Background.BLACK); model.quality(91)
        val restored = SklejkaModel(context, state)
        assertEquals(model.options(), restored.options())
        val photo = inspectPhoto(context, Uri.fromFile(alphaInput()))
        restored.photos.addAll(List(6) { photo.copy(id = it.toString()) })
        val expected = mapOf(2 to Size(8, 10), 3 to Size(13, 6), 4 to Size(18, 6))
        for ((columns, size) in expected) {
            restored.columns(columns)
            val options = restored.options()
            assertEquals(columns, options.columns)
            assertEquals(size, Layout.plan(restored.photos.map { it.size }, options.direction, options.reverse, options.gap, options.columns).size)
        }
        restored.direction(Direction.VERTICAL)
        assertEquals(Size(3, 22), Layout.plan(restored.photos.map { it.size }, restored.direction, gap = restored.gap, columns = restored.columns).size)
        restored.direction(Direction.HORIZONTAL)
        assertEquals(Size(28, 2), Layout.plan(restored.photos.map { it.size }, restored.direction, gap = restored.gap, columns = restored.columns).size)
    }
    @Test fun existingReorderReverseRemoveAndClearActionsWorkInGrid() {
        val model = SklejkaModel(context, SavedStateHandle())
        val photo = inspectPhoto(context, Uri.fromFile(alphaInput()))
        model.photos.addAll(listOf(1, 2, 3, 4, 5, 7).map { photo.copy(id = it.toString()) })
        model.direction(Direction.GRID); model.columns(3)
        // The same model.move() is used by both drag gestures and arrow buttons.
        model.move("5", 0); model.move("2", 1); model.move("7", 3); model.move("4", 4)
        val plan = Layout.plan(model.photos.map { it.size }, model.direction, columns = model.columns)
        assertEquals(listOf("5", "2", "1", "7", "4", "3"), plan.placements.map { model.photos[it.sourceIndex].id })
        assertEquals(listOf(0 to 0, 3 to 0, 6 to 0, 0 to 2, 3 to 2, 6 to 2), plan.placements.map { it.box.x to it.box.y })
        model.reverse(); assertEquals(listOf("3", "4", "7", "1", "2", "5"), model.photos.map { it.id })
        model.remove("7"); assertEquals(listOf("3", "4", "1", "2", "5"), model.photos.map { it.id })
        model.clear(); assertTrue(model.photos.isEmpty())
    }
}
