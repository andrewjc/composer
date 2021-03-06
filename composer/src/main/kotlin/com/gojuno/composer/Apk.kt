package com.gojuno.composer

import com.gojuno.composer.os.Notification
import com.gojuno.composer.os.android.aapt
import com.gojuno.composer.os.process
import com.linkedin.dex.parser.DexParser

sealed class TestPackage {
    data class Valid(val value: String) : TestPackage()
    data class ParseError(val error: String) : TestPackage()
}

sealed class TestRunner {
    data class Valid(val value: String) : TestRunner()
    data class ParseError(val error: String) : TestRunner()
}


sealed class AppPackage {
    data class Valid(val value: String) : AppPackage()
    data class ParseError(val error: String) : AppPackage()
}


fun parseAppPackage(appApkPath: String): AppPackage =
        process(
                commandAndArgs = listOf(
                        aapt, "dump", "badging", appApkPath
                ),
                unbufferedOutput = true
        )
                .ofType(Notification.Exit::class.java)
                .map { (output) ->
                    output
                            .readText()
                            .split(System.lineSeparator())
                            .firstOrNull { it.contains("package") }
                            ?.split(" ")
                            ?.firstOrNull { it.startsWith("name=") }
                            ?.split("'")
                            ?.getOrNull(1)
                            ?.let(AppPackage::Valid)
                            ?: AppPackage.ParseError("Cannot parse app package from `aapt dump badging \$APK` output.")
                }
                .toSingle()
                .toBlocking()
                .value()

fun parseTestPackage(testApkPath: String): TestPackage =
        process(
                commandAndArgs = listOf(
                        aapt, "dump", "badging", testApkPath
                ),
                unbufferedOutput = true
        )
                .ofType(Notification.Exit::class.java)
                .map { (output) ->
                    output
                            .readText()
                            .split(System.lineSeparator())
                            // output format `package: name='$testPackage' versionCode='' versionName='' platformBuildVersionName='xxx'`
                            .firstOrNull { it.contains("package") }
                            ?.split(" ")
                            ?.firstOrNull { it.startsWith("name=") }
                            ?.split("'")
                            ?.getOrNull(1)
                            ?.let(TestPackage::Valid)
                            ?: TestPackage.ParseError("Cannot parse test package from `aapt dump badging \$APK` output.")
                }
                .toSingle()
                .toBlocking()
                .value()

fun parseTestRunner(testApkPath: String): TestRunner =
        process(
                commandAndArgs = listOf(
                        aapt, "dump", "xmltree", testApkPath, "AndroidManifest.xml"
                ),
                unbufferedOutput = true
        )
                .ofType(Notification.Exit::class.java)
                .map { (output) ->
                    output
                            .readText()
                            .split(System.lineSeparator())
                            .dropWhile { !it.contains("instrumentation") }
                            .firstOrNull { it.contains("android:name") }
                            // output format : `A: android:name(0x01010003)="$testRunner" (Raw: "$testRunner")`
                            ?.split("\"")
                            ?.getOrNull(1)
                            ?.let(TestRunner::Valid)
                            ?: TestRunner.ParseError("Cannot parse test runner from `aapt dump xmltree \$TEST_APK AndroidManifest.xml` output.")
                }
                .toSingle()
                .toBlocking()
                .value()

fun parseTests(testApkPath: String): List<TestMethod> =
        DexParser.findTestMethods(testApkPath).map { TestMethod(it.testName, it.annotationNames) }
