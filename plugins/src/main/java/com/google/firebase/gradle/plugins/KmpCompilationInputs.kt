package com.google.firebase.gradle.plugins

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import java.io.File

internal class KmpCompilationInputs(
    val project: Project,
    val compilationProvider: Provider<KotlinCompilation<*>>,
    val bootClasspath: File?,
) {

}