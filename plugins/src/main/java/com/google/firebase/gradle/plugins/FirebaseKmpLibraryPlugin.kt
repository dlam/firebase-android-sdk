/*
 * Copyright 2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.firebase.gradle.plugins

import com.android.build.api.dsl.KotlinMultiplatformAndroidTarget
import com.google.firebase.gradle.plugins.LibraryType.KMP
import com.google.firebase.gradle.plugins.semver.ApiDiffer
import com.google.firebase.gradle.plugins.semver.GmavenCopier
import kotlinx.kover.gradle.plugin.KoverGradlePlugin
import kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.attributes.Attribute
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation.Companion.MAIN_COMPILATION_NAME
import org.jetbrains.kotlin.gradle.plugin.KotlinMultiplatformPluginWrapper
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinAndroidTarget
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Plugin for Java Firebase Libraries.
 *
 * ```kts
 * plugins {
 *   id("firebase-kmp-library")
 * }
 * ```
 *
 * @see [BaseFirebaseLibraryPlugin]
 * @see [FirebaseLibraryExtension]
 */
class FirebaseKmpLibraryPlugin : BaseFirebaseLibraryPlugin() {

  override fun apply(project: Project) {
    val firebaseExtension = project.extensions.create<FirebaseLibraryExtension>(
      "firebaseLibrary", project, KMP
    )
    setupDefaults(project, firebaseExtension)

    project.apply<KotlinMultiplatformPluginWrapper>()
    project.configureApiTasks()

    setupStaticAnalysis(project, firebaseExtension)
    getIsPomValidTask(project, firebaseExtension)
    setupVersionCheckTasks(project, firebaseExtension)

    registerMakeReleaseNotesTask(project)

    // TODO(dustin): We need to setup Dackka to be aware of KMP configurations
    // project.apply<DackkaPlugin>()
    project.configureCodeCoverage()
    project.configurePublishing(firebaseExtension)

    // TODO(dustin): Is this needed?
    // reduce the likelihood of kotlin module files colliding.
    project.tasks.withType<KotlinCompile> {
      kotlinOptions.freeCompilerArgs = listOf("-module-name", kotlinModuleName(project))
    }
  }

  private fun Project.configureCodeCoverage() {
    apply<KoverGradlePlugin>()
    extensions.getByType<KoverProjectExtension>().useJacoco.set(true)
  }

  private fun setupVersionCheckTasks(project: Project, firebaseLibrary: FirebaseLibraryExtension) {
    project.tasks.register<GmavenVersionChecker>("gmavenVersionCheck") {
      groupId.value(firebaseLibrary.groupId.get())
      artifactId.value(firebaseLibrary.artifactId.get())
      version.value(firebaseLibrary.version)
      latestReleasedVersion.value(firebaseLibrary.latestReleasedVersion.orElse(""))
    }
    project.mkdir("semver")
    project.tasks.register<GmavenCopier>("copyPreviousArtifacts") {
      dependsOn("jar")
      project.file("semver/previous.jar").delete()
      groupId.value(firebaseLibrary.groupId.get())
      artifactId.value(firebaseLibrary.artifactId.get())
      aarAndroidFile.value(false)
      filePath.value(project.file("semver/previous.jar").absolutePath)
    }
    val currentJarFile =
      project
        .file("build/libs/${firebaseLibrary.artifactId.get()}-${firebaseLibrary.version}.jar")
        .absolutePath
    val previousJarFile = project.file("semver/previous.jar").absolutePath
    project.tasks.register<ApiDiffer>("semverCheck") {
      currentJar.value(currentJarFile)
      previousJar.value(previousJarFile)
      version.value(firebaseLibrary.version)
      previousVersionString.value(
        GmavenHelper(firebaseLibrary.groupId.get(), firebaseLibrary.artifactId.get())
          .getLatestReleasedVersion()
      )

      dependsOn("copyPreviousArtifacts")
    }

    project.tasks.register<CopyApiTask>("copyApiTxtFile") {
      apiTxtFile.set(project.file("api.txt"))
      output.set(project.file("new_api.txt"))
    }

    project.tasks.register<SemVerTask>("metalavaSemver") {
      apiTxtFile.set(project.file("new_api.txt"))
      otherApiFile.set(project.file("api.txt"))
      currentVersionString.value(firebaseLibrary.version)
      previousVersionString.value(firebaseLibrary.previousVersion)
    }
  }

  // afterEvaluate is required to read extension properties, configuration and applied targets.
  private fun Project.configureApiTasks() = afterEvaluate {
    val kmpExtension = kmpExtension!!

    // Prioritize android target over jvm for api tracking.
    val compilationInputs = if (kmpExtension.hasAndroidTarget()) {
      @Suppress("UnstableApiUsage")
      val androidTarget = kmpExtension.targets.withType<KotlinMultiplatformAndroidTarget>().single()
      val androidCompilation: Provider<KotlinCompilation<*>> = provider {
        @Suppress("UnstableApiUsage")
        androidTarget.compilations.findByName(MAIN_COMPILATION_NAME)
      }
      KmpCompilationInputs(
        project = this,
        compilationProvider = androidCompilation,
        bootClasspath = androidJar,
      )
    } else if (kmpExtension.hasJvmTarget()) {
      val jvmTarget = kmpExtension.targets.single { it.platformType == KotlinPlatformType.jvm }
      val jvmCompilation: Provider<KotlinCompilation<*>> = provider {
        jvmTarget.compilations.findByName(MAIN_COMPILATION_NAME)
      }
      KmpCompilationInputs(
        project = this,
        compilationProvider = jvmCompilation,
        bootClasspath = androidJar,
      )
    } else {
      throw GradleException("Failed to setup metalava API tasks as there was no JVM or Android target set.")
    }

    val srcDirs = files(
      compilationInputs.compilationProvider.map { compilation ->
        compilation.allKotlinSourceSets.flatMap { it.kotlin.sourceDirectories }
      }
    )

    val apiInfo = getApiInfo(this, srcDirs)
    val generateApiTxt = getGenerateApiTxt(this, srcDirs)
    val docStubs = getDocStubs(this, srcDirs)

    tasks.getByName("check").dependsOn(docStubs)
    val classpath = configurations
      .getByName(firebaseLibrary.runtimeClasspath)
      .incoming
      .artifactView {
        this.attributes { this.attribute(Attribute.of("artifactType", String::class.java), "jar") }
      }
      .artifacts
      .artifactFiles
    apiInfo.configure { classPath = classpath }
    generateApiTxt.configure { classPath = classpath }
    docStubs.configure { classPath = classpath }
  }
}

internal val Project.kmpExtension
  get() = extensions.findByType<KotlinMultiplatformExtension>()

internal fun KotlinMultiplatformExtension.hasAndroidTarget(): Boolean =
  targets.withType<KotlinAndroidTarget>().isNotEmpty()

internal fun KotlinMultiplatformExtension.hasJvmTarget(): Boolean =
  targets.withType<KotlinJvmTarget>().isNotEmpty()
