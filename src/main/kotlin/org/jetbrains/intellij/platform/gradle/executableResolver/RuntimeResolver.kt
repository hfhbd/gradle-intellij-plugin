// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.intellij.platform.gradle.executableResolver

import org.gradle.api.file.FileCollection
import org.gradle.internal.jvm.Jvm
import org.gradle.internal.os.OperatingSystem
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JavaToolchainSpec
import org.gradle.jvm.toolchain.internal.DefaultJvmVendorSpec
import org.jetbrains.intellij.platform.gradle.*
import org.jetbrains.intellij.platform.gradle.IntelliJPluginConstants.JETBRAINS_RUNTIME_VENDOR
import org.jetbrains.intellij.platform.gradle.utils.asPath
import org.jetbrains.intellij.platform.gradle.utils.ifNull
import org.jetbrains.intellij.platform.gradle.utils.or
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.nio.file.Path
import java.util.*
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

class RuntimeResolver(
    val jetbrainsRuntime: FileCollection,
    val intellijPlatform: FileCollection,
    val javaToolchainSpec: JavaToolchainSpec,
    val javaToolchainService: JavaToolchainService,
    val context: String? = null,
) : ExecutableResolver {

    override fun resolveExecutable() = runtime

    override fun resolveDirectory() = directory

    private val directory by lazy {
        debug(context, "Resolving runtime directory.")

        listOf(
            /**
             * Use JetBrains Runtime provided via [IntelliJPluginConstants.Configurations.JETBRAINS_RUNTIME_DEPENDENCY] configuration.
             * To add a custom JetBrains Runtime, use [org.jetbrains.intellij.platform.gradle.dependencies.jetbrainsRuntime]
             * or [org.jetbrains.intellij.platform.gradle.dependencies.jetbrainsRuntimeExplicit].
             */
            {
                jetbrainsRuntime.singleOrNull()?.let { file ->
                    file.toPath().resolveRuntimeDirectory()
                        .also { debug(context, "JetBrains Runtime specified with dependencies resolved as: $it") }
                        .ensureExecutableExists()
                        .ifNull { debug(context, "Cannot resolve JetBrains Runtime: $file") }
                }
            },
            {
                @Suppress("UnstableApiUsage")
                javaToolchainSpec.vendor.orNull
                    ?.takeUnless { it == DefaultJvmVendorSpec.any() }
                    ?.takeIf { it.matches(JETBRAINS_RUNTIME_VENDOR) }
                    ?.let { javaToolchainService.launcherFor(javaToolchainSpec).get() }
                    ?.let { javaLauncher ->
                        javaLauncher.metadata.installationPath.asPath.resolveRuntimeDirectory()
                            .also { debug(context, "JetBrains Runtime specified with Java Toolchain resolved as: $it") }
                            .ensureExecutableExists()
                            .ifNull { debug(context, "Cannot resolve JetBrains Runtime specified with Java Toolchain") }
                    }
            },
            {
                intellijPlatform.singleOrNull()?.let { file ->
                    file.toPath().resolveRuntimeDirectory()
                        .also { debug(context, "JetBrains Runtime bundled within IntelliJ Platform resolved as: $it") }
                        .ensureExecutableExists()
                        .ifNull { debug(context, "Cannot resolve JetBrains Runtime bundled within IntelliJ Platform: $file") }
                }
            },
            {
                javaToolchainSpec.languageVersion.orNull
                    ?.let { javaToolchainService.launcherFor(javaToolchainSpec).get() }
                    ?.let { javaLauncher ->
                        javaLauncher.metadata.installationPath.asPath.resolveRuntimeDirectory()
                            .also { debug(context, "Java Runtime specified with Java Toolchain resolved as: $it") }
                            .ensureExecutableExists()
                            .ifNull { debug(context, "Cannot resolve Java Runtime specified with Java Toolchain") }
                    }
            },
            {
                Jvm.current().javaHome.toPath().resolveRuntimeDirectory()
                    .also { debug(context, "Using current JVM: $it") }
                    .ensureExecutableExists()
                    .ifNull { debug(context, "Cannot resolve current JVM") }
            },
        )
            .asSequence()
            .mapNotNull { it() }
            .firstOrNull()
            ?.also { info(context, "Resolved Runtime directory: $it") }
    }

    val runtime by lazy {
        directory?.resolveRuntimeExecutable()
    }

    private fun getBuiltinJbrVersion(ideDirectory: File): String? {
        val dependenciesFile = File(ideDirectory, "dependencies.txt")
        if (dependenciesFile.exists()) {
            val properties = Properties()
            val reader = FileReader(dependenciesFile)
            try {
                properties.load(reader)
                return properties.getProperty("runtimeBuild") ?: properties.getProperty("jdkBuild")
            } catch (ignore: IOException) {
            } finally {
                reader.close()
            }
        }
        return null
    }

    private fun Path.resolveRuntimeDirectory(): Path? {
        val jbr = listDirectoryEntries().firstOrNull { it.name.startsWith("jbr") }?.takeIf { it.exists() }

        return when {
            OperatingSystem.current().isMacOsX -> when {
                endsWith("Contents/Home") -> this
                jbr != null -> jbr.resolve("Contents/Home")
                else -> resolve("jdk/Contents/Home")
            }

            else -> when {
                jbr != null -> jbr
                else -> this
            }
        }.takeIf { it.exists() }
    }

    private fun Path.resolveRuntimeExecutable(): Path? {
        val base = resolve("jre").takeIf { it.exists() }.or(this)
        val extension = ".exe".takeIf { OperatingSystem.current().isWindows }.orEmpty()
        return base.resolve("bin/java$extension").takeIf { it.exists() }
    }

    private fun Path?.ensureExecutableExists() = this
        ?.resolveRuntimeExecutable()
        .ifNull { debug(context, "Java Runtime Executable not found in: $this") }
        ?.let { this }
}
