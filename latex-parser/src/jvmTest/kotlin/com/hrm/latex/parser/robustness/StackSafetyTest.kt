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


package com.hrm.latex.parser.robustness

import com.hrm.latex.parser.IncrementalLatexParser
import com.hrm.latex.parser.LatexParser
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Deep input on a 512 KiB stack, after RaTeX's `stack_safety.rs`: adversarial depths must come back
 * as a parse result (errors allowed), and depth [ACCEPTED_DEPTH] must still parse cleanly.
 */
class StackSafetyTest {

    private val parser = LatexParser()

    @Test
    fun adversarialDepthNeverThrows() {
        val inputs = (ADVERSARIAL_DEPTHS + AROUND_THE_LIMIT).flatMap { depth ->
            nestedFamilies(depth).map { (name, input) -> "$name-$depth" to input } +
                flatFamilies(depth).map { (name, input) -> "$name-$depth" to input }
        }
        val failures = sweep(inputs) {
            parser.parse(it)
            parser.parseWithDiagnostics(it)
        }
        assertTrue(failures.isEmpty(), failures.report())
    }

    @Test
    fun adversarialDepthNeverThrowsWhenStreamed() {
        val inputs = nestedFamilies(300).map { (name, input) -> "$name-300" to input }
        val failures = sweep(inputs, slowMs = 30_000) { input ->
            val incremental = IncrementalLatexParser()
            input.chunked(7).forEach { incremental.append(it); incremental.getCurrentDocument() }
        }
        assertTrue(failures.isEmpty(), failures.report())
    }

    @Test
    fun acceptedDepthParsesCleanly() {
        val failures = nestedFamilies(ACCEPTED_DEPTH).mapNotNull { (name, input) ->
            val supported = onSmallStack { isSupported(parser, input) }
            if (supported.getOrNull() == true) null
            else Failure("$name-$ACCEPTED_DEPTH", input, supported.exceptionOrNull()?.toString() ?: "parse errors")
        }
        assertTrue(failures.isEmpty(), failures.report())
    }

    companion object {
        /** Deepest nesting that must keep rendering; RaTeX's limit (`docs/STACK_SAFETY.md`). */
        const val ACCEPTED_DEPTH = 32
        val ADVERSARIAL_DEPTHS = listOf(300, 1_000, 4_200)

        /** Spans the deepest input each structure still accepts, where the parser's stack is fullest. */
        val AROUND_THE_LIMIT = 33..100

        private fun wrap(prefix: String, suffix: String, depth: Int) = prefix.repeat(depth) + "x" + suffix.repeat(depth)

        /** Structures that nest through braces or a matching closer. */
        fun nestedFamilies(depth: Int): List<Pair<String, String>> = listOf(
            "braces" to wrap("{", "}", depth),
            "frac" to wrap("\\frac{1}{", "}", depth),
            "dfrac-numerator" to wrap("\\dfrac{", "}{2}", depth),
            "superscript" to wrap("x^{", "}", depth),
            "subscript" to wrap("x_{", "}", depth),
            "sqrt" to wrap("\\sqrt{", "}", depth),
            "sqrt-index" to wrap("\\sqrt[", "]{y}", depth),
            "left-right" to wrap("\\left(", "\\right)", depth),
            "overline" to wrap("\\overline{", "}", depth),
            "hat" to wrap("\\hat{", "}", depth),
            "text" to wrap("\\text{", "}", depth),
            // Two structures per level (a text box holding inline math); the budget is shared.
            "text-math" to wrap("\\text{\$", "\$}", depth / 2),
            "mathbf" to wrap("\\mathbf{", "}", depth),
            "color" to wrap("\\color{red}{", "}", depth),
            "boxed" to wrap("\\boxed{", "}", depth),
            "overset" to wrap("\\overset{a}{", "}", depth),
            "xrightarrow" to wrap("\\xrightarrow{", "}", depth),
            "matrix" to wrap("\\begin{matrix}", "\\end{matrix}", depth),
            "cases" to wrap("\\begin{cases}", "\\end{cases}", depth),
            "array" to wrap("\\begin{array}{c}", "\\end{array}", depth),
            "operatorname" to wrap("\\operatorname{", "}", depth),
            "ce" to wrap("\\ce{", "}", depth),
            "tag" to "\\tag{" + wrap("{", "}", depth) + "}",
            "gdef-tag" to "\\gdef\\mytag{" + wrap("{", "}", depth) + "}\\tag\\mytag",
            "raisebox" to wrap("\\raisebox{0pt}{", "}", depth),
        )

        /** Chains without closers: unbraced arguments, prefixes and repeated tokens. */
        fun flatFamilies(depth: Int): List<Pair<String, String>> = listOf(
            "unbraced-sqrt" to "\\sqrt".repeat(depth) + "x",
            "unbraced-hat" to "\\hat".repeat(depth) + "x",
            "unbraced-frac" to "\\frac".repeat(depth) + "xy",
            "unbraced-mathbf" to "\\mathbf".repeat(depth) + "x",
            "superscript-chain" to "x" + "^".repeat(depth) + "1",
            // Scripts on a script deepen the tree in a loop, without recursing in the parser.
            "subscript-chain" to "x" + "_1".repeat(depth),
            "script-chain" to "x" + "_1^2".repeat(depth),
            "primed-subscript-chain" to "x" + "'_1".repeat(depth),
            "script-tower" to "x^".repeat(depth) + "1",
            "bigl-chain" to "\\bigl".repeat(depth) + "(",
            "left-chain" to "\\left".repeat(depth) + "(",
            "combining-accents" to "x" + "\u0301".repeat(depth),
            "text-combining-accents" to "\\text{x" + "\u0301".repeat(depth) + "}",
            "comment-lines" to "%\n".repeat(depth) + "x",
            "global-prefixes" to "\\global".repeat(depth) + "\\def\\foo{x}\\foo",
            "open-braces" to "{".repeat(depth),
            "close-braces" to "}".repeat(depth),
            "over-chain" to "a\\over ".repeat(depth) + "b",
            "choose-chain" to "a\\choose ".repeat(depth) + "b",
            "dots" to "\\dots".repeat(depth),
            "rows" to "\\begin{matrix}" + "x\\\\".repeat(depth) + "\\end{matrix}",
            "columns" to "\\begin{matrix}" + "x&".repeat(depth) + "\\end{matrix}",
        )
    }
}
