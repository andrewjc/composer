package com.gojuno.composer.os

import rx.Emitter.BackpressureMode
import rx.Observable
import rx.schedulers.Schedulers.io
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.TimeoutException

val home: String by lazy { System.getenv("HOME") }

fun log(message: String) = println("[${Date()}]: $message")

sealed class Notification {
    data class Start(val process: Process, val output: File) : Notification()
    data class Exit(val output: File) : Notification()
}

fun process(
        commandAndArgs: List<String>,
        timeout: Pair<Int, TimeUnit>? = 30 to SECONDS,
        redirectOutputTo: File? = null,
        keepOutputOnExit: Boolean = false,
        unbufferedOutput: Boolean = false,
        print: Boolean = false,
        destroyOnUnsubscribe: Boolean = false
): Observable<Notification> = Observable.create<Notification>(
        { emitter ->
            if (print) {
                log("\nRun: $commandAndArgs")
            }

            val outputFile = when {
                redirectOutputTo == null || redirectOutputTo.isDirectory -> {
                    prepareOutputFile(redirectOutputTo, keepOutputOnExit)
                }
                else -> redirectOutputTo
            }

            outputFile.apply { parentFile?.mkdirs() }

            if (print) {
                log("$commandAndArgs\n, outputFile = $outputFile")
            }

            val command: List<String> = when (unbufferedOutput) {
                false -> commandAndArgs
                true -> when (os()) {
                // Some programs, in particular "emulator" do not always flush output
                // after printing so we have to force unbuffered mode to make sure
                // that output will be available for consuming.
                    Os.Linux -> listOf("script", outputFile.absolutePath, "--flush", "-c", commandAndArgs.joinToString(separator = " "))
                    Os.Mac -> listOf("script", "-F", outputFile.absolutePath, *commandAndArgs.toTypedArray())
                    Os.Windows -> commandAndArgs
                }
            }

            val process: Process = ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .let {
                        when (unbufferedOutput) {
                            true -> it.redirectOutput(os().nullDeviceFile())
                            else -> it.redirectOutput(ProcessBuilder.Redirect.to(outputFile))
                        }
                    }
                    .start()

            if (destroyOnUnsubscribe) {
                emitter.setCancellation {
                    process.destroy()
                }
            }

            emitter.onNext(Notification.Start(process, outputFile))

            if (timeout == null) {
                process.waitFor()
            } else {
                if (process.waitFor(timeout.first.toLong(), timeout.second).not()) {
                    throw TimeoutException("Process $command timed out ${timeout.first} ${timeout.second} waiting for exit code ${outputFile.readText()}") as Throwable
                }
            }

            val exitCode = process.exitValue()

            if (print) {
                log("Exit code $exitCode: $commandAndArgs,\noutput = \n${outputFile.readText()}")
            }

            when (exitCode) {
                0 -> {
                    emitter.onNext(Notification.Exit(outputFile))
                    emitter.onCompleted()
                }
                else -> {
                    emitter.onError(IllegalStateException("Process $command exited with non-zero code $exitCode ${outputFile.readText()}"))
                }
            }
        }, BackpressureMode.LATEST
)
        .subscribeOn(io()) // Prevent subscriber thread from unnecessary blocking.
        .observeOn(io())   // Allow to wait for process exit code.

private fun prepareOutputFile(parent: File?, keepOnExit: Boolean): File = Random()
        .nextInt()
        .let { System.nanoTime() + it }
        .let { name ->
            File(parent, "$name.output").apply {
                createNewFile()
                if (!keepOnExit) {
                    deleteOnExit()
                }
            }
        }

enum class Os {
    Linux,
    Mac,
    Windows
}

internal fun os(): Os {
    val os = System.getProperty("os.name", "unknown").toLowerCase(Locale.ENGLISH)

    if (os.contains("mac") || os.contains("darwin")) {
        return Os.Mac
    } else if (os.contains("linux")) {
        return Os.Linux
    } else if (os.contains("windows")) {
        return Os.Windows
    } else {
        throw IllegalStateException("Unsupported os $os, only ${Os.values()} are supported.")
    }
}

internal fun Os.nullDeviceFile(): File {
    val path = when (this) {
        Os.Linux, Os.Mac -> "/dev/null"
        Os.Windows -> "NUL"
    }
    return File(path)
}

fun Long.nanosToHumanReadableTime(): String {
    var seconds: Long = TimeUnit.NANOSECONDS.toSeconds(this)
    var minutes: Long = (seconds / 60).apply {
        seconds -= this * 60
    }
    val hours: Long = (minutes / 60).apply {
        minutes -= this * 60
    }

    return buildString {
        if (hours != 0L) {
            append("$hours hour")

            if (hours > 1) {
                append("s")
            }

            append(" ")
        }

        if (minutes != 0L || hours > 0) {
            append("$minutes minute")

            if (minutes != 1L) {
                append("s")
            }

            append(" ")
        }

        append("$seconds second")

        if (seconds != 1L) {
            append("s")
        }
    }
}
