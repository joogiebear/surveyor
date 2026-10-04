plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.6.1"
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":mcp"))
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")
    // Also listed under `libraries` in plugin.yml, which is where Paper downloads it at runtime.
    compileOnly("org.xerial:sqlite-jdbc:3.53.4.0")
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveBaseName.set("Surveyor")
    archiveClassifier.set("")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
