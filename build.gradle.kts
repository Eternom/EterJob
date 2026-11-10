plugins {
    id("java-library")
    id("maven-publish")
}

repositories {
    // PaperMC en premier : Maven Central limite les téléchargements (429)
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
    // Plugins Eter (EterLib, API des autres plugins) : le jar de leur release GitHub (publiée par la CI à chaque tag)
    ivy {
        url = uri("https://github.com/Eternom/")
        patternLayout { artifact("[module]/releases/download/[revision]/[module]-[revision].[ext]") }
        metadataSources { artifact() }
        content { includeGroup("com.github.Eternom") }
    }
    // Autres dépendances publiées sur JitPack (VaultAPI...)
    maven("https://jitpack.io") { content { excludeGroup("com.github.Eternom") } }
    // Repli : EterLib publié sur cette machine (`gradlew publishToMavenLocal` dans EterLib), pour tester avant de pousser
    mavenLocal()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")

    // Socle commun : base, Redis, langues, menus, actions (plugin EterLib installé sur le serveur)
    compileOnly("com.github.Eternom:EterLib:1.25.0")
    // PNJ (softdepend) : marqueur « ! » au-dessus du donneur de quêtes, nettoyage des anciens PNJ d'EterMarket
    compileOnly("com.github.Eternom:EterNpc:2.0.0")
    // Argent : l'API d'EterEconomy (chaque mouvement avec sa source)
    compileOnly("com.github.Eternom:EterEconomy:2.2.2")
    // Le moteur des quêtes (depend) : les quêtes de métier sont des quêtes d'EterQuest
    compileOnly("com.github.Eternom:EterQuest:1.0.0")

    // Tests de la logique pure (niveaux, objectifs)
    testImplementation("io.papermc.paper:paper-api:26.2.build.129-stable")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.test {
    useJUnitPlatform()
}

tasks {
    processResources {
        val props = mapOf("version" to version)
        // Déclarée comme entrée : sinon le cache de Gradle réutilise un plugin.yml avec l'ancienne version
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}

// Après chaque build, copie le jar dans <eterPluginsDir>/paper et y supprime l'ancienne version de ce plugin.
// eterPluginsDir se règle dans ~/.gradle/gradle.properties (propre à ta machine) : sans lui, rien n'est copié (JitPack...).
val deployPlugin by tasks.registering(Copy::class) {
    description = "Copie le jar dans le dossier de plugins local (eterPluginsDir)"
    // Variables locales à la tâche : le cache de configuration de Gradle refuse les variables du script
    val eterPluginsDir = providers.gradleProperty("eterPluginsDir")
    val enabled = eterPluginsDir.isPresent
    onlyIf { enabled }
    val jarName = tasks.jar.flatMap { it.archiveBaseName }
    from(tasks.jar)
    into(eterPluginsDir.map { "$it/paper" }.orElse(layout.buildDirectory.dir("deploy").map { it.asFile.path }))
    doFirst {
        destinationDir.listFiles { file -> file.name.startsWith(jarName.get() + "-") && file.name.endsWith(".jar") }
            ?.forEach { it.delete() }
    }
}
tasks.build { finalizedBy(deployPlugin) }

// Publié pour les autres plugins (son API, fr.eternom.eterJob.api) : compileOnly("com.github.Eternom:EterJob:<tag>")
publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "EterJob"
            from(components["java"])
        }
    }
}
