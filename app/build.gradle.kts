import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Ключ DeepSeek читаем из local.properties (файл не попадает в git)
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val deepSeekApiKey: String = localProps.getProperty("deepseek.api.key", "")
val geminiApiKey: String = localProps.getProperty("gemini.api.key", "")
val openRouterApiKey: String = localProps.getProperty("openrouter.api.key", "")

android {
    namespace = "com.example.smartwallet"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.smartwallet"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "DEEPSEEK_API_KEY", "\"$deepSeekApiKey\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        buildConfigField("String", "OPENROUTER_API_KEY", "\"$openRouterApiKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
