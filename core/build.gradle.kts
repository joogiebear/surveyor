plugins {
    `java-library`
}

dependencies {
    // Paper provides Gson at runtime.
    compileOnly("com.google.code.gson:gson:2.14.0")
    testImplementation("com.google.code.gson:gson:2.14.0")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
