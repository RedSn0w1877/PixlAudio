// The music brain's pure logic (docs/brain-spec.md, section 2.1 and 10).
//
// Kotlin JVM, standard library only. It cannot import an Android class by construction, which is
// the point: everything that decides which music is chosen lives here and is proven equal to the
// Swift transliteration by shared golden vectors. PurityTest keeps banned calls out.
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

// The golden vectors of docs/brain-spec.md, 10.4: `./gradlew :brain:generateGolden` rewrites
// brain/src/test/resources/brain-golden/ from the Kotlin in this module. JVM only: no Android build.
// BrainGoldenTest fails when the files that are checked in differ from a fresh run; the iOS repo
// copies the folder and must match it byte for byte.
tasks.register<JavaExec>("generateGolden") {
    group = "verification"
    description = "Regenerates the brain's golden vectors (brain/src/test/resources/brain-golden)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.theveloper.pixelplay.brain.GoldenGenerator")
    args(layout.projectDirectory.asFile.absolutePath)
}
