package com.gojuno.composer

import com.gojuno.commander.android.AdbDevice
import com.gojuno.commander.os.Notification
import com.gojuno.commander.os.log
import com.gojuno.commander.os.process
import rx.Observable
import rx.Single
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

val androidHome: String by lazy { requireNotNull(System.getenv("ANDROID_HOME")) { "Please specify ANDROID_HOME env variable" } }
val adb: String by lazy { "$androidHome/platform-tools/adb" }
private val buildTools: String? by lazy {
    File(androidHome, "build-tools")
            .listFiles()
            .sortedArray()
            .lastOrNull()
            ?.absolutePath
}
val aapt: String by lazy { buildTools?.let { "$buildTools/aapt" } ?: "" }

fun deviceModel(adbDevice: AdbDevice): Single<String> = process(listOf(adb, "-s", adbDevice.id, "shell", "getprop ro.product.model undefined"))
        .ofType(Notification.Exit::class.java)
        .trimmedOutput()
        .doOnError { log("Could not get model name of device ${adbDevice.id}, error = $it") }

internal fun Observable<Notification.Exit>.trimmedOutput() = toSingle()
        .map { it.output.readText().trim() }

fun connectedAdbDevices(): Observable<Set<AdbDevice>> = process(listOf(adb, "devices"), unbufferedOutput = true)
        .ofType(Notification.Exit::class.java)
        .map { it.output.readText() }
        .map {
            when (it.contains("List of devices attached")) {
                true -> it
                false -> throw IllegalStateException("Adb output is not correct: $it.")
            }
        }
        .retry { retryCount, exception ->
            val shouldRetry = retryCount < 5 && exception is IllegalStateException
            if (shouldRetry) {
                log("runningEmulators: retrying $exception.")
            }

            shouldRetry
        }
        .flatMapIterable {
            it
                    .substringAfter("List of devices attached")
                    .split(System.lineSeparator())
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .filter { it.contains("online") || it.contains("device") }
        }
        .map { line ->
            val serial = line.substringBefore("\t")
            val online = when {
                line.contains("offline", ignoreCase = true) -> false
                line.contains("device", ignoreCase = true) -> true
                else -> throw IllegalStateException("Unknown adb output for device: $line")
            }
            AdbDevice(id = serial, online = online)
        }
        .flatMapSingle { device ->
            deviceModel(device).map { model ->
                device.copy(model = model)
            }
        }
        .toList()
        .map { it.toSet() }
        .doOnError { log("Error during getting connectedAdbDevices, error = $it") }

fun AdbDevice.log(message: String) = println("[${Date()}]: [$id] $message")

fun AdbDevice.installApk(pathToApk: String, timeout: Pair<Int, TimeUnit> = 2 to TimeUnit.MINUTES): Observable<Unit> {
    val adbDevice = this

    val packageName: String = parseAppPackage(pathToApk).let {
        when (it) {
            is AppPackage.Valid -> it.value
            is AppPackage.ParseError -> {
                "unknown"
            }
        }
    }

    val pushApkProcess = process(
            commandAndArgs = listOf(adb, "-s", adbDevice.id, "push", pathToApk, "/data/local/tmp/$packageName"),
            unbufferedOutput = true,
            timeout = timeout
    )

    val installApkProcess = process(
            commandAndArgs = listOf(adb, "-s", adbDevice.id, "shell", "pm", "install", "-t", "-r", "/data/local/tmp/$packageName"),
            unbufferedOutput = true,
            timeout = timeout
    )

    return Observable
            .fromCallable { System.nanoTime() }
            .flatMap { startTimeNanos -> pushApkProcess.ofType(Notification.Exit::class.java).map { it to startTimeNanos } }
            .flatMap { startTimeNanos -> installApkProcess.ofType(Notification.Exit::class.java).map { it to startTimeNanos}}
            .map { (exit, startTimeNanos) ->
                val success = exit
                        .output
                        .readText()
                        .split(System.lineSeparator())
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .firstOrNull { it.equals("Success", ignoreCase = true) } != null

                when (success) {
                    true -> {
                        adbDevice.log("Successfully installed apk, pathToApk = $pathToApk")
                    }

                    false -> {
                        adbDevice.log("Failed to install apk $pathToApk")
                        System.exit(1)
                    }
                }
            }
            .doOnSubscribe { adbDevice.log("Installing apk... pathToApk = $pathToApk") }
            .doOnError { adbDevice.log("Error during installing apk: $it, pathToApk = $pathToApk") }
}

fun AdbDevice.pullFolder(folderOnDevice: String, folderOnHostMachine: File, logErrors: Boolean, timeout: Pair<Int, TimeUnit> = 60 to TimeUnit.SECONDS): Single<Boolean> {
    val adbDevice = this
    val pullFiles = process(
            commandAndArgs = listOf(adb, "-s", adbDevice.id, "pull", folderOnDevice, folderOnHostMachine.absolutePath),
            timeout = timeout,
            unbufferedOutput = true
    )

    return pullFiles
            .ofType(Notification.Exit::class.java)
            .retry(3)
            .doOnError { error -> if (logErrors) log("Failed to pull files from $folderOnDevice to $folderOnHostMachine failed: $error") }
            .map { true }
            .onErrorReturn { false }
            .toSingle()
}

fun AdbDevice.redirectLogcatToFile(file: File): Single<Process> = Observable
        .fromCallable { file.parentFile.mkdirs() }
        .flatMap { process(listOf(adb, "-s", this.id, "logcat"), redirectOutputTo = file, timeout = null) }
        .ofType(Notification.Start::class.java)
        .doOnError {
            when (it) {
                is InterruptedException -> Unit // Expected case, interrupt comes from System.exit(0).
                else -> this.log("Error during redirecting logcat to file $file, error = $it")
            }
        }
        .map { it.process }
        .take(1)
        .toSingle()
