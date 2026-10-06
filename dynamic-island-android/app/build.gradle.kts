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
        versionCode = 7
        versionName = "6.1"
        // Vosk ("Oi assistente") traz bibliotecas nativas; só as de celulares reais.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
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
    // Reconhecimento de voz offline para a palavra de ativação "Oi assistente" (github.com/alphacep/vosk-api)
    implementation("com.alphacephei:vosk-android:0.3.75") { isTransitive = false }
    implementation("net.java.dev.jna:jna:5.18.1@aar")
}
