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
import com.hrm.latex.parser.ParseDiagnostic
import com.hrm.latex.parser.model.LatexNode
import java.io.File

/** The formula lists under `test-corpus/` (see its README). */
internal object Corpus {
    /** Files whose formulas are meant to render; `unit-inputs.txt` is mostly malformed on purpose. */
    val SCORED = listOf("main.txt", "parser.txt", "mhchem.txt", "centernot.txt", "prooftree.txt", "slashed.txt")
    val ALL = SCORED + "unit-inputs.txt"

    val dir: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "test-corpus/ratex") }
            .first { it.isDirectory }
    }

    fun load(name: String): List<String> = File(dir, name).readLines().filter {
        val trimmed = it.trim()
        trimmed.isNotEmpty() && trimmed[0] != '#' && trimmed[0] != '%'
    }

    fun loadAll(names: List<String>): List<Pair<String, String>> = names.flatMap { name -> load(name).map { name to it } }
}

/** 512 KiB is the default stack of a secondary thread on iOS, the smallest a caller may parse on. */
internal const val SMALL_STACK_BYTES = 512L * 1024

/**
 * Runs [block] on a fresh thread with [stackBytes] of stack and returns what it returned or threw.
 * A [StackOverflowError] comes back as a failure instead of taking the test JVM down.
 */
internal fun <T> onSmallStack(
    stackBytes: Long = SMALL_STACK_BYTES,
    timeoutMs: Long = 600_000,
    block: () -> T,
): Result<T> {
    var result: Result<T>? = null
    val thread = Thread(null, { result = runCatching(block) }, "small-stack", stackBytes)
    thread.isDaemon = true
    thread.start()
    thread.join(timeoutMs)
    if (thread.isAlive) return Result.failure(AssertionError("Timed out after $timeoutMs ms"))
    return result!!
}

/** One input that threw or took too long, for a readable failure message. */
internal data class Failure(val source: String, val input: String, val problem: String) {
    override fun toString() = "[$source] ${problem.take(160)} <- ${input.take(120).replace("\n", "\\n")}"
}

/**
 * Feeds every input to [check] on one small-stack thread, catching per input, and returns the
 * inputs that threw or ran longer than [slowMs]. The limit catches hangs and blow-ups, so it
 * leaves room for a GC pause or a busy CI machine.
 */
internal fun sweep(
    inputs: List<Pair<String, String>>,
    slowMs: Long = 10_000,
    check: (String) -> Unit,
): List<Failure> = onSmallStack {
    val failures = mutableListOf<Failure>()
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
    failures
}.getOrThrow()

internal fun List<Failure>.report(limit: Int = 25): String =
    "$size failing inputs:\n" + take(limit).joinToString("\n") + if (size > limit) "\n…" else ""

/**
 * Whether [input] parses into something the renderer draws as intended: no error diagnostic, no
 * error node and no unrecognized command (drawn as red `\name`).
 */
internal fun isSupported(parser: LatexParser, input: String): Boolean {
    val result = parser.parseWithDiagnostics(input)
    if (result.diagnostics.any { it.severity == ParseDiagnostic.Severity.ERROR }) return false
    val pending = ArrayDeque<LatexNode>(listOf(result.document))
    while (pending.isNotEmpty()) {
        val node = pending.removeLast()
        if (node is LatexNode.Error || node is LatexNode.Command) return false
        pending.addAll(node.children())
    }
    return true
}
