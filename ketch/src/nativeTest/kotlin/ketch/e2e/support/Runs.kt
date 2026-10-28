// Running the ketch under test against a [Sandbox], as Rust tests/support/mod.rs's `Sandbox::ketch*` methods do.
package ketch.e2e.support

import kotlin.test.assertTrue

/** Run ketch against this sandbox, with the bin dir on PATH: the configuration ketch is installed into. */
fun Sandbox.ketch(vararg args: String) = ketch(args.toList())

fun Sandbox.ketch(args: List<String>) = command(KETCH_EXE, args, pathWithBin()).runOnce()

/** Run ketch with the bin dir left off PATH, as an install not yet wired into a shell looks. */
fun Sandbox.ketchOffPath(vararg args: String) = command(KETCH_EXE, args.toList(), joinPath(inheritedPath())).runOnce()

/** As [ketch], with extra environment variables; they win over the sandbox's. */
fun Sandbox.ketchOverrides(
    args: List<String>,
    envs: Map<String, String>,
) = command(KETCH_EXE, args, pathWithBin()).apply { envs.forEach { (k, v) -> env(k, v) } }.runOnce()

/** As [ketch], with [input] on stdin. */
fun Sandbox.ketchWithInput(
    args: List<String>,
    input: String,
    envs: Map<String, String> = emptyMap(),
): Run {
    val file = child("stdin-${inputs++}")
    writeText(file, input)
    return command(KETCH_EXE, args, pathWithBin())
        .apply { envs.forEach { (k, v) -> env(k, v) } }
        .stdinFrom(file.toString())
        .runOnce()
}

/**
 * Run ketch on a pseudo-terminal, as a person at a prompt would, with [input] typed ahead. BSD `script` provides
 * the terminal; its output is stdout and stderr together. The input stays open until ketch is done: `script`
 * turns the end of its input into an end of file on the terminal, which can reach the prompt before the answer.
 */
fun Sandbox.ketchOnTty(
    args: List<String>,
    input: String,
): Run {
    val file = child("tty-${inputs++}")
    writeText(file, input)
    val pipeline = "{ cat \"\$0\"; sleep 60; } | /usr/bin/script -q /dev/null \"\$@\""
    return command("/bin/sh", listOf("-c", pipeline, file.toString(), KETCH_EXE) + args, pathWithBin()).runOnce()
}

/** Run ketch and fail the test with its full output if it did not succeed; returns stdout. */
fun Sandbox.ok(vararg args: String): String = ok(args.toList())

fun Sandbox.ok(args: List<String>): String = ketch(args).also { assertTrue(it.success, "`ketch ${args.joinToString(" ")}` failed with $it") }.stdout

fun Sandbox.okEnv(
    args: List<String>,
    envs: Map<String, String>,
): String = ketchOverrides(args, envs).also { assertTrue(it.success, "`ketch ${args.joinToString(" ")}` failed with $it") }.stdout

/** Run ketch expecting failure; returns stderr. */
fun Sandbox.fails(vararg args: String): String {
    val out = ketch(args.toList())
    assertTrue(!out.success, "`ketch ${args.joinToString(" ")}` was expected to fail but succeeded\n--- stdout ---\n${out.stdout}")
    return out.stderr
}
