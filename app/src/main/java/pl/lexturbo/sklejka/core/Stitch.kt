package pl.lexturbo.sklejka.core

import java.io.OutputStream
import java.io.DataOutputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

enum class Direction { VERTICAL, HORIZONTAL, GRID }
enum class Background(val argb: Int) { WHITE(-1), BLACK(0xff000000.toInt()), TRANSPARENT(0) }
enum class Format(val mime: String, val extension: String) { PNG("image/png", "png"), JPG("image/jpeg", "jpg") }
data class Size(val width: Int, val height: Int) {
    init { require(width > 0 && height > 0) }
}
data class Box(val x: Int, val y: Int, val width: Int, val height: Int)
data class Placement(val sourceIndex: Int, val box: Box)
data class Plan(val size: Size, val placements: List<Placement>)

object Layout {
    fun plan(sizes: List<Size>, direction: Direction, reverse: Boolean = false, gap: Int = 0,
             columns: Int = 3): Plan {
        require(sizes.isNotEmpty()) { "Dodaj co najmniej jedno zdjęcie." }
        require(gap in 0..50)
        val indices = if (reverse) sizes.indices.reversed().toList() else sizes.indices.toList()
        if (direction == Direction.GRID) return grid(sizes, indices, columns, gap)
        val width = if (direction == Direction.VERTICAL) sizes.maxOf { it.width }.toLong()
            else sizes.sumOf { it.width.toLong() } + gap.toLong() * (sizes.size - 1)
        val height = if (direction == Direction.HORIZONTAL) sizes.maxOf { it.height }.toLong()
            else sizes.sumOf { it.height.toLong() } + gap.toLong() * (sizes.size - 1)
        require(width <= Int.MAX_VALUE && height <= Int.MAX_VALUE) {
            "Wynik ${width} × ${height} px przekracza obsługiwany zakres wymiarów."
        }
        var cursor = 0L
        val placements = indices.map { i ->
            val s = sizes[i]
            val box = if (direction == Direction.VERTICAL)
                Box(((width - s.width) / 2).toInt(), cursor.toInt(), s.width, s.height)
            else Box(cursor.toInt(), ((height - s.height) / 2).toInt(), s.width, s.height)
            cursor += (if (direction == Direction.VERTICAL) s.height else s.width).toLong() + gap
            Placement(i, box)
        }
        return Plan(Size(width.toInt(), height.toInt()), placements)
    }

    /** Fixed-width columns, native pixels, top-left alignment and row-major list order. */
    private fun grid(sizes: List<Size>, indices: List<Int>, columns: Int, gap: Int): Plan {
        require(columns in 2..4) { "Wybierz 2, 3 lub 4 kolumny." }
        val columnWidth = sizes.maxOf { it.width }.toLong()
        val rows = indices.chunked(columns)
        val rowHeights = rows.map { row -> row.maxOf { sizes[it].height }.toLong() }
        // Reserve all selected columns, including background in an incomplete last row.
        val width = columnWidth * columns + gap.toLong() * (columns - 1)
        val height = rowHeights.sum() + gap.toLong() * (rows.size - 1)
        require(width <= Int.MAX_VALUE && height <= Int.MAX_VALUE) {
            "Wynik ${width} × ${height} px przekracza obsługiwany zakres wymiarów."
        }
        val placements = ArrayList<Placement>(sizes.size)
        var y = 0L
        rows.forEachIndexed { rowIndex, row ->
            row.forEachIndexed { column, sourceIndex ->
                val size = sizes[sourceIndex]
                placements.add(Placement(sourceIndex, Box((column * (columnWidth + gap)).toInt(),
                    y.toInt(), size.width, size.height)))
            }
            y += rowHeights[rowIndex] + gap
        }
        return Plan(Size(width.toInt(), height.toInt()), placements)
    }
}

/** Integer pixel permutation: EXIF orientation never resamples the source. */
object Orientation {
    fun size(raw: Size, exif: Int): Size = if (exif in 5..8) Size(raw.height, raw.width) else raw
    fun rawPoint(x: Int, y: Int, raw: Size, exif: Int): Pair<Int, Int> = when (exif) {
        2 -> raw.width - 1 - x to y
        3 -> raw.width - 1 - x to raw.height - 1 - y
        4 -> x to raw.height - 1 - y
        5 -> y to x
        6 -> y to raw.height - 1 - x
        7 -> raw.width - 1 - y to raw.height - 1 - x
        8 -> raw.width - 1 - y to x
        else -> x to y
    }
    fun regionIndex(x: Int, y: Int, raw: Size, exif: Int, left: Int, top: Int, stride: Int): Int {
        val rx = when (exif) { 2, 3 -> raw.width - 1 - x; 5, 6 -> y; 7, 8 -> raw.width - 1 - y; else -> x }
        val ry = when (exif) { 3, 4 -> raw.height - 1 - y; 5, 8 -> x; 6, 7 -> raw.height - 1 - x; else -> y }
        return (ry - top) * stride + rx - left
    }
}

interface PixelSource : AutoCloseable {
    val size: Size
    /** Straight-alpha ARGB pixels of a rectangle in EXIF-corrected coordinates. */
    fun read(box: Box): IntArray
    override fun close() {}
}

object Limits {
    fun png(size: Size) {
        require(size.width <= 131072) {
            "Wynik ${size.width} × ${size.height} px jest zbyt szeroki dla bezpiecznego zapisu pasami (limit szerokości: 131072 px). Zdjęcia nie zostały zmniejszone."
        }
    }
    fun jpeg(size: Size, availableBytes: Long): Long {
        val estimate = size.width.toLong() * size.height * 4 + 24L * 1024 * 1024
        require(size.width <= 65500 && size.height <= 65500 && estimate <= availableBytes * 3 / 5) {
            "Wynik ${size.width} × ${size.height} px wymaga około ${(estimate + 1048575) / 1048576} MB pamięci. JPG przekracza bezpieczny limit. Wybierz PNG (zapis pasami) albo usuń część zdjęć. Zdjęcia nie zostały zmniejszone."
        }
        return estimate
    }
}

object Stitcher {
    fun bands(sources: List<PixelSource>, plan: Plan, background: Background,
              cancelled: () -> Boolean = { false }, consume: (Int, Int, IntArray) -> Unit) {
        Limits.png(plan.size)
        // An output band uses at most 2 MiB; source patches are released between placements.
        val bandHeight = (524288 / plan.size.width).coerceIn(1, 256)
        var y = 0
        while (y < plan.size.height) {
            if (cancelled()) throw InterruptedException("Anulowano sklejanie.")
            val rows = minOf(bandHeight, plan.size.height - y)
            val pixels = IntArray(plan.size.width * rows) { background.argb }
            for (p in plan.placements) {
                val top = maxOf(y, p.box.y)
                val bottom = minOf((y + rows).toLong(), p.box.y.toLong() + p.box.height).toInt()
                if (bottom <= top) continue
                val patch = sources[p.sourceIndex].read(Box(0, top - p.box.y, p.box.width, bottom - top))
                for (row in 0 until bottom - top) {
                    val offset = (top - y + row) * plan.size.width + p.box.x
                    for (x in 0 until p.box.width) {
                        val color = patch[row * p.box.width + x]
                        pixels[offset + x] = if (background == Background.TRANSPARENT) color
                            else flatten(color, background.argb)
                    }
                }
            }
            consume(y, rows, pixels)
            y += rows
        }
    }
    private fun flatten(c: Int, bg: Int): Int {
        val a = c ushr 24
        if (a == 255) return c
        if (a == 0) return bg
        fun channel(shift: Int) = (((c ushr shift) and 255) * a + ((bg ushr shift) and 255) * (255 - a) + 127) / 255
        return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}

/** PNG RGBA8 encoder. One scanline and a 64 KiB deflate buffer, no complete output bitmap. */
class PngWriter(stream: OutputStream, private val size: Size) : AutoCloseable {
    private val output = DataOutputStream(stream)
    private val deflater = Deflater(6)
    private val compressed = ByteArray(65536)
    private val row = ByteArray(1 + size.width * 4)
    private var writtenRows = 0
    init {
        output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
        val header = ByteArrayOutputStream()
        DataOutputStream(header).use { it.writeInt(size.width); it.writeInt(size.height); it.write(byteArrayOf(8, 6, 0, 0, 0)) }
        chunk("IHDR", header.toByteArray())
    }
    fun write(pixels: IntArray, rows: Int) {
        require(pixels.size == size.width * rows && writtenRows + rows <= size.height)
        for (y in 0 until rows) {
            row[0] = 0 // no filter: source channels are preserved exactly
            for (x in 0 until size.width) {
                val c = pixels[y * size.width + x]; val i = 1 + x * 4
                row[i] = (c ushr 16).toByte(); row[i + 1] = (c ushr 8).toByte()
                row[i + 2] = c.toByte(); row[i + 3] = (c ushr 24).toByte()
            }
            deflater.setInput(row)
            while (!deflater.needsInput()) drain()
            writtenRows++
        }
    }
    fun finish() {
        check(writtenRows == size.height)
        deflater.finish()
        while (!deflater.finished()) drain()
        chunk("IEND", byteArrayOf()); output.flush()
    }
    private fun drain() {
        val n = deflater.deflate(compressed)
        if (n > 0) chunk("IDAT", compressed, n)
    }
    private fun chunk(type: String, bytes: ByteArray, count: Int = bytes.size) {
        val name = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32(); crc.update(name); crc.update(bytes, 0, count)
        output.writeInt(count); output.write(name); output.write(bytes, 0, count); output.writeInt(crc.value.toInt())
    }
    override fun close() { deflater.end() }
}
