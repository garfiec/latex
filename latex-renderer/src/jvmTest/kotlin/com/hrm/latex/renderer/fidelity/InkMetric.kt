/*
 * Copyright (c) 2026 huarangmeng
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.hrm.latex.renderer.fidelity

import java.awt.image.BufferedImage
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Port of RaTeX's `ratex-ink-v2` score (tools/golden_compare/compare_golden.py, MIT), so scores
 * read on the same scale as RaTeX's published golden results.
 *
 * Both images are cropped to their ink, scaled to [NORM_HEIGHT] px tall with PIL's Lanczos
 * resampling, and aligned by the shift that maximises shared ink. The score is
 * `0.4 IoU + 0.2 recall + 0.2 aspect similarity + 0.2 width similarity`.
 */
internal object InkMetric {
    const val NORM_HEIGHT = 120
    private const val INK_THRESHOLD = 240
    private const val PROOFTREE_TOLERANCE_PX = 20

    data class Score(
        val score: Double,
        val iou: Double,
        val recall: Double,
        val precision: Double,
        val aspectSimilarity: Double,
        val widthSimilarity: Double,
    )

    /** An RGB raster, one `0xRRGGBB` int per pixel. */
    class Raster(val width: Int, val height: Int, val pixels: IntArray) {
        fun channel(x: Int, y: Int, shift: Int) = (pixels[y * width + x] shr shift) and 0xFF
        fun isInk(x: Int, y: Int): Boolean {
            val p = pixels[y * width + x]
            return (p shr 16 and 0xFF) < INK_THRESHOLD || (p shr 8 and 0xFF) < INK_THRESHOLD || (p and 0xFF) < INK_THRESHOLD
        }

        companion object {
            /** Composites any alpha over white, as `Image.convert("RGB")` sees an opaque PNG. */
            fun of(image: BufferedImage): Raster {
                val pixels = IntArray(image.width * image.height)
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    val argb = image.getRGB(x, y)
                    val a = argb ushr 24
                    pixels[y * image.width + x] = if (a == 255) argb and 0xFFFFFF else {
                        fun over(c: Int) = (c * a + 255 * (255 - a) + 127) / 255
                        (over(argb shr 16 and 0xFF) shl 16) or (over(argb shr 8 and 0xFF) shl 8) or over(argb and 0xFF)
                    }
                }
                return Raster(image.width, image.height, pixels)
            }
        }
    }

    fun score(reference: Raster, test: Raster, prooftreeTolerant: Boolean = false): Score {
        val refCrop = crop(reference)
        val testCrop = crop(test)
        val refNorm = normalize(refCrop)
        val testNorm = normalize(testCrop)
        val width = max(refNorm.width, testNorm.width)
        val refInk = InkMask.of(refNorm, width)
        val testInk = InkMask.of(testNorm, width)
        if (refInk.count() == 0 && testInk.count() == 0) return Score(1.0, 1.0, 1.0, 1.0, 1.0, 1.0)

        val aligned = bestAlignment(refInk, testInk, NORM_HEIGHT / 8, width / 16)
        val refCount = refInk.count()
        val testCount = aligned.count()
        val intersection = refInk.intersect(aligned)
        val union = refCount + testCount - intersection
        val iou = if (union > 0) intersection.toDouble() / union else 1.0
        val precision = if (testCount > 0) intersection.toDouble() / testCount else 0.0
        val recall = if (refCount > 0) intersection.toDouble() / refCount else 0.0
        val refAspect = refCrop.width.toDouble() / refCrop.height
        val testAspect = testCrop.width.toDouble() / testCrop.height
        val aspect = min(refAspect, testAspect) / max(refAspect, testAspect)
        val widthSimilarity = min(refNorm.width, testNorm.width).toDouble() / max(refNorm.width, testNorm.width)

        var score = 0.4 * iou + 0.2 * recall + 0.2 * aspect + 0.2 * widthSimilarity
        if (prooftreeTolerant) {
            val tolerantRecall = if (refCount > 0) refInk.intersect(aligned.dilate(PROOFTREE_TOLERANCE_PX)).toDouble() / refCount else 0.0
            val tolerantPrecision = if (testCount > 0) aligned.intersect(refInk.dilate(PROOFTREE_TOLERANCE_PX)).toDouble() / testCount else 0.0
            val tolerantF1 = if (tolerantPrecision + tolerantRecall > 0) {
                2 * tolerantPrecision * tolerantRecall / (tolerantPrecision + tolerantRecall)
            } else 0.0
            score = max(score, 0.5 * tolerantF1 + 0.25 * aspect + 0.25 * widthSimilarity)
        }
        return Score(score, iou, recall, precision, aspect, widthSimilarity)
    }

    /** Ink bounding box plus a 2 px margin; the top-left 10×10 when there is no ink. */
    private fun crop(image: Raster, margin: Int = 2): Raster {
        var top = -1; var bottom = -1; var left = Int.MAX_VALUE; var right = -1
        for (y in 0 until image.height) for (x in 0 until image.width) {
            if (!image.isInk(x, y)) continue
            if (top < 0) top = y
            bottom = y
            left = min(left, x)
            right = max(right, x)
        }
        if (top < 0) return image.region(0, 0, min(10, image.width), min(10, image.height))
        val x0 = max(0, left - margin)
        val y0 = max(0, top - margin)
        return image.region(x0, y0, min(image.width - 1, right + margin) - x0 + 1, min(image.height - 1, bottom + margin) - y0 + 1)
    }

    private fun Raster.region(x0: Int, y0: Int, w: Int, h: Int): Raster {
        val out = IntArray(w * h)
        for (y in 0 until h) System.arraycopy(pixels, (y0 + y) * width + x0, out, y * w, w)
        return Raster(w, h, out)
    }

    private fun normalize(image: Raster): Raster {
        if (image.width == 0 || image.height == 0) {
            return Raster(NORM_HEIGHT, NORM_HEIGHT, IntArray(NORM_HEIGHT * NORM_HEIGHT) { 0xFFFFFF })
        }
        val newWidth = max(1, (image.width * (NORM_HEIGHT.toDouble() / image.height)).toInt())
        return Lanczos.resize(image, newWidth, NORM_HEIGHT)
    }

    /** PIL's search order (dy outer, dx inner, first maximum wins), so ties pick the same shift. */
    private fun bestAlignment(ref: InkMask, test: InkMask, maxDy: Int, maxDx: Int): InkMask {
        val shiftedByDx = (-maxDx..maxDx).map { test.shiftColumns(it) }
        var best = -1
        var bestDy = 0
        var bestDx = 0
        for (dy in -maxDy..maxDy) {
            for ((i, dx) in (-maxDx..maxDx).withIndex()) {
                val intersection = ref.intersectShiftedRows(shiftedByDx[i], dy)
                if (intersection > best) { best = intersection; bestDy = dy; bestDx = dx }
            }
        }
        return test.shiftColumns(bestDx).shiftRows(bestDy)
    }
}

/** A boolean ink mask packed into 64-bit words per row; `shifted[y][x] = source[y - dy][x - dx]`. */
internal class InkMask(val width: Int, val height: Int, private val rows: Array<LongArray>) {
    private val words = (width + 63) / 64

    fun count(): Int = rows.sumOf { row -> row.sumOf { java.lang.Long.bitCount(it) } }

    fun intersect(other: InkMask): Int = intersectShiftedRows(other, 0)

    fun intersectShiftedRows(other: InkMask, dy: Int): Int {
        var total = 0
        for (y in max(0, dy) until min(height, height + dy)) {
            val a = rows[y]
            val b = other.rows[y - dy]
            for (w in 0 until words) total += java.lang.Long.bitCount(a[w] and b[w])
        }
        return total
    }

    fun shiftRows(dy: Int): InkMask = InkMask(width, height, Array(height) { y ->
        val source = y - dy
        if (source in 0 until height) rows[source].copyOf() else LongArray(words)
    })

    fun shiftColumns(dx: Int): InkMask = InkMask(width, height, Array(height) { y ->
        val out = LongArray(words)
        val row = rows[y]
        for (x in 0 until width) {
            val source = x - dx
            if (source in 0 until width && row[source ushr 6] ushr (source and 63) and 1L != 0L) {
                out[x ushr 6] = out[x ushr 6] or (1L shl (x and 63))
            }
        }
        out
    })

    /** Square max filter of side `2 * radius + 1`, like PIL's `MaxFilter`. */
    fun dilate(radius: Int): InkMask {
        val horizontal = Array(height) { y ->
            val out = LongArray(words)
            for (x in 0 until width) {
                if (!get(y, x)) continue
                for (t in max(0, x - radius)..min(width - 1, x + radius)) out[t ushr 6] = out[t ushr 6] or (1L shl (t and 63))
            }
            out
        }
        return InkMask(width, height, Array(height) { y ->
            val out = LongArray(words)
            for (s in max(0, y - radius)..min(height - 1, y + radius)) {
                for (w in 0 until words) out[w] = out[w] or horizontal[s][w]
            }
            out
        })
    }

    private fun get(y: Int, x: Int) = rows[y][x ushr 6] ushr (x and 63) and 1L != 0L

    companion object {
        /** White-padded on the right to [width] columns, or truncated to it. */
        fun of(image: InkMetric.Raster, width: Int): InkMask {
            val words = (width + 63) / 64
            return InkMask(width, image.height, Array(image.height) { y ->
                val row = LongArray(words)
                for (x in 0 until min(width, image.width)) {
                    if (image.isInk(x, y)) row[x ushr 6] = row[x ushr 6] or (1L shl (x and 63))
                }
                row
            })
        }
    }
}

/** PIL's `Image.resize(..., LANCZOS)`: separable, antialiased when downscaling, horizontal pass first. */
private object Lanczos {
    private const val SUPPORT = 3.0

    private fun kernel(x: Double): Double {
        if (x <= -SUPPORT || x >= SUPPORT) return 0.0
        return sinc(x) * sinc(x / SUPPORT)
    }

    private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

    private class Coefficients(val start: IntArray, val weights: Array<DoubleArray>)

    private fun coefficients(inSize: Int, outSize: Int): Coefficients {
        val scale = inSize.toDouble() / outSize
        val filterScale = max(scale, 1.0)
        val support = SUPPORT * filterScale
        val start = IntArray(outSize)
        val weights = Array(outSize) { out ->
            val center = (out + 0.5) * scale
            val xMin = max((center - support + 0.5).toInt(), 0)
            val xMax = min((center + support + 0.5).toInt(), inSize)
            start[out] = xMin
            val w = DoubleArray(xMax - xMin) { kernel((it + xMin - center + 0.5) / filterScale) }
            val sum = w.sum()
            if (sum != 0.0) for (i in w.indices) w[i] /= sum
            w
        }
        return Coefficients(start, weights)
    }

    private fun clip(value: Double): Int = value.roundToInt().coerceIn(0, 255)

    fun resize(image: InkMetric.Raster, outWidth: Int, outHeight: Int): InkMetric.Raster {
        var current = image
        if (outWidth != image.width) {
            val c = coefficients(image.width, outWidth)
            val out = IntArray(outWidth * current.height)
            for (y in 0 until current.height) for (x in 0 until outWidth) {
                out[y * outWidth + x] = pixel { shift ->
                    var acc = 0.0
                    val w = c.weights[x]
                    for (i in w.indices) acc += w[i] * current.channel(c.start[x] + i, y, shift)
                    clip(acc)
                }
            }
            current = InkMetric.Raster(outWidth, current.height, out)
        }
        if (outHeight != current.height) {
            val c = coefficients(current.height, outHeight)
            val out = IntArray(current.width * outHeight)
            for (y in 0 until outHeight) for (x in 0 until current.width) {
                out[y * current.width + x] = pixel { shift ->
                    var acc = 0.0
                    val w = c.weights[y]
                    for (i in w.indices) acc += w[i] * current.channel(x, c.start[y] + i, shift)
                    clip(acc)
                }
            }
            current = InkMetric.Raster(current.width, outHeight, out)
        }
        return current
    }

    private inline fun pixel(channel: (shift: Int) -> Int): Int =
        (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}
