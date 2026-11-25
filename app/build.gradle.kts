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

// Chave de upload da Play Store, em assinatura.properties (fora do Git). Sem o arquivo, o release usa a chave debug.
val assinatura = Properties().apply {
    val f = rootProject.file("assinatura.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.gamelaunchertv.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gamelaunchertv.app"
        minSdk = 33
        targetSdk = 36
        versionCode = 35
        versionName = "3.5"
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

    signingConfigs {
        if (assinatura.getProperty("arquivo") != null) create("upload") {
            storeFile = rootProject.file(assinatura.getProperty("arquivo"))
            storePassword = assinatura.getProperty("senha")
            keyAlias = assinatura.getProperty("alias")
            keyPassword = assinatura.getProperty("senha")
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
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
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
    // Resolução e densidade sem root: libera as chamadas internas do serviço de janelas (o Android 9+ bloqueia
    // para apps novos); a permissão WRITE_SECURE_SETTINGS continua sendo exigida pelo sistema.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    // Liberar a permissão da resolução sem PC: pelo Shizuku (ligado pela Depuração sem fio do próprio celular).
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
