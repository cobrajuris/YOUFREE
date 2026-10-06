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
        versionCode = 2
        versionName = "2.0"
    }

    signingConfigs {
        // Chave fixa do app pessoal: cada APK novo instala por cima do anterior sem desinstalar.
        // Não use esta chave para outros apps (ela é pública neste repositório).
        create("ilha") {
            storeFile = file("ilha-release.keystore")
            storePassword = "ilhaassistente"
            keyAlias = "ilha"
            keyPassword = "ilhaassistente"
        }
    }

    buildTypes {
        release {
            // O SDK do Claude usa Jackson (reflexão); manter sem minificação evita regras ProGuard extras.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ilha")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
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
