plugins {
    id("com.android.application")
}

fun gitOutput(vararg args: String): String = providers.exec {
    workingDir(rootProject.projectDir)
    commandLine("git", *args)
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim()

val revision = gitOutput("rev-parse", "--short=7", "HEAD").ifEmpty { "uncommitted" }
val dirty = gitOutput("status", "--porcelain").isNotEmpty()

android {
    namespace = "com.stone.pintop"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.stone.pintop"
        minSdk = 30
        targetSdk = 35
        buildConfigField("String", "REVISION", "\"$revision\"")
        buildConfigField("boolean", "DIRTY", dirty.toString())
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "com.stone.pintop.PinInstrumentation"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
