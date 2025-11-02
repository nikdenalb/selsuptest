plugins {
    id("java")
}

group = "kazinard"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.1")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.20.1")
    testImplementation("org.mockito:mockito-junit-jupiter:5.20.0")
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-javaagent:${configurations.testRuntimeClasspath.get().files.find { it.name.contains("mockito-core") }?.absolutePath}")
}

tasks.withType<Test> {
    jvmArgs("-Xshare:off")
}