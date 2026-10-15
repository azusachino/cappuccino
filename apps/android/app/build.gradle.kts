import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.ktfmt)
}

android {
  namespace = "com.azusachino.cappuccino"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.azusachino.cappuccino"
    minSdk = 26
    targetSdk = 37
    versionCode = 2
    versionName = "0.1.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  lint {
    warningsAsErrors = true
    abortOnError = true
    disable += listOf("AndroidGradlePluginVersion", "GradleDependency", "NewerVersionAvailable")
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
    allWarningsAsErrors.set(true)
  }
}

ktfmt { googleStyle() }

dependencies {
  implementation(platform(libs.compose.bom))
  implementation(libs.compose.material3)
  implementation(libs.commonmark)
  implementation(libs.mikepenz.markdown.m3)
  implementation(libs.mikepenz.markdown.code)
  implementation(libs.kotlin.textmate.core)
  implementation(libs.activity.compose)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.okhttp)
  implementation(libs.lifecycle.runtime.compose)
  implementation(libs.lifecycle.viewmodel.compose)
  implementation(libs.lifecycle.viewmodel.savedstate)
  implementation(libs.coroutines.android)
  testImplementation(libs.junit)
  testImplementation(libs.mockwebserver)
  testImplementation(libs.coroutines.test)
  androidTestImplementation(platform(libs.compose.bom))
  androidTestImplementation(libs.compose.ui.test)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.test.runner)
  // Compose's transitive Espresso 3.5.0 uses InputManager reflection removed on API 36.
  androidTestImplementation(libs.espresso.core)
  debugImplementation(libs.compose.ui.test.manifest)
}
