plugins {
  id("voice.library")
  alias(libs.plugins.metro)
}

kotlin {
  explicitApi()
}

dependencies {
  implementation(projects.core.data.api)
  implementation(projects.core.logging.api)
  implementation(libs.sherpaOnnx)
  implementation(libs.commonsCompress)
  implementation(libs.okhttp)

  testImplementation(libs.bundles.testing.jvm)
  testImplementation(projects.core.data.impl)
}
