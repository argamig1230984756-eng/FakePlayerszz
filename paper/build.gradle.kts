plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.19"
}
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
repositories { mavenCentral() }
dependencies { paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT") }
paperweight.reobfArtifactConfiguration =
    io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
tasks.jar { archiveFileName.set("SkyBlockPlus-Paper.jar") }
