import com.github.spotbugs.snom.Confidence
import com.github.spotbugs.snom.Effort
import com.github.spotbugs.snom.SpotBugsTask

plugins {
    java
    id("com.github.spotbugs") version "6.5.13"
}

group = "de.shyrshi"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Ggf. an deinen Paper-Build anpassen (Format: 26.3.build.<nr>-<kanal>).
    // Sollte zur Paper-Version passen, gegen die MockBukkit gebaut ist.
    compileOnly("io.papermc.paper:paper-api:26.3.build.200-stable")

    // --- Tests ---
    testImplementation("io.papermc.paper:paper-api:26.3.build.200-stable")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.3:4.118.0")
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // --- Sicherheitsanalyse ---
    spotbugsPlugins("com.h3xstream.findsecbugs:findsecbugs-plugin:1.14.0")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// SpotBugs + FindSecBugs: `./gradlew spotbugsMain` oder automatisch bei `./gradlew check`/`build`
spotbugs {
    effort.set(Effort.MAX)
    reportLevel.set(Confidence.LOW)          // auch schwache Hinweise anzeigen
    ignoreFailures.set(false)                // Build schlägt bei Funden fehl
    excludeFilter.set(file("config/spotbugs/exclude.xml"))
}

tasks.withType<SpotBugsTask>().configureEach {
    reports.create("html") {
        required.set(true)                   // build/reports/spotbugs/main.html
    }
}

// Testcode nicht analysieren, nur das Plugin selbst
tasks.named("spotbugsTest") {
    enabled = false
}
