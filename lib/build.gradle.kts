/*
 * This file was generated by the Gradle 'init' task.
 *
 * This generated file contains a sample Java library project to get you started.
 * For more details take a look at the 'Building Java & JVM projects' chapter in the Gradle
 * User Manual available at https://docs.gradle.org/7.3/userguide/building_java_projects.html
 */

plugins {
    // Apply the java-library plugin for API and implementation separation.
    `java-library`
    `maven-publish`
}

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
}

dependencies {
    // Use JUnit Jupiter for testing.
    testImplementation("org.junit.jupiter:junit-jupiter:5.7.2")

    // Use sync driver for testing
    testImplementation("org.mongodb:mongodb-driver-sync:4.0.0")

    testImplementation("ch.qos.logback:logback-classic:1.2.9")

    // This dependency is exported to consumers, that is to say found on their compile classpath.
    api("org.mongodb:mongodb-driver-core:4.0.0")
}

tasks.named<Test>("test") {
    // Use JUnit Platform for unit tests.
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "org.mongodb"
            artifactId = "mongodb-ftdc"
            version = "1.0-SNAPSHOT"

            from(components["java"])
        }
    }
}

