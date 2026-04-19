import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.commonmark:commonmark:0.22.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    intellijPlatform {
        intellijIdea("2025.2.6.1")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        description =
            "Scopecreep — software-defined hardware control inside JetBrains IDEs. " +
                "Drives real lab instruments (PSU, oscilloscope) through a managed Python sidecar."
    }
}

// -------------------------------------------------------------------------
// Bundle the jbhack Python agent backend (python/{api,agent,drivers,schdoc})
// into plugin resources under /sidecar/benchy/. Emits a manifest so the
// runtime SidecarManager can enumerate + extract entries without scanning
// the JAR. Source path defaults to ../python and is overridable via the
// gradle property `benchy.backend.path`.
// -------------------------------------------------------------------------
val benchyBackendSrc: String =
    (project.findProperty("benchy.backend.path") as String?) ?: "../python"

val bundleBenchyBackend = tasks.register("bundleBenchyBackend") {
    group = "build"
    description = "Copy jbhack agent backend into resources/sidecar/benchy/ + manifest."

    val srcDir = file(benchyBackendSrc)
    val outDir = layout.buildDirectory.dir("generated/benchy-resources/sidecar/benchy").get().asFile
    if (srcDir.exists()) {
        inputs.dir(srcDir).withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    }
    outputs.dir(layout.buildDirectory.dir("generated/benchy-resources/sidecar/benchy"))

    doLast {
        outDir.deleteRecursively()
        outDir.mkdirs()

        if (!srcDir.exists()) {
            throw GradleException(
                """
                |bundleBenchyBackend: backend source not found at $srcDir.
                |
                |If you cloned this repo without submodules, run:
                |    git submodule update --init --recursive
                |
                |To point the bundler at a checkout outside the submodule, rebuild with:
                |    ./gradlew buildPlugin -Pbenchy.backend.path=/absolute/path/to/python
                """.trimMargin()
            )
        }

        // Packages under python/ that must be bundled for both the REST
        // agent worker and the run_test CLI path.
        val includes = listOf(
            "api", "agent", "drivers", "schdoc",
            "planner", "executor", "evaluator", "cli",
        )
        val manifest = mutableListOf<String>()

        includes.forEach { pkg ->
            val pkgDir = srcDir.resolve(pkg)
            if (!pkgDir.exists()) {
                logger.warn("bundleBenchyBackend: missing package $pkgDir — skipping.")
                return@forEach
            }
            pkgDir.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = srcDir.toPath().relativize(f.toPath()).toString().replace('\\', '/')
                if (rel.contains("__pycache__") ||
                    rel.contains("/tests/") ||
                    rel.endsWith(".pyc") ||
                    rel.endsWith(".pyo")
                ) return@forEach
                val dst = outDir.toPath().resolve(rel).toFile()
                dst.parentFile.mkdirs()
                f.copyTo(dst, overwrite = true)
                manifest.add(rel)
            }
        }

        // Standalone file used by the CLI's usage tracker.
        srcDir.resolve("_openai_usage.py").takeIf { it.exists() }?.let { f ->
            f.copyTo(outDir.resolve("_openai_usage.py"), overwrite = true)
            manifest.add("_openai_usage.py")
        }

        // The DPS-150 adapter in executor/drivers.py loads
        // backend/drivers/dps150.py via importlib from repo-root. Mirror that
        // structure inside the bundle by copying the backend/drivers dir to a
        // sibling path the CLI can resolve.
        val backendDrivers = srcDir.parentFile?.resolve("backend/drivers")
        if (backendDrivers != null && backendDrivers.exists()) {
            val dst = outDir.parentFile!!.resolve("backend/drivers")
            dst.mkdirs()
            backendDrivers.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = backendDrivers.toPath().relativize(f.toPath()).toString().replace('\\', '/')
                if (rel.contains("__pycache__") || rel.endsWith(".pyc")) return@forEach
                val target = dst.toPath().resolve(rel).toFile()
                target.parentFile.mkdirs()
                f.copyTo(target, overwrite = true)
                // Manifest tracks paths relative to the benchy/ root; use a
                // prefix so the extractor knows to place them one level up.
                manifest.add("../backend/drivers/$rel")
            }
        } else {
            logger.warn("bundleBenchyBackend: backend/drivers/ not found alongside $srcDir — DPS-150 CLI path will break.")
        }

        val shim = """
            |# Generated by Scopecreep bundleBenchyBackend.
            |# Re-exports the jbhack FastAPI app so `uvicorn agent_worker:app` works
            |# when launched with cwd = this directory.
            |from api.server import app  # noqa: F401
            |
            |if __name__ == "__main__":
            |    import uvicorn
            |    uvicorn.run(app, host="0.0.0.0", port=8000)
            |""".trimMargin()
        outDir.resolve("agent_worker.py").writeText(shim)
        manifest.add("agent_worker.py")

        outDir.resolve("benchy-manifest.txt").writeText(manifest.sorted().joinToString("\n"))
        logger.lifecycle("bundleBenchyBackend: bundled ${manifest.size} files from $srcDir")
    }
}

sourceSets.named("main") {
    resources.srcDir(layout.buildDirectory.dir("generated/benchy-resources"))
}

tasks.named("processResources") {
    dependsOn(bundleBenchyBackend)
}
