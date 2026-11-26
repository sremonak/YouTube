// Diagnostic tool run only in CI (.github/workflows/probe.yml): checks which YouTube player
// clients return playable streams from a datacenter IP (similar to a VPN exit). Not part of the app.
plugins {
    kotlin("jvm") version "2.3.21"
    application
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.27.1")
    implementation("com.squareup.okhttp3:okhttp:5.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}
application { mainClass.set("ProbeKt") }
