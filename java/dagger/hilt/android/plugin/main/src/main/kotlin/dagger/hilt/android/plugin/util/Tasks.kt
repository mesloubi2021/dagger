/*
 * Copyright (C) 2023 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.hilt.android.plugin.util

import com.google.devtools.ksp.gradle.KspTaskJvm
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.internal.KaptTask

internal fun addJavaTaskProcessorOptions(
  project: Project,
  component: ComponentCompat,
  produceArgProvider: (Task) -> CommandLineArgumentProvider
) = project.tasks.withType(JavaCompile::class.java) { task ->
  if (task.name == "compile${component.name.capitalize()}JavaWithJavac") {
    task.options.compilerArgumentProviders.add(produceArgProvider.invoke(task))
  }
}

internal fun addKaptTaskProcessorOptions(
  project: Project,
  component: ComponentCompat,
  produceArgProvider: (Task) -> CommandLineArgumentProvider
) = project.plugins.withId("kotlin-kapt") {
  checkClass("org.jetbrains.kotlin.gradle.internal.KaptTask") {
    """
    The KAPT plugin was detected to be applied but its task class could not be found.

    This is an indicator that the Hilt Gradle Plugin is using a different class loader because
    it was declared at the root while KAPT was declared in a sub-project. To fix this, declare
    both plugins in the same scope, i.e. either at the root (without applying them) or at the
    sub-projects.
    """.trimIndent()
  }
  project.tasks.withType(KaptTask::class.java) { task ->
    if (task.name == "kapt${component.name.capitalize()}Kotlin" ||
        // Task names in shared/src/AndroidMain in KMP projects has a platform suffix.
        task.name == "kapt${component.name.capitalize()}KotlinAndroid") {
      val argProvider = produceArgProvider.invoke(task)
      // TODO: Update once KT-58009 is fixed.
      try {
        // Because of KT-58009, we need to add a `listOf(argProvider)` instead
        // of `argProvider`.
        task.annotationProcessorOptionProviders.add(listOf(argProvider))
      } catch (e: Throwable) {
        // Once KT-58009 is fixed, adding `listOf(argProvider)` will fail, we will
        // pass `argProvider` instead, which is the correct way.
        task.annotationProcessorOptionProviders.add(argProvider)
      }
    }
  }
}

internal fun addKspTaskProcessorOptions(
  project: Project,
  component: ComponentCompat,
  produceArgProvider: (Task) -> CommandLineArgumentProvider
) = project.plugins.withId("com.google.devtools.ksp") {
  checkClass("com.google.devtools.ksp.gradle.KspTaskJvm") {
    """
    The KSP plugin was detected to be applied but its task class could not be found.

    This is an indicator that the Hilt Gradle Plugin is using a different class loader because
    it was declared at the root while KSP was declared in a sub-project. To fix this, declare
    both plugins in the same scope, i.e. either at the root (without applying them) or at the
    sub-projects.

    See https://github.com/google/dagger/issues/3965 for more details.
    """.trimIndent()
  }
  project.tasks.withType(KspTaskJvm::class.java) { task ->
    if (task.name == "ksp${component.name.capitalize()}Kotlin" ||
        // Task names in shared/src/AndroidMain in KMP projects has a platform suffix.
        task.name == "ksp${component.name.capitalize()}KotlinAndroid") {
      task.commandLineArgumentProviders.add(produceArgProvider.invoke(task))
    }
  }
}

private inline fun checkClass(fqn: String, msg: () -> String) {
  try {
    Class.forName(fqn)
  } catch (ex: ClassNotFoundException) {
    throw IllegalStateException(msg.invoke(), ex)
  }
}

internal fun Task.isKspTask(): Boolean = try {
  val kspTaskClass = Class.forName("com.google.devtools.ksp.gradle.KspTask")
  kspTaskClass.isAssignableFrom(this::class.java)
} catch (ex: ClassNotFoundException) {
  false
}

