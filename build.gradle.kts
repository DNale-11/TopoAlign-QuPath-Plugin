plugins { java }

group = "io.github.dnale11"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://maven.scijava.org/content/repositories/releases")
}

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

val qupathHome = providers.gradleProperty("qupathHome")
dependencies {
    if (qupathHome.isPresent) {
        compileOnly(fileTree("${qupathHome.get()}/app") { include("*.jar") })
        testImplementation(fileTree("${qupathHome.get()}/app") { include("*.jar") })
    } else {
        for (module in listOf("qupath-core", "qupath-core-processing", "qupath-gui-fx")) {
            compileOnly("io.github.qupath:$module:0.7.0") { isTransitive = false }
        }
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch")
        val platform = when {
            os.contains("win") -> "win"
            os.contains("mac") -> if (arch == "aarch64") "mac-aarch64" else "mac"
            else -> if (arch == "aarch64") "linux-aarch64" else "linux"
        }
        for (module in listOf("base", "graphics", "controls")) {
            compileOnly("org.openjfx:javafx-$module:25.0.2:$platform")
        }
        compileOnly("com.google.code.gson:gson:2.13.2")
    }
    testImplementation("com.google.code.gson:gson:2.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.test { useJUnitPlatform() }
tasks.jar {
    manifest {
        attributes("Implementation-Title" to "TopoAlign", "Implementation-Version" to project.version,
            "Automatic-Module-Name" to "io.github.dnale11.topoalign")
    }
}
