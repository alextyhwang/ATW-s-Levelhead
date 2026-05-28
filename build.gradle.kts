plugins {
    java
    id("com.github.weave-mc.weave-gradle") version "649dba7468"
}

group = "com.atw"
version = "0.2.0"

minecraft.version("1.8.9")

repositories {
    maven("https://jitpack.io")
}

dependencies {
    val weaveLoaderJar = providers.gradleProperty("weaveLoaderJar")
        .orElse(providers.environmentVariable("WEAVE_LOADER_JAR"))
        .orElse("../../java/agents/WeaveLoader.jar")
    compileOnly(files(weaveLoaderJar))
}

tasks.compileJava {
    options.release.set(8)
}

tasks.jar {
    archiveBaseName.set("ATWLevelHead")
}
