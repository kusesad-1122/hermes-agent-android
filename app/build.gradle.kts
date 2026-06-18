plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "com.hermes.agent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hermes.agent"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-hermes-core"

        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
}

// Chaquopy configuration
chaquopy {
    defaultConfig {
        version = "3.12"
        pip {
            // Hermes core dependencies (all pure Python)
            install("openai==2.24.0")
            install("pydantic==1.10.21")  // v1 = pure Python, no pydantic-core needed
            install("python-dotenv==1.2.2")
            install("fire==0.7.1")
            install("httpx==0.28.1")
            install("httpx[socks]==0.28.1")
            install("rich==14.3.3")
            install("tenacity==9.1.4")
            install("certifi==2026.5.20")
            install("requests==2.33.0")
            install("jinja2==3.1.6")
            install("croniter==6.0.0")
            install("packaging==26.0")
            install("pyyaml==6.0.3")
            install("prompt_toolkit==3.0.52")
            install("pathspec==1.1.1")
            install("typing_extensions==4.15.0")
            install("sniffio==1.3.1")
            install("idna==3.18")
            install("distro==2.2.0")
            install("anyio==4.10.0")
            install("h11==0.16.0")
            install("h2==4.2.0")
            install("hpack==4.1.0")
            install("hyperframe==6.1.0")
            install("socksio==1.0.0")
            install("MarkupSafe==3.0.2")
            install("pygments==2.19.2")
            install("markdown-it-py==3.0.0")
            install("mdurl==0.1.2")
            install("jedi==0.19.2")
            install("parso==0.8.4")
            install("decorator==5.2.1")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.code.gson:gson:2.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
