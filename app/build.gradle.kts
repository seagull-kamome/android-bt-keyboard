plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "jp.kamome.btkeyboard"
    compileSdk = 35
    buildToolsVersion = "34.0.0"
    defaultConfig {
        applicationId = "jp.kamome.btkeyboard"
        minSdk = 28
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

// 映像の送信用スクリプト（sender.js）と、Chrome 拡張機能（extension.zip）を、アプリの assets に入れる。
// 元は extension/ の 1 か所にあり、Android が配信するページと、拡張機能で、同じスクリプトを使う
val webAssets = layout.buildDirectory.dir("generated/web-assets")
val syncSenderJs = tasks.register<Copy>("syncSenderJs") {
    from(rootProject.file("extension/sender.js"))
    into(webAssets.map { it.dir("js") })
}
val zipExtension = tasks.register<Zip>("zipExtension") {
    from(rootProject.file("extension"))
    archiveFileName.set("extension.zip")
    destinationDirectory.set(webAssets.map { it.dir("zip") })
}
android.sourceSets.getByName("main").assets.srcDir(webAssets.map { it.dir("js") })
android.sourceSets.getByName("main").assets.srcDir(webAssets.map { it.dir("zip") })
tasks.named("preBuild") { dependsOn(syncSenderJs, zipExtension) }

dependencies {
    // 映像の受信: HTTP（送信ページの配信）と WebSocket（映像の受信）を同じポートで扱う小さなサーバー
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")
    // 接続先の QR コードの生成
    implementation("com.google.zxing:core:3.5.3")
    // 共有メニュー（近距離共有など）へ、拡張機能の zip を渡すための FileProvider
    implementation("androidx.core:core:1.13.1")
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
}
