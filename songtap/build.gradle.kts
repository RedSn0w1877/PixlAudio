// Song Tap's pure logic (handoff/2026-11-20-song-tap.md).
//
// Kotlin JVM, standard library only, like :brain. It cannot import an Android class by construction;
// everything that decides what a beam is allowed to be, which state a session is in, when two phones
// count as tapped and what the animation looks like at time t lives here, and is proven equal to the
// Swift transliteration by shared golden vectors. PurityTest keeps platform calls and floating point out.
plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    testImplementation(libs.junit.jupiter.api)
    testImplementation(libs.junit.jupiter.params)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junitplatformlauncher)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// `./gradlew :songtap:generateGolden` rewrites songtap/src/test/resources/songtap-golden/ from the Kotlin
// in this module. JVM only: no Android build. SongTapGoldenTest fails when the files that are checked in
// differ from a fresh run; the iOS repo copies the folder byte for byte (Fixtures/songtap) and must match.
tasks.register<JavaExec>("generateGolden") {
    group = "verification"
    description = "Regenerates Song Tap's golden vectors (songtap/src/test/resources/songtap-golden)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.theveloper.pixelplay.songtap.GoldenGenerator")
    args(layout.projectDirectory.asFile.absolutePath)
}
