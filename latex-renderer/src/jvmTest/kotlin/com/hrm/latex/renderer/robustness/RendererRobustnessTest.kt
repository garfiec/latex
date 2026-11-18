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


package com.hrm.latex.renderer.robustness

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.hrm.latex.parser.LatexParser
import com.hrm.latex.parser.model.LatexNode
import com.hrm.latex.renderer.font.loadKatexFonts
import com.hrm.latex.renderer.layout.LatexRenderer
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.toContext
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The whole pipeline (parse, measure, draw) on a 512 KiB stack, the default for an iOS secondary
 * thread, for the RaTeX corpus in `test-corpus/ratex`, deep input and seeded random input.
 */
class RendererRobustnessTest {

    @Test
    fun everyCorpusFormulaRenders() {
        val failures = sweep(corpus(ALL_FILES)) { render(it) }
        assertTrue(failures.isEmpty(), report(failures))
    }

    @Test
    fun everyStructureRendersAtTheAcceptedDepth() {
        val failures = sweep(families(ACCEPTED_DEPTH)) { input ->
            val document = render(input)
            check(document.children.none { it is LatexNode.Error }) { "rejected" }
        }
        assertTrue(failures.isEmpty(), report(failures))
    }

    /** Spans the deepest tree the parser returns, where the renderer's stack is fullest. */
    @Test
    fun everyStructureRendersAroundTheDepthLimit() {
        val failures = sweep((ACCEPTED_DEPTH + 1..130).flatMap { families(it) }) { render(it) }
        assertTrue(failures.isEmpty(), report(failures))
    }

    @Test
    fun adversarialDepthRendersItsSourceAsAnError() {
        val inputs = listOf(300, 4_200).flatMap { families(it) }
        val failures = sweep(inputs) { input ->
            val document = LatexParser().parse(input)
            assertIs<LatexNode.Error>(document.children.single())
            val layout = LatexRenderer.measure(document.children, context, measurer(), density).layout
            check(layout.width > 0f && layout.height > 0f) { "the source should be shown" }
        }
        assertTrue(failures.isEmpty(), report(failures))
    }

    /** A lone surrogate (a streamed prefix cut inside a pair) must not reach Skia's glyph lookup. */
    @Test
    fun loneSurrogatesRender() {
        val inputs = listOf(
            "\uD835", "\uDD04", "x\uD835y", "\\mathbb{\uD835}", "\\frac{\uDD04}{2}", "\\text{\uD835}",
            "\\verb|\uD835|", "\\hat{\uD835}", "\\text{\\'\uD835}", "\\sum_{\uDD04}", "\\left\uD835 x\\right\uDD04",
            "\\operatorname{\uD835}", "\\sqrt[\uD835]{\uDD04}", "\uD835\uD835\uDD04",
        ).map { "surrogate" to it }
        val failures = sweep(inputs) { render(it) }
        assertTrue(failures.isEmpty(), report(failures))
    }

    @Test
    fun randomInputRenders() {
        val random = Random(42)
        val formulas = corpus(ALL_FILES).map { it.second }
        val inputs = List(ITERATIONS) {
            val text = StringBuilder(formulas[random.nextInt(formulas.size)])
            repeat(1 + random.nextInt(4)) {
                val at = random.nextInt(text.length + 1)
                when (random.nextInt(3)) {
                    0 -> if (at < text.length) text.deleteAt(at)
                    1 -> text.insert(at, TOKENS[random.nextInt(TOKENS.size)])
                    else -> text.insert(at, formulas[random.nextInt(formulas.size)])
                }
            }
            "random" to text.toString()
        }
        val failures = sweep(inputs) { render(it) }
        assertTrue(failures.isEmpty(), report(failures))
    }

    private fun measurer() = TextMeasurer(createFontFamilyResolver(), density, LayoutDirection.Ltr)

    private fun render(input: String): LatexNode.Document {
        val document = LatexParser().parse(input)
        val result = LatexRenderer.measure(document.children, context, measurer(), density)
        val bitmap = ImageBitmap(CANVAS, CANVAS)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(CANVAS.toFloat(), CANVAS.toFloat())) {
            with(LatexRenderer) { draw(result) }
        }
        return document
    }

    private data class Failure(val source: String, val input: String, val problem: String)

    /**
     * Runs every input on one 512 KiB-stack thread, catching per input. [slowMs] catches hangs and
     * blow-ups, so it leaves room for font loading, a GC pause or a busy CI machine.
     */
    private fun sweep(
        inputs: List<Pair<String, String>>,
        slowMs: Long = 10_000,
        check: (String) -> Unit,
    ): List<Failure> {
        val failures = mutableListOf<Failure>()
        val thread = Thread(null, {
            for ((source, input) in inputs) {
                val start = System.nanoTime()
                try {
                    check(input)
                } catch (t: Throwable) {
                    failures += Failure(source, input, "${t::class.simpleName}: ${t.message}")
                    continue
                }
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                if (elapsedMs > slowMs) failures += Failure(source, input, "took $elapsedMs ms")
            }
        }, "small-stack", 512L * 1024)
        thread.start()
        thread.join()
        return failures
    }

    private fun report(failures: List<Failure>) = "${failures.size} failing inputs:\n" +
        failures.take(25).joinToString("\n") { "[${it.source}] ${it.problem.take(160)} <- ${it.input.take(120)}" }

    internal companion object {
        const val ACCEPTED_DEPTH = 32
        const val CANVAS = 64
        val ITERATIONS = System.getProperty("latex.fuzz.iterations")?.toIntOrNull() ?: 3_000
        val ALL_FILES = listOf(
            "main.txt", "parser.txt", "mhchem.txt", "centernot.txt", "prooftree.txt", "slashed.txt", "unit-inputs.txt",
        )
        val TOKENS = listOf(
            "{", "}", "^", "_", "&", "\\\\", "\\frac", "\\sqrt", "\\left(", "\\right)", "\\begin{matrix}",
            "\\end{matrix}", "\\text{", "\\color{", "\\hspace{", "-1000em", "\\rule{", "\\kern", "\\verb|", "\\",
            "\uD83D", "😀",
        )

        val density = Density(1f)
        val fonts by lazy { runBlocking { loadKatexFonts() } }
        val context by lazy { LatexConfig(fontSize = 17.sp).toContext(false, fonts) }

        fun corpus(files: List<String>): List<Pair<String, String>> {
            val dir = generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "test-corpus/ratex") }
                .first { it.isDirectory }
            return files.flatMap { name ->
                File(dir, name).readLines()
                    .filter { val t = it.trim(); t.isNotEmpty() && t[0] != '#' && t[0] != '%' }
                    .map { name to it }
            }
        }

        private fun wrap(prefix: String, suffix: String, depth: Int) = prefix.repeat(depth) + "x" + suffix.repeat(depth)

        fun families(depth: Int): List<Pair<String, String>> = listOf(
            "braces" to wrap("{", "}", depth),
            "frac" to wrap("\\frac{1}{", "}", depth),
            "superscript" to wrap("x^{", "}", depth),
            "sqrt" to wrap("\\sqrt{", "}", depth),
            "left-right" to wrap("\\left(", "\\right)", depth),
            "overline" to wrap("\\overline{", "}", depth),
            "color" to wrap("\\color{red}{", "}", depth),
            "overset" to wrap("\\overset{a}{", "}", depth),
            "matrix" to wrap("\\begin{matrix}", "\\end{matrix}", depth),
            "cases" to wrap("\\begin{cases}", "\\end{cases}", depth),
            "ce" to wrap("\\ce{", "}", depth),
            "unbraced-sqrt" to "\\sqrt".repeat(depth) + "x",
            "subscript-chain" to "x" + "_1".repeat(depth),
        ).map { (name, input) -> "$name-$depth" to input }
    }
}
