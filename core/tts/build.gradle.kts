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

  testImplementation(libs.bundles.testing.jvm)
}
