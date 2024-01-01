/*
Copyright 2023 c-fraser

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
import com.github.gradle.node.yarn.task.YarnTask
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

@Suppress("DSL_SCOPE_VIOLATION")
plugins {
  application
  alias(libs.plugins.buildconfig)
  alias(libs.plugins.nodejs)
  alias(libs.plugins.shadow)
}

application { mainClass.set("io.github.cfraser.graphguard.app.Main") }

dependencies {
  implementation(rootProject)
  implementation(libs.clikt)
  implementation(libs.ktor.server.core)
  implementation(libs.ktor.server.netty)
  runtimeOnly(libs.logback.classic)
  runtimeOnly(libs.logback.encoder)

  testImplementation(testFixtures(rootProject))
}

buildConfig {
  packageName("${rootProject.group}.${rootProject.name}".replace("-", ""))
  buildConfigField(String::class.simpleName!!, "VERSION", "\"${rootProject.version}\"")
}

node {
  download = false
  nodeProjectDir = file("web")
}

tasks {
  withType<Test> {
    dependsOn(":spotlessKotlin")
    testLogging { showStandardStreams = true }
  }

  val yarnBuild by
      creating(YarnTask::class) {
        dependsOn(yarn)
        args = listOf("build")
        doLast {
          copy {
            from(file("web/dist"))
            into(file("src/main/resources/web"))
          }
        }
      }

  withType<ProcessResources> { dependsOn(yarnBuild) }

  val cleanWeb by creating {
    doLast {
      file("src/main/resources/web").deleteRecursively()
      file("web/dist").deleteRecursively()
    }
  }

  clean { finalizedBy(cleanWeb) }

  val shadowJar = withType<ShadowJar> { archiveClassifier.set("") }
  distZip { mustRunAfter(shadowJar) }
  distTar { mustRunAfter(shadowJar) }
  startScripts { mustRunAfter(shadowJar, ":spotlessKotlin") }
  startShadowScripts { mustRunAfter(jar, ":spotlessKotlin") }
}
