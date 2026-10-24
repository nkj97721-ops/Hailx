plugins {
    alias(libs.plugins.android.application)
}

android {
    val signingProps = file("../signing.properties")
    val commitHash = providers.exec {
        workingDir = rootDir
        commandLine = "git rev-parse --short HEAD".split(" ")
    }.standardOutput.asText.get().trim()
    val commitSubject = providers.exec {
        workingDir = rootDir
        commandLine = "git log -1 --pretty=%s".split(" ")
    }.standardOutput.asText.get().trim()

    namespace = "com.aistra.hail"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aistra.hail"
        minSdk = 23
        targetSdk = 36
        versionCode = 35
        versionName = "1.11.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-g$commitHash"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (!commitSubject.startsWith("[release]")) versionNameSuffix = "-g$commitHash"
            signingConfig = if (signingProps.exists()) {
                val props = `java.util`.Properties().apply { load(signingProps.reader()) }
                signingConfigs.create("release") {
                    storeFile = file(props.getProperty("storeFile"))
                    storePassword = props.getProperty("storePassword")
                    keyAlias = props.getProperty("keyAlias")
                    keyPassword = props.getProperty("keyPassword")
                }
            } else signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
    }
    androidResources {
        generateLocaleConfig = true
        localeFilters += listOf("en", "zh-rCN")
    }
    buildFeatures {
        buildConfig = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach {
            if (it is com.android.build.api.variant.impl.VariantOutputImpl)
                it.outputFileName = "Hail-v${it.versionName.get()}.apk"
        }
    }
}
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}
kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.swiperefreshlayout)
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation(libs.material)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hiddenapibypass)
}
