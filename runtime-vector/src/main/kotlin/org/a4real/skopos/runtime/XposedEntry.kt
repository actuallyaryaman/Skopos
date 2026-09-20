package org.a4real.skopos.runtime

import android.util.Log
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import org.a4real.skopos.core.SkoposContract

/**
 * M0 Vector entry class, listed in META-INF/xposed/java_init.list.
 *
 * The framework instantiates it once per scoped process and drives it through the
 * XposedModuleInterface lifecycle. Only the Skopos test application is ever hooked;
 * every other package is returned from immediately.
 */
class XposedEntry : XposedModule() {

    private val tag = "SkoposRuntime"

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        if (param.isSystemServer) return
        log(Log.INFO, tag, "Skopos runtime loaded by ${getFrameworkName()} ${getFrameworkVersion()}")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != SkoposContract.TEST_PACKAGE) return
        log(Log.INFO, tag, "Skopos runtime active in ${param.packageName}")

        val probe =
            runCatching {
                val clazz = param.defaultClassLoader.loadClass(SkoposContract.PROBE_CLASS)
                clazz.getDeclaredMethod(SkoposContract.PROBE_METHOD)
            }.getOrElse { e ->
                log(Log.WARN, tag, "Probe target not resolvable: ${e.message}")
                return
            }

        runCatching {
            hook(probe)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { _: Chain -> SkoposContract.HOOKED_RESULT }
            log(Log.INFO, tag, "Probe hook installed on ${SkoposContract.PROBE_CLASS}.${SkoposContract.PROBE_METHOD}")
        }.onFailure { e -> log(Log.WARN, tag, "Probe hook failed: ${e.message}", e) }
    }
}