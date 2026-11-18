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

import com.hrm.latex.parser.LatexParser
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Macros that expand forever or exponentially must end quickly in a parse result, after RaTeX's
 * `expansion_allocations.rs` (`docs/EXPANSION_RESOURCE_LIMITS.md`). Ordinary macro use still works.
 */
class ExpansionLimitTest {

    private val parser = LatexParser()

    @Test
    fun runawayExpansionEndsQuicklyWithoutThrowing() {
        val failures = sweep(BOMBS.map { it.key to it.value }) {
            parser.parse(it)
            parser.parseWithDiagnostics(it)
        }
        assertTrue(failures.isEmpty(), failures.report())
    }

    @Test
    fun ordinaryMacroUseStillParsesCleanly() {
        val inputs = listOf(
            "\\def\\R{\\mathbb{R}}" + "\\R+".repeat(500) + "\\R",
            "\\newcommand{\\norm}[1]{\\left\\lVert#1\\right\\rVert}\\norm{\\norm{x}}",
            "\\def\\a{xx}\\def\\b{\\a\\a}\\def\\c{\\b\\b}\\def\\d{\\c\\c}\\d",
            "\\newenvironment{pm}{\\begin{pmatrix}}{\\end{pmatrix}}\\begin{pm}a&b\\end{pm}",
            "\\def\\R{x}" + "{".repeat(StackSafetyTest.ACCEPTED_DEPTH) + "\\R" + "}".repeat(StackSafetyTest.ACCEPTED_DEPTH),
        )
        val failures = inputs.filterNot { onSmallStack { isSupported(parser, it) }.getOrDefault(false) }
        assertTrue(failures.isEmpty(), "Should parse cleanly:\n" + failures.joinToString("\n"))
    }

    companion object {
        private fun doubling(levels: Int): String {
            val names = (0..levels).map { "\\m" + "a".repeat(it + 1) }
            return "\\def${names[0]}{x}" +
                (1..levels).joinToString("") { "\\def${names[it]}{${names[it - 1]}${names[it - 1]}}" } +
                names[levels]
        }

        private fun tenfold(levels: Int): String {
            val names = (0..levels).map { "\\m" + "a".repeat(it + 1) }
            return "\\def${names[0]}{xxxxxxxxxx}" +
                (1..levels).joinToString("") { "\\def${names[it]}{${names[it - 1].repeat(10)}}" } +
                names[levels]
        }

        val BOMBS = linkedMapOf(
            "self-recursive-def" to "\\def\\a{\\a}\\a",
            "self-recursive-newcommand" to "\\newcommand{\\a}{\\a}\\a",
            "growing-recursion" to "\\def\\a{\\a\\a}\\a",
            "argument-doubling" to "\\def\\a#1{\\a{#1#1}}\\a x",
            "mutual-recursion" to "\\def\\a{\\b}\\def\\b{\\a}\\a",
            "recursive-environment" to "\\newenvironment{e}{\\begin{e}}{\\end{e}}\\begin{e}x\\end{e}",
            "doubling-30" to doubling(30),
            "tenfold-6" to tenfold(6),
            "tenfold-9" to tenfold(9),
            "renewcommand-loop" to "\\newcommand{\\a}{x}\\renewcommand{\\a}{\\a\\a}\\a",
            "let-loop" to "\\let\\a\\relax\\def\\b{\\a\\b}\\b",
            "gdef-in-group" to "{\\gdef\\a{\\a x}}\\a",
            "expandafter-loop" to "\\def\\a{\\expandafter\\a\\a}\\a",
        )
    }
}
