import java.util.Properties

// Trending settings live in local.properties (gitignored, see local.properties.example): tmdb.key, tmdb.region, tmdb.feed
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

// UI font: app/fonts/inter.ttf, or any variable font with wght + opsz axes named by ui.font in local.properties
// (e.g. a font you may not redistribute, kept outside the repo). It's copied in as res/font/ui.ttf; ui.font.opsz
// gives its text and display optical sizes (Inter: 14,32).
val uiFont = localProperties.getProperty("ui.font", "").takeIf { it.isNotBlank() }?.let { file(it) }
    ?.takeIf { it.exists() } ?: file("fonts/inter.ttf")
val uiFontOpsz = localProperties.getProperty("ui.font.opsz", "").ifBlank { "14,32" }.split(",").map { it.trim() }
val generatedRes = layout.buildDirectory.dir("generated/uiFont/res").get().asFile
copy {
    from(uiFont)
    into(File(generatedRes, "font"))
    rename { "ui.ttf" }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "me.prince.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "me.prince.launcher"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "TMDB_KEY", "\"${localProperties.getProperty("tmdb.key", "")}\"")
        buildConfigField("String", "TMDB_REGION", "\"${localProperties.getProperty("tmdb.region", "")}\"")
        buildConfigField("String", "TMDB_FEED", "\"${localProperties.getProperty("tmdb.feed", "all")}\"")
        buildConfigField("float", "UI_FONT_OPSZ_TEXT", "${uiFontOpsz[0]}f")
        buildConfigField("float", "UI_FONT_OPSZ_DISPLAY", "${uiFontOpsz[1]}f")
    }
    sourceSets["main"].res.srcDir(generatedRes)

    buildTypes {
        release {
            // Sideloaded only, so the debug key is fine; R8 makes Compose noticeably faster on low-end TV hardware
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.palette)
}
