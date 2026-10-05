plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.youfree.island"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.youfree.island"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // O SDK do Claude usa Jackson (reflexão); manter sem minificação evita regras ProGuard extras.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/{AL2.0,LGPL2.1}",
            )
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.anthropic:anthropic-java:2.68.0")
}
