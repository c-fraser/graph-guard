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
  alias(libs.plugins.kotlin.multiplatform)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.kotlinx.rpc)
}

kotlin {
  jvm()
  js {
    browser()
    binaries.executable()
  }

  sourceSets {
    commonMain {
      dependencies {
        api(libs.kotlinx.rpc.krpc.core)
        implementation(libs.kotlinx.serialization.json)
      }
    }
  }
}
