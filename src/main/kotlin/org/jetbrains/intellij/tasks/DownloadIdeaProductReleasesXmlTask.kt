// Copyright 2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.intellij.tasks

import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Sync
import org.jetbrains.intellij.IntelliJPluginConstants
import org.jetbrains.intellij.IntelliJPluginConstants.IDEA_PRODUCTS_RELEASES_URL
import org.jetbrains.intellij.error
import org.jetbrains.intellij.logCategory

@CacheableTask
abstract class DownloadIdeaProductReleasesXmlTask : Sync() {

    private val context = logCategory()

    init {
        group = IntelliJPluginConstants.PLUGIN_GROUP_NAME
        description = "Downloads XML files containing the IntelliJ IDEA product release information."

        // TODO: migrate to `project.resources.binary` whenever it's available. Ref: https://github.com/gradle/gradle/issues/25237
        from(
            project.resources.text
                .fromUri(IDEA_PRODUCTS_RELEASES_URL)
                .runCatching { asFile("UTF-8") }
                .onFailure { error(context, "Cannot resolve product releases", it) }
                .getOrDefault("<products />")
        ) {
            rename { "idea_product_releases.xml" }
        }
        into(temporaryDir)
    }
}