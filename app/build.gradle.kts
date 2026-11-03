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

// Прокси ИИ на Cloudflare Workers (tools/ai-proxy). Если задан — ключи ИИ в релизный APK не попадают
val aiProxyUrl: String = localProps.getProperty("ai.proxy.url", "")

// Откуда приложение берёт сведения об обновлениях (tools/publish.bat кладёт update.json в GitHub Releases)
val updateManifestUrl: String = localProps.getProperty(
    "update.manifest.url",
    "https://github.com/MrKowar/SmartWallet/releases/latest/download/update.json"
)

// Release-ключ подписи: файл и пароли только в local.properties
val releaseStoreFile: String? = localProps.getProperty("release.store.file")

android {
    namespace = "com.example.smartwallet"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.smartwallet"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "1.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "DEEPSEEK_API_KEY", "\"$deepSeekApiKey\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        buildConfigField("String", "OPENROUTER_API_KEY", "\"$openRouterApiKey\"")
        buildConfigField("String", "AI_PROXY_URL", "\"$aiProxyUrl\"")
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$updateManifestUrl\"")
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = localProps.getProperty("release.store.password")
                keyAlias = localProps.getProperty("release.key.alias")
                keyPassword = localProps.getProperty("release.key.password")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            signingConfigs.findByName("release")?.let { signingConfig = it }
            if (aiProxyUrl.isNotBlank()) {
                buildConfigField("String", "DEEPSEEK_API_KEY", "\"\"")
                buildConfigField("String", "GEMINI_API_KEY", "\"\"")
                buildConfigField("String", "OPENROUTER_API_KEY", "\"\"")
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
