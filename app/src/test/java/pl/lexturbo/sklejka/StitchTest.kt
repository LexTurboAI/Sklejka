package pl.lexturbo.sklejka

import org.junit.Assert.*
import org.junit.Test
import pl.lexturbo.sklejka.core.*
import java.io.ByteArrayOutputStream
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StitchTest {
    private fun Bitmap.getRGB(x: Int, y: Int) = getPixel(x, y)
    private fun Bitmap.getRGB(x: Int, y: Int, width: Int, height: Int, unused: IntArray?, offset: Int, stride: Int): IntArray =
        IntArray(width * height).also { getPixels(it, offset, stride, x, y, width, height) }
    private val red = 0xffff0000.toInt()
    private val blue = 0xff0000ff.toInt()
    private class Source(override val size: Size, private val pixels: IntArray) : PixelSource {
        override fun read(box: Box) = IntArray(box.width * box.height) { i -> pixels[(box.y + i / box.width) * size.width + box.x + i % box.width] }
    }
    private fun solid(w: Int, h: Int, color: Int) = Source(Size(w, h), IntArray(w * h) { color })
    private fun render(sources: List<PixelSource>, direction: Direction = Direction.VERTICAL,
                       reverse: Boolean = false, gap: Int = 0, bg: Background = Background.TRANSPARENT): Bitmap {
        val plan = Layout.plan(sources.map { it.size }, direction, reverse, gap)
        val output = ByteArrayOutputStream()
        PngWriter(output, plan.size).use { png ->
            Stitcher.bands(sources, plan, bg) { _, rows, pixels -> png.write(pixels, rows) }; png.finish()
        }
        val bytes = output.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPremultiplied = false })!!
    }
    @Test fun twoVertical() {
        val image = render(listOf(solid(2, 3, red), solid(2, 4, blue)))
        assertEquals(2, image.width); assertEquals(7, image.height)
        assertEquals(red, image.getRGB(0, 2)); assertEquals(blue, image.getRGB(0, 3))
    }
    @Test fun twoHorizontal() {
        val image = render(listOf(solid(2, 3, red), solid(4, 3, blue)), Direction.HORIZONTAL)
        assertEquals(6, image.width); assertEquals(3, image.height)
        assertEquals(red, image.getRGB(1, 1)); assertEquals(blue, image.getRGB(2, 1))
    }
    @Test fun differentWidthsCenteredWithoutResize() {
        val image = render(listOf(solid(2, 2, red), solid(6, 3, blue)))
        assertEquals(6, image.width); assertEquals(5, image.height)
        assertEquals(0, image.getRGB(1, 0)); assertEquals(red, image.getRGB(2, 0))
        assertEquals(red, image.getRGB(3, 1)); assertEquals(0, image.getRGB(4, 1))
    }
    @Test fun differentHeightsCenteredWithoutResize() {
        val image = render(listOf(solid(2, 2, red), solid(3, 6, blue)), Direction.HORIZONTAL)
        assertEquals(5, image.width); assertEquals(6, image.height)
        assertEquals(0, image.getRGB(0, 1)); assertEquals(red, image.getRGB(0, 2)); assertEquals(0, image.getRGB(0, 4))
    }
    @Test fun reversedVerticalOrder() {
        val image = render(listOf(solid(2, 3, red), solid(2, 4, blue)), reverse = true)
        assertEquals(blue, image.getRGB(0, 3)); assertEquals(red, image.getRGB(0, 4))
    }
    @Test fun reversedHorizontalOrder() {
        val image = render(listOf(solid(2, 2, red), solid(3, 2, blue)), Direction.HORIZONTAL, reverse = true)
        assertEquals(blue, image.getRGB(2, 0)); assertEquals(red, image.getRGB(3, 0))
    }
    @Test fun pngPreservesAlphaAndChannels() {
        val colors = intArrayOf(0x80123456.toInt(), 0x01abcdef, 0x00112233, -1)
        val image = render(listOf(Source(Size(2, 2), colors)))
        assertArrayEquals(colors, image.getRGB(0, 0, 2, 2, null, 0, 2))
    }
    @Test fun originalPixelPositionsSurvive() {
        val colors = IntArray(35) { 0xff000000.toInt() or (it * 7919) }
        val image = render(listOf(Source(Size(7, 5), colors), solid(3, 2, blue)))
        assertArrayEquals(colors, image.getRGB(0, 0, 7, 5, null, 0, 7))
        assertEquals(7, image.width); assertEquals(7, image.height)
    }
    @Test fun dimensionsIncludeGap() {
        val sizes = listOf(Size(1440, 5000), Size(1440, 5000), Size(1440, 5000), Size(1440, 5000))
        assertEquals(Size(1440, 20000), Layout.plan(sizes, Direction.VERTICAL).size)
        assertEquals(Size(5760, 5000), Layout.plan(sizes, Direction.HORIZONTAL).size)
        assertEquals(Size(1440, 20150), Layout.plan(sizes, Direction.VERTICAL, gap = 50).size)
    }
    @Test fun gapUsesSelectedBackground() {
        val image = render(listOf(solid(1, 1, red), solid(1, 1, blue)), gap = 2, bg = Background.BLACK)
        assertEquals(4, image.height); assertEquals(0xff000000.toInt(), image.getRGB(0, 2))
    }
    @Test fun opaqueBackgroundBlendsAlpha() {
        val image = render(listOf(solid(1, 1, 0x80ff0000.toInt())), bg = Background.WHITE)
        assertEquals(0xffff7f7f.toInt(), image.getRGB(0, 0))
    }
    @Test fun allEightExifPermutations() {
        val expected = listOf(
            intArrayOf(1, 2, 3, 4, 5, 6), intArrayOf(3, 2, 1, 6, 5, 4),
            intArrayOf(6, 5, 4, 3, 2, 1), intArrayOf(4, 5, 6, 1, 2, 3),
            intArrayOf(1, 4, 2, 5, 3, 6), intArrayOf(4, 1, 5, 2, 6, 3),
            intArrayOf(6, 3, 5, 2, 4, 1), intArrayOf(3, 6, 2, 5, 1, 4))
        for (exif in 1..8) {
            val size = Orientation.size(Size(3, 2), exif)
            val pixels = IntArray(6) { i -> val (x, y) = Orientation.rawPoint(i % size.width, i / size.width, Size(3, 2), exif); y * 3 + x + 1 }
            assertArrayEquals("EXIF $exif", expected[exif - 1], pixels)
        }
    }
    @Test fun longPngUsesSmallBands() {
        val size = Size(1440, 20000)
        var maxPixels = 0; var totalRows = 0
        val source = object : PixelSource {
            override val size = size
            override fun read(box: Box) = IntArray(box.width * box.height) { red }
        }
        Stitcher.bands(listOf(source), Layout.plan(listOf(size), Direction.VERTICAL), Background.WHITE) { _, rows, pixels ->
            maxPixels = maxOf(maxPixels, pixels.size); totalRows += rows
        }
        assertEquals(20000, totalRows); assertTrue(maxPixels <= 524288)
    }
    @Test fun jpegRejectsInsufficientMemory() {
        val error = assertThrows(IllegalArgumentException::class.java) { Limits.jpeg(Size(1440, 20000), 128L * 1024 * 1024) }
        assertTrue(error.message!!.contains("1440 × 20000"))
    }
    @Test fun dimensionOverflowIsReported() {
        val error = assertThrows(IllegalArgumentException::class.java) { Layout.plan(listOf(Size(1, Int.MAX_VALUE), Size(1, 2)), Direction.VERTICAL) }
        assertTrue(error.message!!.contains("2147483649"))
    }
    @Test fun cancellationInterruptsBands() {
        assertThrows(InterruptedException::class.java) {
            Stitcher.bands(listOf(solid(1, 1, red)), Layout.plan(listOf(Size(1, 1)), Direction.VERTICAL), Background.WHITE, { true }) { _, _, _ -> fail() }
        }
    }
}
