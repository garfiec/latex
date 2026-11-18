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
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Seeded random input: structural tokens in random order, and corpus formulas with random edits.
 * The default sizes keep `jvmTest` quick; `-Dlatex.fuzz.iterations=N` runs longer campaigns.
 */
class ParserFuzzTest {

    private val parser = LatexParser()
    private val iterations = System.getProperty("latex.fuzz.iterations")?.toIntOrNull() ?: 20_000

    @Test
    fun randomTokenSequencesNeverThrow() {
        val random = Random(SEED)
        val inputs = List(iterations) {
            "tokens" to buildString { repeat(1 + random.nextInt(40)) { append(TOKENS[random.nextInt(TOKENS.size)]) } }
        }
        val failures = sweep(inputs) { parser.parseWithDiagnostics(it) }
        assertTrue(failures.isEmpty(), failures.report())
    }

    @Test
    fun editedCorpusFormulasNeverThrow() {
        val random = Random(SEED)
        val corpus = Corpus.loadAll(Corpus.ALL).map { it.second }
        val inputs = List(iterations) { "edit" to mutate(corpus[random.nextInt(corpus.size)], corpus, random) }
        val failures = sweep(inputs) { parser.parseWithDiagnostics(it) }
        assertTrue(failures.isEmpty(), failures.report())
    }

    companion object {
        const val SEED = 42

        val TOKENS = listOf(
            "{", "}", "[", "]", "(", ")", "^", "_", "&", "\\\\", "#", "#1", "%", "$", "'", "~", " ", "\n",
            "x", "1", "\\alpha", "\\frac", "\\dfrac", "\\sqrt", "\\left(", "\\right)", "\\left.", "\\right.",
            "\\middle|", "\\big(", "\\over", "\\choose", "\\atop", "\\begin{cases}", "\\end{cases}",
            "\\begin{matrix}", "\\end{matrix}", "\\begin{array}{c|c}", "\\end{array}", "\\begin{aligned}",
            "\\end{aligned}", "\\text{", "\\mathbb{", "\\mathrm", "\\operatorname{", "\\color{", "\\textcolor{red}{",
            "\\hspace{", "\\kern", "-1000em", "\\tag{", "\\label{", "\\ref{", "\\cr", "\\hline", "\\verb|",
            "\\def\\q#1{#1#1}", "\\q", "\\newcommand", "\\newcommand{\\w}[2]{#2#1}", "\\w", "\\renewcommand",
            "\\newenvironment{v}{(}{)}", "\\begin{v}", "\\end{v}", "\\let", "\\global", "\\ce{", "\\pu{",
            "\\overset", "\\underbrace{", "\\xrightarrow[", "\\substack{", "\\not", "\\hat", "\\vec{",
            "\\boxed{", "\\raisebox{", "\\rule{", "\\href{", "\\url{", "\\char", "\"41", "\\unicode{",
            "\\displaystyle", "\\limits", "\\nolimits", "\\mathop{", "\\sideset{", "\\prescript{", "\\cancel{",
            "\\phantom{", "\\smash[", "\\stackrel{", "\\genfrac{", "\\binom", "\\pmod{", "\\\$", "\\{", "\\}",
            "\\", "́", "‍", "😀", "\uD83D", "é",
        )

        fun mutate(formula: String, corpus: List<String>, random: Random): String {
            val text = StringBuilder(formula)
            repeat(1 + random.nextInt(4)) {
                val at = if (text.isEmpty()) 0 else random.nextInt(text.length + 1)
                when (random.nextInt(5)) {
                    0 -> if (text.isNotEmpty()) text.deleteAt(at.coerceAtMost(text.length - 1))
                    1 -> text.insert(at, TOKENS[random.nextInt(TOKENS.size)])
                    2 -> if (text.isNotEmpty()) {
                        val end = (at + random.nextInt(12)).coerceAtMost(text.length)
                        text.insert(at, text.substring(at.coerceAtMost(end), end))
                    }
                    3 -> text.insert(at, corpus[random.nextInt(corpus.size)])
                    else -> text.setLength(at.coerceAtMost(text.length))
                }
            }
            return text.toString()
        }
    }
}
