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
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every formula of the RaTeX corpus (`test-corpus/ratex`) through the parser on a 512 KiB stack:
 * whole, as every prefix (what a streaming caller sees) and appended character by character to
 * [IncrementalLatexParser].
 */
class CorpusParseTest {

    @Test
    fun everyCorpusFormulaParsesWithoutThrowing() {
        val parser = LatexParser()
        val failures = sweep(Corpus.loadAll(Corpus.ALL)) {
            parser.parse(it)
            parser.parseWithDiagnostics(it)
        }
        assertTrue(failures.isEmpty(), failures.report())
    }

    @Test
    fun everyPrefixOfEveryCorpusFormulaParsesWithoutThrowing() {
        val parser = LatexParser()
        val prefixes = Corpus.loadAll(Corpus.ALL).flatMap { (source, formula) ->
            (1 until formula.length).map { source to formula.substring(0, it) }
        }
        val failures = sweep(prefixes) { parser.parse(it) }
        assertTrue(failures.isEmpty(), failures.report())
    }

    @Test
    fun appendingEveryCorpusFormulaCharacterByCharacterNeverThrows() {
        val failures = sweep(Corpus.loadAll(Corpus.ALL)) { formula ->
            val parser = IncrementalLatexParser()
            for (char in formula) {
                parser.append(char.toString())
                parser.getCurrentDocument()
            }
        }
        assertTrue(failures.isEmpty(), failures.report())
    }

    /** Appending one UTF-16 unit at a time splits surrogate pairs; the result must still match. */
    @Test
    fun appendingSplitSurrogatePairsEndsLikeAFullParse() {
        val formulas = Corpus.loadAll(Corpus.ALL).filter { (_, formula) -> formula.any { it.isSurrogate() } }
        val failures = sweep(formulas) { formula ->
            val incremental = IncrementalLatexParser()
            formula.forEach { incremental.append(it.toString()); incremental.getCurrentDocument() }
            check(incremental.getCurrentDocument() == LatexParser().parse(formula)) { "differs from a full parse" }
        }
        assertTrue(formulas.isNotEmpty())
        assertTrue(failures.isEmpty(), failures.report())
    }

    /**
     * A ratchet on `unsupported-baseline.txt`: fails when a formula joins or leaves it, so every
     * change in coverage is reviewed. `UPDATE_CORPUS_BASELINE=1` rewrites the file.
     */
    @Test
    fun unsupportedFormulasMatchTheBaseline() {
        val parser = LatexParser()
        val formulas = Corpus.loadAll(Corpus.SCORED).map { it.second }.distinct()
        val unsupported = onSmallStack { formulas.filterNot { runCatching { isSupported(parser, it) }.getOrDefault(false) } }
            .getOrThrow().toSortedSet()
        val baselineFile = File(Corpus.dir, "unsupported-baseline.txt")
        println("RaTeX corpus coverage: ${formulas.size - unsupported.size}/${formulas.size} formulas parse cleanly " +
            "(${"%.1f".format(100.0 * (formulas.size - unsupported.size) / formulas.size)}%)")

        if (System.getenv("UPDATE_CORPUS_BASELINE") == "1" || !baselineFile.exists()) {
            baselineFile.writeText(unsupported.joinToString("\n", postfix = "\n"))
            return
        }
        val baseline = baselineFile.readLines().filter { it.isNotBlank() }.toSet()
        val newlyBroken = unsupported - baseline
        val newlyFixed = baseline - unsupported
        if (newlyBroken.isNotEmpty() || newlyFixed.isNotEmpty()) {
            fail(
                "Coverage changed; rerun with UPDATE_CORPUS_BASELINE=1 if intended.\n" +
                    "No longer parse cleanly (${newlyBroken.size}):\n" + newlyBroken.joinToString("\n") +
                    "\nNow parse cleanly (${newlyFixed.size}):\n" + newlyFixed.joinToString("\n")
            )
        }
    }
}
