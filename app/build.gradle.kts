plugins {
    id("com.android.application")
}

android {
    namespace = "app.armada"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.berketket.armada"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":proto"))
    implementation(project(":daemon"))
    implementation(project(":cli"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
}
