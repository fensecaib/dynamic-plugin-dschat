plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
}

apply(from = "gradle/dynamic-plugin-fatjar.gradle.kts")

group = "top.colter.dynamic"
version = "0.0.3"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    val coroutinesVersion = "1.11.0"
    val coreVersion = "0.0.3"
    val kotlinLoggingVersion = "8.0.4"
    val serializationVersion = "1.11.0"

    compileOnly("top.colter.dynamic:dynamic-bot-core:$coreVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
    compileOnly("io.github.oshai:kotlin-logging-jvm:$kotlinLoggingVersion")

    // Skia 渲染 (由宿主提供)
    compileOnly("org.jetbrains.skiko:skiko-awt:0.148.1")
    compileOnly("top.colter.skiko:skiko-layout:0.0.9")

    // 打包进 plugin fatJar
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.google.zxing:javase:3.5.4")

    testImplementation(kotlin("test"))
    testImplementation("top.colter.dynamic:dynamic-bot-core:$coreVersion")
    testImplementation("io.github.oshai:kotlin-logging-jvm:$kotlinLoggingVersion")
    // 仅供本地战绩绘图快照测试使用；生产插件仍由宿主提供 Skia。
    testImplementation("org.jetbrains.skiko:skiko-awt:0.148.1")
    testImplementation("top.colter.skiko:skiko-layout:0.0.9")
    val testOs = System.getProperty("os.name").lowercase().let {
        when {
            it.contains("win") -> "windows"
            it.contains("mac") -> "macos"
            else -> "linux"
        }
    }
    val testArch = if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) "arm64" else "x64"
    testRuntimeOnly("org.jetbrains.skiko:skiko-awt-runtime-$testOs-$testArch:0.148.1")
}

tasks.test {
    useJUnitPlatform()
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
    jvmToolchain(21)
}
