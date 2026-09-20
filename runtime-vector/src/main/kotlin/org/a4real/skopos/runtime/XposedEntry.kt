package org.a4real.skopos.runtime

import android.util.Log
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import org.a4real.skopos.core.SkoposContract

/**
 * Vector entry class, listed in META-INF/xposed/java_init.list.
 *
 * The framework instantiates it once per scoped process and drives it through the
 * XposedModuleInterface lifecycle. Only the Skopos test application is ever hooked; every
 * other package is returned from immediately. In the test app the runtime installs the
 * contact-scope interceptor and binds its policy to the RemotePreferences group the manager
 * app writes.
 */
class XposedEntry : XposedModule() {

    private val tag = "SkoposRuntime"

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        if (param.isSystemServer) return
        resolvedLog("Skopos runtime loaded by ${getFrameworkName()} ${getFrameworkVersion()}")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != SkoposContract.TEST_PACKAGE) return
        resolvedLog("active in ${param.packageName}")

        val probe =
            runCatching {
                val clazz = param.defaultClassLoader.loadClass(SkoposContract.PROBE_CLASS)
                clazz.getDeclaredMethod(SkoposContract.PROBE_METHOD)
            }.getOrElse { e ->
                resolvedLog("Probe target not resolvable: ${e.message}")
                return
            }

        runCatching {
            hook(probe)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { _: Chain -> SkoposContract.HOOKED_RESULT }
        }.onFailure { e -> resolvedLog("probe hook failed: ${e.message}", e) }
    }

    /**
     * Fires after the application context exists; the interceptor and its policy-resolution
     * queries need a live ContentResolver, so remaining setup happens here.
     */
    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != SkoposContract.TEST_PACKAGE) return
        // ActivityThread is hidden API; the module runs with framework access and this
        // reflection is the same call Vector itself uses to find the process Application.
        val application = currentApplication() ?: run {
            resolvedLog("Application context not ready at package-ready; interceptor deferred")
            return
        }
        try {
            val prefs = getRemotePreferences(SkoposContract.POLICY_GROUP)
            val policy = PolicyCache(prefs, application.contentResolver)
            ContactsInterceptor(this, policy).install(param.classLoader)
            resolvedLog("ContactsInterceptor installed")
        } catch (e: Throwable) {
            resolvedLog("interceptor install failed: ${e.message}", e)
        }
    }

    private fun currentApplication(): android.app.Application? = try {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? android.app.Application
    } catch (e: Throwable) {
        null
    }

    fun resolvedLog(message: String, throwable: Throwable? = null) {
        val level = if (throwable == null) Log.INFO else Log.WARN
        log(level, tag, message)
        if (throwable != null) log(level, tag, throwable.toString())
    }
}