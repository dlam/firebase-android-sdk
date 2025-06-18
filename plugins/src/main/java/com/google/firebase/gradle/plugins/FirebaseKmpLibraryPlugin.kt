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
import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import com.android.build.gradle.api.KotlinMultiplatformAndroidPlugin
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
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
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
    @Suppress("UnstableApiUsage")
    project.apply<KotlinMultiplatformAndroidPlugin>()
    project.configureApiTasks()

    setupStaticAnalysis(project, firebaseExtension)
    getIsPomValidTask(project, firebaseExtension)
    setupVersionCheckTasks(project, firebaseExtension)

    registerMakeReleaseNotesTask(project)

    project.apply<DackkaPlugin>()
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

  // afterEvaluate is required to read extension properties, configurations and kmp targets.
  @Suppress("UnstableApiUsage")
  private fun Project.configureApiTasks() = afterEvaluate {
    val kmpExtension = kmpExtension!!

    // Prioritize android target over jvm for api tracking.
    val androidTarget = kmpExtension.targetOrNull<KotlinMultiplatformAndroidTarget>()
    val jvmTarget = kmpExtension.targetOrNull<KotlinJvmTarget>()
    val compilationInputs = if (androidTarget != null) {
      val androidCompilation: Provider<KotlinCompilation<*>> = provider {
        androidTarget.compilations.findByName(MAIN_COMPILATION_NAME)
      }
      val androidKmpExtension =
        project.extensions.getByType<KotlinMultiplatformAndroidComponentsExtension>()
      MetalavaCompilationInputs(
        compilationProvider = androidCompilation,
        bootClasspath = project.files(androidKmpExtension.sdkComponents.bootClasspath)
      )
    } else if (jvmTarget != null) {
      val jvmCompilation: Provider<KotlinCompilation<*>> = provider {
        jvmTarget.compilations.findByName(MAIN_COMPILATION_NAME)
      }
      MetalavaCompilationInputs(
        compilationProvider = jvmCompilation,
        bootClasspath = configurations
          .getByName(firebaseLibrary.runtimeClasspath)
          .incoming
          .artifactView {
            attributes {
              attribute(Attribute.of("artifactType", String::class.java), "jar")
            }
          }
          .artifacts
          .artifactFiles
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

    apiInfo.configure { classPath = compilationInputs.bootClasspath }
    generateApiTxt.configure { classPath = compilationInputs.bootClasspath }
    docStubs.configure { classPath = compilationInputs.bootClasspath }
  }
}

internal val Project.kmpExtension
  get() = extensions.findByType<KotlinMultiplatformExtension>()

internal inline fun <reified T : KotlinTarget> KotlinMultiplatformExtension.hasTarget(): Boolean {
  return targets.withType<T>().isNotEmpty()
}

internal inline fun <reified T : KotlinTarget> KotlinMultiplatformExtension.target(): T {
  return targets.withType<T>().single()
}

internal inline fun <reified T : KotlinTarget> KotlinMultiplatformExtension.targetOrNull(): T? {
  return targets.withType<T>().singleOrNull()
}
