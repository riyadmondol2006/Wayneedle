// Root build script: plugins are declared here (apply false) and applied in :app.
// AGP 9 ships built-in Kotlin, so org.jetbrains.kotlin.android is intentionally NOT used.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidx.room) apply false
    // >>> static analysis >>>
    alias(libs.plugins.spotless)
    // <<< static analysis <<<
}

// >>> static analysis >>>
// Style is enforced by ktlint through Spotless; rules live in .editorconfig. Compiler warnings are errors in
// every build and Android Lint runs with warningsAsErrors (see app/build.gradle.kts), so `staticAnalysis`
// is the one command that covers style + lint. Fix style findings with `./gradlew spotlessApply`.
// detekt is deliberately not used: see docs/TESTING.md ("Static analysis").
spotless {
    lineEndings = com.diffplug.spotless.LineEnding.UNIX
    kotlin {
        target("app/src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("*.gradle.kts", "app/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}

tasks.register("staticAnalysis") {
    group = "verification"
    description = "Runs the ktlint style check (spotlessCheck) and Android Lint (:app:lintDebug)."
    dependsOn("spotlessCheck", ":app:lintDebug")
}
// <<< static analysis <<<
