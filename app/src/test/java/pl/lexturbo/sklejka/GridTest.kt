package pl.lexturbo.sklejka

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import pl.lexturbo.sklejka.core.*
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GridTest {
    private val red = 0xffff0000.toInt()
    private val blue = 0xff0000ff.toInt()
    private class Source(override val size: Size, val pixels: IntArray) : PixelSource {
        override fun read(box: Box): IntArray {
            require(box.x >= 0 && box.y >= 0 && box.x + box.width <= size.width && box.y + box.height <= size.height)
            return IntArray(box.width * box.height) { i ->
                pixels[(box.y + i / box.width) * size.width + box.x + i % box.width]
            }
        }
    }
    private fun solid(width: Int, height: Int, color: Int) = Source(Size(width, height), IntArray(width * height) { color })
    private fun render(sources: List<PixelSource>, columns: Int, gap: Int = 0,
                       background: Background = Background.TRANSPARENT, reverse: Boolean = false): Bitmap {
        val plan = Layout.plan(sources.map { it.size }, Direction.GRID, reverse, gap, columns)
        val stream = ByteArrayOutputStream()
        PngWriter(stream, plan.size).use { png ->
            Stitcher.bands(sources, plan, background) { _, rows, pixels -> png.write(pixels, rows) }
            png.finish()
        }
        val bytes = stream.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPremultiplied = false })!!
    }
    @Test fun fourPhotosInTwoColumns() {
        val colors = intArrayOf(red, blue, -1, 0xff00ff00.toInt())
        val image = render(colors.map { solid(2, 3, it) }, 2)
        try {
            assertEquals(4, image.width); assertEquals(6, image.height)
            assertEquals(colors[0], image.getPixel(0, 0)); assertEquals(colors[1], image.getPixel(2, 0))
            assertEquals(colors[2], image.getPixel(0, 3)); assertEquals(colors[3], image.getPixel(3, 5))
        } finally { image.recycle() }
    }
    @Test fun sixPhotosInThreeColumns() {
        val colors = (1..6).map { 0xff000000.toInt() or it }
        val image = render(colors.map { solid(2, 3, it) }, 3)
        try {
            assertEquals(6, image.width); assertEquals(6, image.height)
            colors.forEachIndexed { i, color -> assertEquals(color, image.getPixel((i % 3) * 2 + 1, (i / 3) * 3 + 2)) }
        } finally { image.recycle() }
    }
    @Test fun incompleteLastRowStaysOnLeftAndEmptyCellsStayTransparent() {
        val image = render(List(7) { solid(2, 1, red) }, 3)
        try {
            assertEquals(6, image.width); assertEquals(3, image.height)
            assertEquals(red, image.getPixel(0, 2)); assertEquals(red, image.getPixel(1, 2))
            for (x in 2 until 6) assertEquals(0, image.getPixel(x, 2))
        } finally { image.recycle() }
    }
    @Test fun gapsAndVariableRowHeightsUseSelectedBackground() {
        for (background in listOf(Background.WHITE, Background.BLACK)) {
            val image = render(listOf(solid(2, 4, red), solid(4, 2, blue), solid(1, 3, blue), solid(3, 1, red)), 2, 2, background)
            try {
                assertEquals(10, image.width); assertEquals(9, image.height)
                assertEquals(red, image.getPixel(0, 3)); assertEquals(blue, image.getPixel(6, 0))
                assertEquals(blue, image.getPixel(0, 8)); assertEquals(red, image.getPixel(6, 6))
                for (y in 0 until 9) for (x in 4..5) assertEquals(background.argb, image.getPixel(x, y))
                for (y in 4..5) for (x in 0 until 10) assertEquals(background.argb, image.getPixel(x, y))
                assertEquals(background.argb, image.getPixel(6, 2)) // shorter image aligned to top
                assertEquals(background.argb, image.getPixel(2, 0)) // narrower image keeps native width
            } finally { image.recycle() }
        }
    }
    @Test fun differentAspectRatiosKeepEveryPixelWithoutCropStretchOrUpscale() {
        val sources = listOf(Size(2, 4), Size(6, 2), Size(3, 3)).mapIndexed { index, size ->
            Source(size, IntArray(size.width * size.height) { 0xff000000.toInt() or ((index + 1) shl 16) or it })
        }
        val image = render(sources, 2)
        try {
            assertEquals(12, image.width); assertEquals(7, image.height)
            val origins = listOf(0 to 0, 6 to 0, 0 to 4)
            sources.forEachIndexed { i, source ->
                val (x, y) = origins[i]
                val actual = IntArray(source.pixels.size)
                image.getPixels(actual, 0, source.size.width, x, y, source.size.width, source.size.height)
                assertArrayEquals(source.pixels, actual)
            }
            assertEquals(0, image.getPixel(2, 0)); assertEquals(0, image.getPixel(6, 2))
            assertEquals(0, image.getPixel(3, 4)); assertEquals(0, image.getPixel(6, 4))
        } finally { image.recycle() }
    }
    @Test fun manuallyOrderedListAndReverseDetermineGridPositions() {
        val order = listOf(5, 2, 1, 7, 4, 3)
        val sources = order.map { solid(1, 1, 0xff000000.toInt() or it) }
        for (reverse in listOf(false, true)) {
            val image = render(sources, 3, reverse = reverse)
            try {
                val expected = if (reverse) order.reversed() else order
                expected.forEachIndexed { i, number -> assertEquals(0xff000000.toInt() or number, image.getPixel(i % 3, i / 3)) }
            } finally { image.recycle() }
        }
    }
    @Test fun transparentGridPreservesAlphaAndHiddenRgb() {
        val colors = intArrayOf(0x80123456.toInt(), 0x00112233)
        val image = render(listOf(Source(Size(2, 1), colors), solid(1, 2, blue)), 2, 1)
        try {
            assertEquals(5, image.width); assertEquals(2, image.height)
            assertEquals(colors[0], image.getPixel(0, 0)); assertEquals(colors[1], image.getPixel(1, 0))
            assertEquals(0, image.getPixel(0, 1)); assertEquals(0, image.getPixel(2, 0))
            assertEquals(blue, image.getPixel(3, 1)); assertEquals(0, image.getPixel(4, 1))
        } finally { image.recycle() }
    }
    @Test fun twelveLargeScreenshotsStillUseBoundedBandsAndSourcePatches() {
        val size = Size(1440, 5000)
        var largestPatch = 0; var largestBand = 0; var totalRows = 0
        val source = object : PixelSource {
            override val size = size
            override fun read(box: Box): IntArray {
                assertTrue(box.y >= 0 && box.y + box.height <= size.height)
                largestPatch = maxOf(largestPatch, box.width * box.height)
                return IntArray(box.width * box.height) { red }
            }
        }
        val sources = List(12) { source }
        val plan = Layout.plan(sources.map { it.size }, Direction.GRID, columns = 3)
        assertEquals(Size(4320, 20000), plan.size)
        Stitcher.bands(sources, plan, Background.WHITE) { _, rows, pixels ->
            largestBand = maxOf(largestBand, pixels.size); totalRows += rows
        }
        assertEquals(20000, totalRows)
        assertTrue(largestBand <= 524288); assertTrue(largestPatch <= 524288)
    }
    @Test fun gridDimensionOverflowIsReportedBeforeRendering() {
        val widthError = assertThrows(IllegalArgumentException::class.java) {
            Layout.plan(listOf(Size(Int.MAX_VALUE, 1)), Direction.GRID, columns = 2)
        }
        assertTrue(widthError.message!!.contains("4294967294 × 1"))
        val heightError = assertThrows(IllegalArgumentException::class.java) {
            Layout.plan(List(5) { Size(1, Int.MAX_VALUE) }, Direction.GRID, columns = 2)
        }
        assertTrue(heightError.message!!.contains("2 × 6442450941"))
    }
    @Test fun unsupportedColumnCountIsRejectedOnlyForGrid() {
        for (columns in listOf(1, 5)) {
            assertThrows(IllegalArgumentException::class.java) { Layout.plan(listOf(Size(1, 1)), Direction.GRID, columns = columns) }
            assertEquals(Size(1, 1), Layout.plan(listOf(Size(1, 1)), Direction.VERTICAL, columns = columns).size)
            assertEquals(Size(1, 1), Layout.plan(listOf(Size(1, 1)), Direction.HORIZONTAL, columns = columns).size)
        }
    }
}
