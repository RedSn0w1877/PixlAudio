// The Music Brain's evaluation and tuning tool (tools/eval/README.md): replays public ListenBrainz listening histories through the
// :brain module's real code (Reward, Affinity, Session, Features, Ranker) and measures the prior, the learner and a baseline.
// JVM only; no Android, no app code. Run it with `./gradlew :eval:run --args="all"`.
plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
    application
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
    implementation(project(":brain"))
}

application {
    mainClass.set("com.theveloper.pixelplay.eval.MainKt")
}

tasks.named<JavaExec>("run") {
    // Paths in the tool are relative to tools/eval (cache/, RESULTS.md).
    workingDir = projectDir
    maxHeapSize = "3g"
}
