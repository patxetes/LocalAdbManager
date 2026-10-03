plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.localadb.manager"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.localadb.manager"
        minSdk = 30 // Android 11 mínimo requerido para Wireless ADB
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Solución al error mergeDebugJavaResource:
    // Si hay colisión de licencias o manifiestos en META-INF, tomar el primero y no fallar.
    packaging {
        resources {
            pickFirsts += setOf(
                "META-INF/**",
                "META-INF/versions/**",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }
}

// Solución al error checkDebugDuplicateClasses:
// Fuerza a que cualquier petición de versiones antiguas de BouncyCastle se redirija a la moderna 1.78.1
configurations.all {
    resolutionStrategy.dependencySubstitution {
        substitute(module("org.bouncycastle:bcprov-jdk15on"))
            .using(module("org.bouncycastle:bcprov-jdk18on:1.78.1"))
            .because("Unificar versión de BouncyCastle y evitar clases duplicadas")

        substitute(module("org.bouncycastle:bcprov-jdk15to18"))
            .using(module("org.bouncycastle:bcprov-jdk18on:1.78.1"))
            .because("Unificar versión de BouncyCastle y evitar clases duplicadas")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")

    // Motor ADB y TLS
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    // Criptografía moderna para certificados X.509
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
}