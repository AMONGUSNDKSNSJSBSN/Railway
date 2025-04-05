plugins {
    java
    `maven-publish`
    id("architectury-plugin") version "3.4-SNAPSHOT"
    id("me.modmuss50.mod-publish-plugin") version "0.7.4" apply false
    id("com.github.johnrengelman.shadow") version "8.1.1" apply false
    id("dev.ithundxr.silk") version "0.11.15"
    id("net.kyori.blossom") version "2.1.0" apply false
    id("org.jetbrains.gradle.plugin.idea-ext") version "1.1.8"
}

println("Steam 'n' Rails v${"mod_version"()}")

val isRelease = System.getenv("RELEASE_BUILD")?.toBoolean() ?: false
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toInt()
val removeDevMixinAnyway = System.getenv("REMOVE_DEV_MIXIN_ANYWAY")?.toBoolean() ?: false
val gitHash = "\"${calculateGitHash() + (if (hasUnstaged()) "-modified" else "")}\""

extra["gitHash"] = gitHash
extra["parchment_version"] = "v2024.11.17" // Replace with the correct version if different
extra["minecraft_version"] = "1.21.1" // Replace with the actual Minecraft version
extra["mod_version"] = "1.0.0" // Replace with the actual mod version

repositories {
    mavenLocal()
    gradlePluginPortal()
    maven { url = uri("https://maven.neoforged.net/releases") }
    maven { url = uri("https://maven.architectury.dev/") }
    maven { url = uri("https://maven.quiltmc.org/repository/release") }
    maven {
        url = uri("https://maven.parchmentmc.org")
        metadataSources {
            mavenPom()
            artifact()
        }
    }
}

dependencies {
    "minecraft"("com.mojang:minecraft:${"minecraft_version"()}")
    "mappings"(loom.layered {
        officialMojangMappings { nameSyntheticMembers = false }
        parchment("org.parchmentmc.data:parchment-${"minecraft_version"()}:${"parchment_version"()}@zip")
    })
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

java {
    withSourcesJar()
}

subprojects {
    apply(plugin = "dev.architectury.loom")
    apply(plugin = "net.kyori.blossom")

    setupRepositories()

    val capitalizedName = project.name.capitalized()

    val loom = project.extensions.getByType<LoomGradleExtensionAPI>()
    loom.apply {
        silentMojangMappingsLicense()
        runs.configureEach {
            vmArg("-XX:+AllowEnhancedClassRedefinition")
            vmArg("-XX:+IgnoreUnrecognizedVMOptions")
            vmArg("-Dmixin.debug.export=true")
            vmArg("-Dmixin.env.remapRefMap=true")
            vmArg("-Dmixin.env.refMapRemappingFile=${projectDir}/build/createSrgToMcp/output.srg")
        }
    }

    configurations.configureEach {
        resolutionStrategy {
            // Remove Fabric loader force
        }
    }

    dependencies {
        "minecraft"("com.mojang:minecraft:${"minecraft_version"()}")
        "mappings"(loom.layered {
            officialMojangMappings { nameSyntheticMembers = false }
            parchment("org.parchmentmc.data:parchment-${"minecraft_version"()}:${"parchment_version"()}@zip")
        })
    }

    publishing {
        publications {
            create<MavenPublication>("maven${capitalizedName}") {
                artifactId = "${"archives_base_name"()}-${project.name}-${"minecraft_version"()}"
                from(components["java"])
            }
        }

        repositories {
            val mavenToken = System.getenv("MAVEN_TOKEN")
            val maven = if (isRelease) "releases" else "snapshots"
            if (mavenToken != null && mavenToken.isNotEmpty()) {
                maven {
                    url = uri("https://mvn.devos.one/${maven}")
                    credentials {
                        username = "ithundxr-github"
                        password = mavenToken
                    }
                }
            }
        }
    }

    if (project.path == ":common") {
        return@subprojects
    }

    apply(plugin = "com.github.johnrengelman.shadow")
    apply(plugin = "me.modmuss50.mod-publish-plugin")

    architectury {
        platformSetupLoomIde()
    }

    tasks.named<RemapJarTask>("remapJar") {
        from("${rootProject.projectDir}/LICENSE")
        val shadowJar = project.tasks.named<ShadowJar>("shadowJar").get()
        inputFile.set(shadowJar.archiveFile)
        injectAccessWidener = true
        dependsOn(shadowJar)
        archiveClassifier = null
        doLast {
            transformJar(outputs.files.singleFile)
        }
    }

    val common: Configuration by configurations.creating
    val shadowCommon: Configuration by configurations.creating
    val development = configurations.maybeCreate("development${capitalizedName}")

    configurations {
        compileOnly.get().extendsFrom(common)
        runtimeOnly.get().extendsFrom(common)
        development.extendsFrom(common)
    }

    dependencies {
        common(project(":common", "namedElements")) { isTransitive = false }
        shadowCommon(project(":common", "transformProduction${capitalizedName}")) { isTransitive = false }
    }

    tasks.named<ShadowJar>("shadowJar") {
        archiveClassifier = "dev-shadow"
        configurations = listOf(shadowCommon)
        exclude("architectury.common.json")
        destinationDirectory = layout.buildDirectory.dir("devlibs").get()
    }

    tasks.processResources {
        from(project(":common").file("src/main/resources")) {
            include("resourcepacks/")
        }

        val properties = mapOf(
            "version" to version,
            "minecraft_version" to "minecraft_version"(),
            "fabric_api_version" to "fabric_api_version"(),
            "fabric_loader_version" to "fabric_loader_version"(),
            "voicechat_api_version" to "voicechat_api_version"(),
            "forge_version" to "forge_version"().split(".")[0],
            "create_forge_version" to "create_forge_version"().split("-")[0],
            "create_fabric_version" to "create_fabric_version"()
        )

        inputs.properties(properties)

        filesMatching(listOf("fabric.mod.json", "META-INF/mods.toml")) {
            expand(properties)
        }
    }

    tasks.jar {
        archiveClassifier = "dev"

        manifest {
            attributes(mapOf("Git-Hash" to gitHash))
        }
    }

    tasks.named<Jar>("sourcesJar") {
        val commonSources = project(":common").tasks.getByName<Jar>("sourcesJar")
        dependsOn(commonSources)
        from(commonSources.archiveFile.map { zipTree(it) })

        manifest {
            attributes(mapOf("Git-Hash" to gitHash))
        }
    }

    components.getByName<AdhocComponentWithVariants>("java") {
        withVariantsFromConfiguration(project.configurations["shadowRuntimeElements"]) {
            skip()
        }
    }
}

fun transformJar(jar: File) {
    val contents = linkedMapOf<String, ByteArray>()
    JarFile(jar).use {
        it.entries().asIterator().forEach { entry ->
            if (!entry.isDirectory) {
                contents[entry.name] = it.getInputStream(entry).readAllBytes()
            }
        }
    }

    jar.delete()

    JarOutputStream(jar.outputStream()).use { out ->
        out.setLevel(Deflater.BEST_COMPRESSION)
        contents.forEach { (name, data) ->
            if (name.startsWith("architectury_inject_${project.name}_common"))
                return@forEach

            val processedData = when {
                name.endsWith(".json") || name.endsWith(".mcmeta") -> JsonOutput.toJson(JsonSlurper().parse(data)).toByteArray()
                name.endsWith(".class") -> transformClass(data)
                else -> data
            }

            out.putNextEntry(JarEntry(name))
            out.write(processedData)
            out.closeEntry()
        }
        out.finish()
        out.close()
    }
}

fun transformClass(bytes: ByteArray): ByteArray {
    val node = ClassNode()
    ClassReader(bytes).accept(node, 0)

    node.methods.removeIf { methodNode: MethodNode -> removeIfDevMixin(node.name, methodNode.visibleAnnotations) }

    return ClassWriter(0).also { node.accept(it) }.toByteArray()
}

fun removeIfDevMixin(nodeName: String, visibleAnnotations: List<AnnotationNode>?): Boolean {
    if (!removeDevMixinAnyway && buildNumber == null && !nodeName.lowercase(Locale.ROOT).matches(Regex(".*\\/mixin\\/.*Mixin")))
        return false

    visibleAnnotations?.forEach { annotationNode ->
        if (annotationNode.desc == "Lcom/railwayteam/railways/annotation/mixin/DevEnvMixin;")
            return true
    }

    return false
}

fun <T> getValueFromAnnotation(annotation: AnnotationNode?, key: String): T? {
    var getNextValue = false

    annotation?.values?.forEach { value ->
        if (getNextValue) {
            @Suppress("UNCHECKED_CAST")
            return value as T
        }
        if (value == key) {
            getNextValue = true
        }
    }

    return null
}

tasks.create("railwaysPublish") {
    when (val platform = System.getenv("PLATFORM")) {
        "both" -> {
            dependsOn(tasks.build, ":forge:publish", ":common:publish", ":forge:publishMods")
        }
        "forge" -> {
            dependsOn("forge:build", "forge:publish", "forge:publishMods")
        }
    }
}

fun Project.setupRepositories() {
    repositories {
        mavenCentral()
        maven("https://maven.shedaniel.me/")
        maven("https://maven.blamejared.com/")
        exclusiveMaven("https://maven.parchmentmc.org", "org.parchmentmc.data")
        exclusiveMaven("https://maven.quiltmc.org/repository/release", "org.quiltmc")
        maven("https://jm.gserv.me/repository/maven-public/")
        exclusiveMaven("https://api.modrinth.com/maven", "maven.modrinth")
        exclusiveMaven("https://cursemaven.com", "curse.maven")
        maven("https://maven.theillusivec4.top/")
        maven("https://maven.tterrag.com/") {
            content {
                includeGroup("com.simibubi.create")
                includeGroup("com.tterrag.registrate")
                includeGroup("com.jozufozu.flywheel")
            }
        }
        maven("https://maven.maxhenkel.de/repository/public")
        maven("https://maven.jamieswhiteshirt.com/libs-release")
        exclusiveMaven("https://thedarkcolour.github.io/KotlinForForge/", "thedarkcolour")
        maven("https://maven.terraformersmc.com/releases/")
        maven("https://mvn.devos.one/snapshots/")
        maven("https://mvn.devos.one/releases/")
        maven("https://maven.cafeteria.dev/releases")
        maven("https://raw.githubusercontent.com/Fuzss/modresources/main/maven/")
        exclusiveMaven("https://maven.ladysnake.org/releases", "dev.onyxstudios.cardinal-components-api")
        maven("https://jitpack.io/") {
            content {
                includeGroupByRegex("com.github.*")
            }
        }
    }
}

fun calculateGitHash(): String {
    return try {
        val stdout = ByteArrayOutputStream()
        exec {
            commandLine("git", "rev-parse", "HEAD")
            standardOutput = stdout
        }
        stdout.toString().trim()
    } catch (ignored: Throwable) {
        "unknown"
    }
}

fun calculateGitBranch(): String {
    return try {
        val stdout = ByteArrayOutputStream()
        exec {
            commandLine("git", "rev-parse", "--abbrev-ref", "HEAD")
            standardOutput = stdout
        }
        stdout.toString().trim()
    } catch (ignored: Throwable) {
        "unknown"
    }
}

fun hasUnstaged(): Boolean {
    return try {
        val stdout = ByteArrayOutputStream()
        exec {
            commandLine("git", "status", "--porcelain")
            standardOutput = stdout
        }
        val result = stdout.toString().replace(Regex("M gradlew(\\.bat)?"), "").trimEnd()
        if (result.isNotEmpty()) println("Found stageable results:\n${result}\n")
        result.isNotEmpty()
    } catch (ignored: Throwable) {
        false
    }
}

fun Project.architectury(action: Action<ArchitectPluginExtension>) {
    action.execute(this.extensions.getByType<ArchitectPluginExtension>())
}

fun RepositoryHandler.exclusiveMaven(url: String, vararg groups: String) {
    exclusiveContent {
        forRepository { maven(url) }
        filter {
            groups.forEach {
                includeGroup(it)
            }
        }
    }
}

operator fun String.invoke(): String {
    return rootProject.ext[this] as? String
        ?: throw IllegalStateException("Property $this is not defined")
}
