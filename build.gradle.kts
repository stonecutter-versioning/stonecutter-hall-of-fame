plugins {
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "dev.kikugie"
version = "1.0.1"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.bundles.default)
    implementation(kotlin("reflect"))
}

kotlin {
    jvmToolchain(16)
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    repositories {
        maven {
            name = "kikugieMaven"
            url = uri("https://maven.kikugie.dev/snapshots")
            credentials(PasswordCredentials::class)
            authentication {
                create("basic", BasicAuthentication::class)
            }
        }
    }

    publications {
        register("mavenJava", MavenPublication::class) {
            groupId = project.group.toString()
            artifactId = "hall-of-fame"
            version = project.version.toString()
            from(components["java"])
        }
    }
}