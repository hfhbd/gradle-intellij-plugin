// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

@file:JvmName("Utils")
@file:Suppress("BooleanMethodIsAlwaysInverted")

package org.jetbrains.intellij.platform.gradle

import com.jetbrains.plugin.structure.base.plugin.PluginCreationFail
import com.jetbrains.plugin.structure.base.plugin.PluginCreationSuccess
import com.jetbrains.plugin.structure.base.problems.PluginProblem
import com.jetbrains.plugin.structure.base.utils.exists
import com.jetbrains.plugin.structure.intellij.extractor.PluginBeanExtractor
import com.jetbrains.plugin.structure.intellij.plugin.IdePlugin
import com.jetbrains.plugin.structure.intellij.plugin.IdePluginManager
import com.jetbrains.plugin.structure.intellij.utils.JDOMUtil
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.file.RegularFile
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.LogLevel
import org.gradle.api.logging.Logging
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.plugins.PluginInstantiationException
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.getByName
import org.gradle.util.GradleVersion
import org.jdom2.Document
import org.jdom2.output.Format
import org.jdom2.output.XMLOutputter
import org.jetbrains.intellij.platform.gradle.IntelliJPluginConstants.MINIMAL_SUPPORTED_GRADLE_VERSION
import org.jetbrains.intellij.platform.gradle.IntelliJPluginConstants.PLUGIN_NAME
import org.jetbrains.intellij.platform.gradle.plugins.IntelliJPlatformPlugin
import org.jetbrains.intellij.platform.gradle.utils.Version
import java.io.File
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files.createTempDirectory
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.function.Predicate
import kotlin.io.path.*

internal fun sourcePluginXmlFiles(project: Project) = project
    .extensions.getByName<JavaPluginExtension>("java") // Name hard-coded in JavaBasePlugin.addExtensions and well-known.
    .sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME) // TODO: iterate over all sourceSets?
    .resources
    .srcDirs
    .filterNotNull()
    .map(File::toPath)
    .map { it.resolve("META-INF/plugin.xml") }
    .filter { it.exists() && it.fileSize() > 0 }

internal fun parsePluginXml(pluginXml: Path) = runCatching {
    pluginXml.inputStream().use {
        val document = JDOMUtil.loadDocument(it)
        PluginBeanExtractor.extractPluginBean(document)
    }
}.getOrElse {
    logger.warn("Cannot read: $pluginXml. Skipping", it)
    null
}

fun transformXml(document: Document, path: Path) {
    val xmlOutput = XMLOutputter()
    xmlOutput.format.apply {
        indent = "  "
        omitDeclaration = true
        textMode = Format.TextMode.TRIM
    }

    StringWriter().use {
        xmlOutput.output(document, it)
        path.writeText(it.toString())
    }
}

fun collectJars(directory: Path, filter: Predicate<Path> = Predicate { true }) =
    collectFiles(directory) { it.extension == "jar" && filter.test(it) }

fun collectZips(directory: Path, filter: Predicate<Path> = Predicate { true }) =
    collectFiles(directory) { it.extension == "zip" && filter.test(it) }

private fun collectFiles(directory: Path, filter: Predicate<Path>) = directory
    .takeIf { it.isDirectory() }
    ?.listDirectoryEntries()
    .orEmpty()
    .filter { filter.test(it) }

@Deprecated(message = "Use Logger")
fun error(logCategory: String? = null, message: String, e: Throwable? = null) = log(LogLevel.ERROR, logCategory, message, e)

@Deprecated(message = "Use Logger")
fun warn(logCategory: String? = null, message: String, e: Throwable? = null) = log(LogLevel.WARN, logCategory, message, e)

@Deprecated(message = "Use Logger")
fun info(logCategory: String? = null, message: String, e: Throwable? = null) = log(LogLevel.INFO, logCategory, message, e)

@Deprecated(message = "Use Logger")
fun debug(logCategory: String? = null, message: String, e: Throwable? = null) = log(LogLevel.DEBUG, logCategory, message, e)

private val logger = Logging.getLogger(IntelliJPlatformPlugin::class.java)

private fun log(level: LogLevel, logCategory: String?, message: String, e: Throwable?) {
    val category = "gradle-intellij-plugin ${logCategory.orEmpty()}".trim()
    if (e != null && level != LogLevel.ERROR && !logger.isDebugEnabled) {
        logger.log(level, "[$category] $message. Run with --debug option to get more log output.")
    } else {
        logger.log(level, "[$category] $message", e)
    }
}

fun Project.logCategory(): String = path + if (path.endsWith(name)) " $name" else ""

fun Task.logCategory(): String = project.logCategory() + path.removePrefix(project.logCategory())

fun createPlugin(artifact: Path, validatePluginXml: Boolean, context: String?): IdePlugin? {
    val extractDirectory = createTempDirectory("tmp")
    val creationResult = IdePluginManager.createManager(extractDirectory)
        .createPlugin(artifact, validatePluginXml, IdePluginManager.PLUGIN_XML)

    return when (creationResult) {
        is PluginCreationSuccess -> creationResult.plugin
        is PluginCreationFail -> {
            val problems = creationResult.errorsAndWarnings.filter { it.level == PluginProblem.Level.ERROR }.joinToString()
            warn(context, "Cannot create plugin from file '$artifact': $problems")
            null
        }

        else -> {
            warn(context, "Cannot create plugin from file '$artifact'. $creationResult")
            null
        }
    }
}

fun isKotlinRuntime(name: String) =
    name == "kotlin-runtime" ||
            name == "kotlin-reflect" || name.startsWith("kotlin-reflect-") ||
            name == "kotlin-stdlib" || name.startsWith("kotlin-stdlib-") ||
            name == "kotlin-test" || name.startsWith("kotlin-test-")

val repositoryVersion: String by lazy {
    LocalDateTime.now().format(
        DateTimeFormatterBuilder()
            .append(DateTimeFormatter.BASIC_ISO_DATE)
            .appendValue(ChronoField.HOUR_OF_DAY, 2)
            .toFormatter()
    )
}

fun <T> T?.or(other: T): T = this ?: other

fun <T> T?.or(block: () -> T): T = this ?: block()

fun <T> T?.ifNull(block: () -> Unit): T? = this ?: block().let { null }

fun <T> T?.throwIfNull(block: () -> Exception) = this ?: throw block()

internal fun URL.resolveRedirection() = with(openConnection() as HttpURLConnection) {
    instanceFollowRedirects = false
    inputStream.use {
        when (responseCode) {
            HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP -> URL(this@resolveRedirection, getHeaderField("Location"))
            else -> this@resolveRedirection
        }
    }.also { disconnect() }
}

internal fun checkGradleVersion() {
    if (GradleVersion.current() < GradleVersion.version(MINIMAL_SUPPORTED_GRADLE_VERSION)) {
        throw PluginInstantiationException("$PLUGIN_NAME requires Gradle $MINIMAL_SUPPORTED_GRADLE_VERSION and higher")
    }
}

fun <T> Property<T>.isSpecified() = isPresent && when (val value = orNull) {
    null -> false
    is String -> value.isNotEmpty()
    is RegularFile -> value.asFile.exists()
    else -> true
}

internal val FileSystemLocation.asPath
    get() = asFile.toPath().absolute()

internal val <T : FileSystemLocation> Provider<T>.asFile
    get() = get().asFile

internal val <T : FileSystemLocation> Provider<T>.asPath
    get() = get().asFile.toPath().absolute()

internal val <T : FileSystemLocation> Provider<T>.asFileOrNull
    get() = orNull?.asFile

internal val <T : FileSystemLocation> Provider<T>.asPathOrNull
    get() = orNull?.asFile?.toPath()?.absolute()

internal val Project.sourceSets
    get() = extensions.getByName("sourceSets") as SourceSetContainer

val Gradle.projectCacheDir
    get() = startParameter.projectCacheDir ?: this.rootProject.projectDir.resolve(".gradle")

internal fun String.toVersion() = Version.parse(this)

internal fun String.toIntelliJPlatformType() = IntelliJPlatformType.fromCode(this)
