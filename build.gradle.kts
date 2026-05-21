plugins {
    java
    id("com.github.weave-mc.weave-gradle") version "649dba7468"
}

group = "com.atw"
version = "0.1.0"

minecraft.version("1.8.9")

repositories {
    maven("https://jitpack.io")
}

dependencies {
    compileOnly(files("../../java/agents/WeaveLoader.jar"))
}

tasks.compileJava {
    options.release.set(8)
}

tasks.jar {
    archiveBaseName.set("ATWLevelHead")
}
