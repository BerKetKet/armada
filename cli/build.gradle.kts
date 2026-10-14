plugins {
    id("com.android.library")
}

android {
    namespace = "app.armada.cli"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":proto"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
    testImplementation("junit:junit:4.13.2")
}
