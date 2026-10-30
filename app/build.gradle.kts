import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Chaves de API ficam em segredos.properties (na raiz do projeto, fora do Git), nunca no código.
val segredos = Properties().apply {
    val f = rootProject.file("segredos.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.exemplo.novahome"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.exemplo.novahome"
        minSdk = 33
        targetSdk = 36
        versionCode = 31
        versionName = "3.1"
    }

    // "pessoal": com as chaves do segredos.properties (só para os seus aparelhos).
    // "publico": sem nenhuma chave dentro, para distribuir; cada pessoa coloca a sua em Configurações → Contas e chaves.
    flavorDimensions += "versao"
    productFlavors {
        create("pessoal") {
            dimension = "versao"
            buildConfigField("String", "TMDB_KEY", "\"${segredos.getProperty("TMDB_KEY", "")}\"")
            buildConfigField("String", "STEAMGRIDDB_KEY", "\"${segredos.getProperty("STEAMGRIDDB_KEY", "")}\"")
            buildConfigField("String", "YOUTUBE_KEY", "\"${segredos.getProperty("YOUTUBE_KEY", "")}\"")
        }
        create("publico") {
            dimension = "versao"
            buildConfigField("String", "TMDB_KEY", "\"\"")
            buildConfigField("String", "STEAMGRIDDB_KEY", "\"\"")
            buildConfigField("String", "YOUTUBE_KEY", "\"\"")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Só para desenhar o QR code de "Colocar chaves pelo celular" (sem rede, sem reflexão).
    implementation("com.google.zxing:core:3.5.3")
}
