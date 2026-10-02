package pl.lexturbo.sklejka

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowLooper
import androidx.lifecycle.ViewModelProvider
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import pl.lexturbo.sklejka.core.*
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidImagesTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val colors = intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0x00112233, 0x80123456.toInt(), 0x01abcdef)
    private fun input(): File {
        // Independent fixture encoded by Pillow, including hidden RGB at zero alpha.
        val bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAYAAACddGYaAAAAI0lEQVR4nGP4z8Dwn+E/w38Ghv//GQSVjBmETMIaVp99zwgAg1oJ5eaSGiYAAAAASUVORK5CYII=")
        return File.createTempFile("source", ".png", context.cacheDir).apply { writeBytes(bytes) }
    }
    private fun read(file: File) = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inPremultiplied = false })!!
    @Test fun nativeRegionReadPreservesPngPixelsAndAlpha() {
        val photo = Photo("1", Uri.fromFile(input()), "alpha", Size(3, 2), 1)
        AndroidPixelSource(context, photo).use { source -> assertArrayEquals(colors, source.read(Box(0, 0, 3, 2))) }
    }
    @Test fun androidDecoderHonorsAllOrientationMappings() {
        val file = input()
        for (exif in 1..8) {
            val photo = Photo("1", Uri.fromFile(file), "source", Size(3, 2), exif)
            AndroidPixelSource(context, photo).use { source ->
                val pixels = source.read(Box(0, 0, source.size.width, source.size.height))
                for (i in pixels.indices) {
                    val (x, y) = Orientation.rawPoint(i % source.size.width, i / source.size.width, photo.raw, exif)
                    assertEquals("EXIF $exif", colors[y * 3 + x], pixels[i])
                }
                assertEquals(pixels[0], source.read(Box(0, 0, 1, 1))[0])
            }
        }
    }
    @Test fun actualJpegExifIsReadAndExportedUpright() {
        val file = File.createTempFile("exif", ".jpg", context.cacheDir)
        val bitmap = Bitmap.createBitmap(intArrayOf(0xffff0000.toInt(), -1, 0xff0000ff.toInt(), -1, 0xff00ff00.toInt(), -1), 3, 2, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) }; bitmap.recycle()
        ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "6"); saveAttributes() }
        val photo = inspectPhoto(context, Uri.fromFile(file))
        assertEquals(6, photo.orientation); assertEquals(Size(2, 3), photo.size)
        val target = File.createTempFile("output", ".png", context.cacheDir)
        Exporter.export(context, listOf(photo), ExportOptions(Direction.VERTICAL, false, Format.PNG, Background.TRANSPARENT, 0, 100), Uri.fromFile(target))
        val output = read(target)
        assertEquals(2, output.width); assertEquals(3, output.height)
        val raw = read(file)
        for (y in 0 until 3) for (x in 0 until 2) {
            val (rx, ry) = Orientation.rawPoint(x, y, Size(3, 2), 6)
            assertEquals(raw.getPixel(rx, ry), output.getPixel(x, y))
        }
        raw.recycle(); output.recycle()
    }
    @Test fun completePngExportKeepsNativeDimensions() {
        val photo = Photo("1", Uri.fromFile(input()), "source", Size(3, 2), 1)
        val target = File.createTempFile("output", ".png", context.cacheDir)
        val saved = Exporter.export(context, listOf(photo, photo), ExportOptions(Direction.VERTICAL, false, Format.PNG, Background.TRANSPARENT, 0, 100), Uri.fromFile(target))
        val output = read(target)
        assertEquals(3, output.width); assertEquals(4, output.height)
        val actual = IntArray(6); output.getPixels(actual, 0, 3, 0, 2, 3, 2)
        assertArrayEquals(colors, actual); assertEquals(target.length(), saved.bytes)
        output.recycle()
    }
    @Test fun jpegExportsWithoutResize() {
        val photo = Photo("1", Uri.fromFile(input()), "source", Size(3, 2), 1)
        val target = File.createTempFile("output", ".jpg", context.cacheDir)
        Exporter.export(context, listOf(photo, photo), ExportOptions(Direction.HORIZONTAL, false, Format.JPG, Background.WHITE, 0, 100), Uri.fromFile(target))
        val output = read(target)
        assertEquals(6, output.width); assertEquals(2, output.height); output.recycle()
    }
    @Test fun fourLargeScreenshotsExportAsNative1440By20000Png() {
        val file = File.createTempFile("large", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(1440, 5000, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff123456.toInt())
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val photo = inspectPhoto(context, Uri.fromFile(file))
        val target = File.createTempFile("large-output", ".png", context.cacheDir)
        val saved = Exporter.export(context, List(4) { photo }, ExportOptions(Direction.VERTICAL, false, Format.PNG, Background.WHITE, 0, 100), Uri.fromFile(target))
        assertEquals(Size(1440, 20000), saved.size)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.absolutePath, bounds)
        assertEquals(1440, bounds.outWidth); assertEquals(20000, bounds.outHeight)
        AndroidPixelSource(context, Photo("result", Uri.fromFile(target), "result", saved.size, 1)).use { source ->
            assertEquals(0xff123456.toInt(), source.read(Box(1439, 19999, 1, 1))[0])
        }
        assertEquals(target.length(), saved.bytes)
    }
    @Test fun manifestHasNoInternetAndAcceptsSharing() {
        val pkg = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertFalse(pkg.requestedPermissions.orEmpty().contains("android.permission.INTERNET"))
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            val matches = context.packageManager.queryIntentActivities(Intent(action).setType("image/png"), 0)
            assertTrue(matches.any { it.activityInfo.name == MainActivity::class.java.name })
        }
    }
    private fun assertShared(intent: Intent, expected: List<Uri>) {
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        try {
            val model = ViewModelProvider(controller.get())[SklejkaModel::class.java]
            val deadline = System.nanoTime() + 5_000_000_000L
            while (model.photos.size != expected.size && System.nanoTime() < deadline) {
                ShadowLooper.idleMainLooper(); Thread.sleep(10)
            }
            assertNull(model.error)
            assertEquals(expected, model.photos.map { it.uri })
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun singleShareActuallyLoadsIntoList() {
        val uri = Uri.fromFile(input())
        assertShared(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri), listOf(uri))
    }
    @Test fun multipleSharePreservesOrderAndRepeatedPhotos() {
        val uris = arrayListOf(Uri.fromFile(input()), Uri.fromFile(input()))
        uris.add(uris.first())
        assertShared(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris), uris)
    }
}
