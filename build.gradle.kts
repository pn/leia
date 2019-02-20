import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val jvmVersion = "1.8"
val ktorVersion = "1.1.2"

buildscript {
    repositories {
        mavenCentral()
        maven("https://plugins.gradle.org/m2/")
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.3.21")
        classpath("org.junit.platform:junit-platform-gradle-plugin:1.2.0")
        classpath("com.github.jengelman.gradle.plugins:shadow:4.0.4")
    }
}

plugins {
    id("com.google.cloud.tools.jib") version "1.0.0"
    `java-library`
    kotlin("jvm") version "1.3.21"
}

apply(plugin = "kotlin")
apply(plugin = "java")
apply(plugin = "maven")
apply(plugin = "idea")

group = "se.zensum"
version = "1.0-SNAPSHOT"
description = "Receive incoming HTTP requests/webhooks and write them to a Kafka topic"

defaultTasks("run")

/*jib {
    to {
        image = "zensum/leia:" + System.getenv("CIRCLE_SHA1")
    }
}*/ // TODO

apply(plugin = "org.junit.platform.gradle.plugin")
apply(plugin = "com.github.johnrengelman.shadow")

repositories {
    jcenter()
    mavenCentral()
    maven("https://dl.bintray.com/kotlin/kotlinx")
    maven("https://dl.bintray.com/kotlin/ktor")
    maven("https://jitpack.io")
}

val integrationTestImplementation: Configuration by configurations.creating {
    extendsFrom(configurations["testImplementation"])
}

dependencies {
    // Kotlin
    "implementation"("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.3.21")
    "implementation"("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.1.1")
    "implementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.1.1")
    "testImplementation"("org.jetbrains.kotlin:kotlin-test-junit:1.3.21")

    // Junit
    "testImplementation"("org.junit.jupiter:junit-jupiter-api:5.4.0")
    "testRuntime"("org.junit.platform:junit-platform-launcher:1.2.0")
    "testRuntime"("org.junit.jupiter:junit-jupiter-engine:5.4.0")

    // Integration tests
    "integrationTestImplementation"("junit:junit:4.12")
    "integrationTestImplementation"("org.testcontainers:testcontainers:1.10.6")

    // Logging
    "implementation"("io.github.microutils:kotlin-logging:1.6.25")

    // Project specific dependencies (disabled by default)
    "implementation"("ch.vorburger:fswatch:1.1.0")
    "implementation"("com.github.zensum:franz:55be4a2")
    "implementation"("com.github.zensum:webhook-proto:0.1.2")
    "implementation"("com.github.zensum:ktor-prometheus-feature:0.6")
    "implementation"("com.github.zensum:ktor-sentry-feature:fde5bc8f")
    "implementation"("com.github.zensum:ktor-jwt:5dc52cb")
    "implementation"("com.github.zensum:ktor-health-check:011a5a8")
    "implementation"("io.ktor:ktor-server-core:$ktorVersion")
    "implementation"("io.ktor:ktor-server-netty:$ktorVersion")
    "implementation"("io.ktor:ktor-server-test-host:$ktorVersion")
    "testImplementation"("io.ktor:ktor-server-test-host:$ktorVersion")
    "implementation"("com.moandjiezana.toml:toml4j:0.7.2")
    "implementation"("com.github.jonross:fauxflake:90abbcf5b6")
    "implementation"("com.github.mantono:pyttipanna:1.0.0")
    "implementation"("com.google.code.gson:gson:2.8.5")
    "implementation"(group = "com.github.everit-org.json-schema", name = "org.everit.json.schema", version = "1.11.0")
    "implementation"(group = "redis.clients", name = "jedis", version = "3.0.1")
    "implementation"("com.fasterxml.jackson.module:jackson-module-kotlin:2.9.+")
}

tasks {
    // Use the built-in JUnit support of Gradle.
    "test"(Test::class) {
        useJUnitPlatform() {
            includeEngines("junit-jupiter")
        }
    }
}

tasks.withType(Jar::class) {
    manifest {
        attributes["Main-Class"] = "se.zensum.leia.MainKt"
    }
}

tasks.withType(ShadowJar::class) {
    baseName = "shadow"
    classifier = null
    version = null
}

tasks.withType(KotlinCompile::class) {
    sourceCompatibility = jvmVersion
    kotlinOptions {
        jvmTarget = jvmVersion
    }
}

tasks.withType(JavaCompile::class) {
    sourceCompatibility = jvmVersion
    targetCompatibility = jvmVersion
    options.isIncremental = true
    options.encoding = "UTF-8"
}

// Important: All classes containing test cases must match the
// the regex pattern "^.*Tests?$" to be picked up by the junit-gradle plugin.
sourceSets {
    main {
        java.srcDir("src/main/java")
        java.srcDir("src/main/kotlin")
        resources.srcDir("src/main/resources")
    }
    test {
        java.srcDir("src/main/java")
        java.srcDir("src/test/kotlin")
    }
    create("integrationTest") {
        java.srcDir("src/integrationTest/java")
        java.srcDir("src/integrationTest/kotlin")
        resources.srcDir("src/integrationTest/resources")
        compileClasspath += sourceSets["main"].output + configurations["testRuntimeClasspath"]
        runtimeClasspath += output + compileClasspath + sourceSets["test"].runtimeClasspath
    }
}

val mainSourceSet = the<JavaPluginConvention>().sourceSets["main"]

task<JavaExec>("run") {
    main = "se.zensum.leia.MainKt" //Important that "Kt" is appended to class name
    classpath = mainSourceSet.runtimeClasspath
}

task<JavaExec>("debug") {
    debug = true
    environment["DEBUG"] = true
    main = "se.zensum.leia.MainKt" // TODO copy from run
    classpath = mainSourceSet.runtimeClasspath // TODO copy from run
}

tasks.withType<Wrapper> {
    gradleVersion = "5.2.1"
}

/*task integrationTest(type = Test) {
    testClassesDirs = sourceSets.integrationTest.output.classesDirs
    classpath = sourceSets.integrationTest.runtimeClasspath
    useJUnit {
        includeCategories("leia.IntegrationTest")
    }
}*/ // TODO

val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    jvmTarget = "1.8"
}
val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
    jvmTarget = "1.8"
}