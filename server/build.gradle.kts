plugins {
    java
    application
    id("com.google.protobuf") version "0.10.0"
}

group = "io.stellasora"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

sourceSets {
    main {
        proto {
            srcDir("resources/tw/v137/protocol/proto")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.35.0"
    }
}

dependencies {
    implementation("com.google.protobuf:protobuf-java:4.35.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.0")

    testImplementation(platform("org.junit:junit-bom:6.1.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "io.stellasora.server.StellaServer"
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

val portableImageRoot = layout.buildDirectory.dir("jpackage")
val portableBundleRoot = layout.buildDirectory.dir("portable/StellaSoraServer")
val portableProjectVersion = version.toString()

val packagePortableImage = tasks.register<Exec>("packagePortableImage") {
    group = "distribution"
    description = "Builds a Windows app image with StellaSoraServer.exe and a bundled Java runtime."
    notCompatibleWithConfigurationCache("jpackage command line is assembled for the host JDK")
    dependsOn(tasks.installDist)

    val installedLibraries =
            layout.buildDirectory.dir("install/stella-sora-server/lib")
    inputs.dir(installedLibraries)
    outputs.dir(portableImageRoot.map { it.dir("StellaSoraServer") })

    doFirst {
        delete(portableImageRoot)
        val executableName = if (System.getProperty("os.name").startsWith("Windows")) {
            "jpackage.exe"
        } else {
            "jpackage"
        }
        val jpackage = file("${System.getProperty("java.home")}/bin/$executableName")
        require(jpackage.isFile) {
            "jpackage is required to build the portable player release: $jpackage"
        }
        commandLine(
                jpackage,
                "--type", "app-image",
                "--name", "StellaSoraServer",
                "--app-version", "0.1.0",
                "--vendor", "Stella Sora Local Server",
                "--input", installedLibraries.get().asFile,
                "--main-jar", "stella-sora-server-$portableProjectVersion.jar",
                "--main-class", application.mainClass.get(),
                "--dest", portableImageRoot.get().asFile,
                "--win-console")
    }
}

val portableServer = tasks.register<Sync>("portableServer") {
    group = "distribution"
    description = "Assembles the player-facing portable server folder."
    notCompatibleWithConfigurationCache("creates the empty runtime directory after synchronization")
    dependsOn(packagePortableImage)
    into(portableBundleRoot)

    from(portableImageRoot.map { it.dir("StellaSoraServer") })
    from("resources") {
        into("resources")
    }
    from("config.example.json") {
        rename { "config.json" }
    }
    from("README.md")
    from("../server_emulator/Install-FiddlerRule.ps1") {
        into("tools/fiddler")
    }
    from("../server_emulator/Configure-Fiddler.ps1") {
        into("tools/fiddler")
    }
    from("../server_emulator/fiddler/LocalRedirect.example.js") {
        into("tools/fiddler")
    }

    doLast {
        portableBundleRoot.get().dir("runtime").asFile.mkdirs()
    }
}

tasks.register<Zip>("portableServerZip") {
    group = "distribution"
    description = "Creates the self-contained Windows player release archive."
    dependsOn(portableServer)
    archiveFileName = "StellaSoraServer-${project.version}-windows-x64.zip"
    destinationDirectory = layout.buildDirectory.dir("release")
    from(portableBundleRoot) {
        into("StellaSoraServer")
    }
}
