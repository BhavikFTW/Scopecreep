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
    (project.findProperty("benchy.backend.path") as String?) ?: "backend/python"

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

        // The DPS-150 driver lives at ../backend/drivers/dps150.py (bundled
        // above). We mount /psu/connect, /psu/voltage, /psu/current,
        // /psu/output, /psu/measurements, /psu/state as FRESH routes on the
        // AD FastAPI app in the shim — loading dps150.py by file path avoids
        // the `api` package-name collision between benchy/api (AD) and
        // backend/api (DPS-150).
        val shim = """
            |# Generated by Scopecreep bundleBenchyBackend.
            |# Mounts the AD FastAPI app and adds DPS-150 /psu/* routes that
            |# wrap backend/drivers/dps150.py.
            |import os, importlib.util, logging
            |from fastapi import HTTPException
            |from pydantic import BaseModel
            |
            |from api.server import app  # AD routes
            |
            |_log = logging.getLogger('scopecreep.dps150')
            |_here = os.path.dirname(os.path.abspath(__file__))
            |_dps_dir = os.path.abspath(
            |    os.path.join(_here, os.pardir, 'backend', 'drivers')
            |)
            |_dps_path = os.path.join(_dps_dir, 'dps150.py')
            |_dps_init = os.path.join(_dps_dir, '__init__.py')
            |
            |_driver = None
            |
            |if os.path.isfile(_dps_path) and os.path.isfile(_dps_init):
            |    try:
            |        import sys as _sys
            |        # Register as a uniquely-named package so relative
            |        # imports (e.g. "from .base import InstrumentDriver" in
            |        # dps150.py) resolve without colliding with benchy's own
            |        # drivers/ package.
            |        _pkg_spec = importlib.util.spec_from_file_location(
            |            '_dps_pkg', _dps_init,
            |            submodule_search_locations=[_dps_dir],
            |        )
            |        _pkg = importlib.util.module_from_spec(_pkg_spec)
            |        _sys.modules['_dps_pkg'] = _pkg
            |        _pkg_spec.loader.exec_module(_pkg)
            |
            |        _spec = importlib.util.spec_from_file_location(
            |            '_dps_pkg.dps150', _dps_path,
            |        )
            |        _mod = importlib.util.module_from_spec(_spec)
            |        _sys.modules['_dps_pkg.dps150'] = _mod
            |        _spec.loader.exec_module(_mod)
            |        _DPS150 = _mod.DPS150
            |
            |        class _PortReq(BaseModel):
            |            port: str
            |
            |        class _VoltReq(BaseModel):
            |            volts: float
            |
            |        class _CurrReq(BaseModel):
            |            amps: float
            |
            |        class _OutReq(BaseModel):
            |            enabled: bool
            |
            |        def _require():
            |            if _driver is None or not getattr(_driver, 'is_connected', False):
            |                raise HTTPException(status_code=503, detail='DPS-150 not connected')
            |            return _driver
            |
            |        @app.post('/psu/connect')
            |        def _dps_connect(req: _PortReq):
            |            global _driver
            |            baud = int(os.environ.get('PSU_BAUD', '115200'))
            |            timeout = float(os.environ.get('PSU_TIMEOUT', '0.5'))
            |            if _driver and getattr(_driver, 'is_connected', False):
            |                _driver.disconnect()
            |            _driver = _DPS150(port=req.port, baud=baud, timeout=timeout)
            |            try:
            |                _driver.connect()
            |            except Exception as exc:  # noqa: BLE001
            |                _driver = None
            |                raise HTTPException(status_code=503, detail=f'cannot open {req.port}: {exc}')
            |            return {'status': 'connected', 'port': req.port}
            |
            |        @app.delete('/psu/disconnect')
            |        def _dps_disconnect():
            |            global _driver
            |            if _driver:
            |                _driver.disconnect()
            |                _driver = None
            |            return {'status': 'disconnected'}
            |
            |        @app.get('/psu/measurements')
            |        def _dps_measurements():
            |            return _require().get_measurements()
            |
            |        @app.get('/psu/state')
            |        def _dps_state():
            |            return _require().get_all_state()
            |
            |        @app.post('/psu/voltage')
            |        def _dps_voltage(req: _VoltReq):
            |            _require().set_voltage(req.volts)
            |            return {'status': 'ok', 'voltage_set': req.volts}
            |
            |        @app.post('/psu/current')
            |        def _dps_current(req: _CurrReq):
            |            _require().set_current(req.amps)
            |            return {'status': 'ok', 'current_set': req.amps}
            |
            |        @app.post('/psu/output')
            |        def _dps_output(req: _OutReq):
            |            _require().enable_output(req.enabled)
            |            return {'status': 'ok', 'output_enabled': req.enabled}
            |
            |        _log.info('DPS-150 routes mounted on /psu/*')
            |    except Exception as _exc:
            |        _log.warning('DPS-150 mount failed: %s', _exc)
            |else:
            |    _log.warning('dps150.py not found at %s — DPS-150 routes disabled', _dps_path)
            |
            |if __name__ == '__main__':
            |    import uvicorn
            |    uvicorn.run(app, host='0.0.0.0', port=8000)
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
