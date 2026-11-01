plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "cn.tracktimer.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "cn.tracktimer.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2-probe"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
    buildFeatures { buildConfig = true }
    lint { abortOnError = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation(project(":core"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    testLogging { events("failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
