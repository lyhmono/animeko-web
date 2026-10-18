plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
    application
}

group = "com.animeko"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.animeko.web.MainKt")
    applicationName = "animeko-web"
}

dependencies {
    implementation("io.ktor:ktor-server-core:3.1.3")
    implementation("io.ktor:ktor-server-netty:3.1.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.1.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.3")
    implementation("io.ktor:ktor-server-status-pages:3.1.3")
    implementation("io.ktor:ktor-server-compression:3.1.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("ch.qos.logback:logback-classic:1.6.5")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.ktor:ktor-server-test-host:3.1.3")
}

tasks.test {
    useJUnitPlatform()
    // 测试 JVM 必须自己声明文本编码，不能继承调用者的 locale。
    //
    // 数据源文件名由（多为中文的）数据源名派生，而 java.io 拼文件名用的字符集是
    // sun.jnu.encoding，它跟随**进程启动时**的 locale，`-Dfile.encoding` 管不到它。
    // CI 与很多最小化镜像里 LANG 是空的（POSIX/ANSI_X3.4-1968），那批用中文名导入数据源
    // 的用例会整片失败——那不是产品坏了：产品给出的正是那条指名变量的正确报错。
    // 此前本机的绿灯，只是因为有人恰好 export 过 LANG。
    environment("LANG", "C.UTF-8")
    environment("LC_ALL", "C.UTF-8")
    jvmArgs("-Dfile.encoding=UTF-8")
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = false
    }
}
