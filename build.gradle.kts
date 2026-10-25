// Root build script: plugins are declared here (apply false) and applied in :app.
// AGP 9 ships built-in Kotlin, so org.jetbrains.kotlin.android is intentionally NOT used.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidx.room) apply false
}
