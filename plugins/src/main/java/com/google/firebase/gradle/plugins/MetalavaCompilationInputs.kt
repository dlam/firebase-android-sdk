package com.google.firebase.gradle.plugins

import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation

internal class MetalavaCompilationInputs(
  val compilationProvider: Provider<KotlinCompilation<*>>,
  val bootClasspath: FileCollection,
) {

}