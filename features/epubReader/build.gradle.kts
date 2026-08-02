plugins {
  id("voice.library")
  id("voice.compose")
  alias(libs.plugins.metro)
}

dependencies {
  implementation(projects.core.common)
  implementation(projects.core.data.api)
  implementation(projects.core.documentfile)
  implementation(projects.core.playback)
  implementation(projects.core.scanner)
  implementation(projects.core.tts)
  implementation(projects.core.ui)
  implementation(projects.navigation)
  implementation(libs.media3.session)

  testImplementation(projects.core.data.impl)
  testImplementation(libs.bundles.testing.jvm)
  testImplementation(libs.molecule)
}
