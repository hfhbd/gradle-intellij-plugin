// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.intellij

import org.gradle.tooling.BuildException

class BuildException(message: String, throwable: Throwable? = null) : BuildException(message, throwable)
