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
plugins {
  application
  alias(libs.plugins.buildconfig)
}

application { mainClass.set("io.github.cfraser.graphguard.cli.Main") }

dependencies {
  implementation(rootProject)
  implementation(libs.clikt)
  runtimeOnly(libs.logback.classic)
}

buildConfig {
  packageName("${rootProject.group}.${rootProject.name}".replace("-", ""))
  buildConfigField(String::class.simpleName!!, "VERSION", "\"${rootProject.version}\"")
}
