package org.a4real.skopos.runtime

import android.util.Log
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import org.a4real.skopos.core.SkoposContract
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Vector entry class, listed in META-INF/xposed/java_init.list.
 *
 * The framework instantiates it once per scoped process. Only the Skopos test application is
 * ever hooked; every other package returns immediately.
 *
 * Lifecycle split (matching the observed Vector/LibXposed dispatch order): `onPackageLoaded`
 * fires on LoadedApk.createAppFactory and `onPackageReady` right after
 * createOrUpdateClassLoaderLocked — both BEFORE the target Application object exists, so
 * `ActivityThread.currentApplication()` is still null there. Hook registration does not need
 * the Application (the query entry points are framework classes resolvable through the target
 * classloader), so it happens immediately at package-ready. Only the SELECTED key resolution
 * needs the app's ContentResolver, and that is initialised lazily inside [PolicyCache] —
 * eagerly when the Application already exists, otherwise at the target's first contacts
 * query, which runs after the Application was created in the observed order. No polling,
 * no sleeps, no later callback.
 */
class XposedEntry : XposedModule() {

    private val tag = "SkoposRuntime"
    private val installed = AtomicBoolean(false)

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        if (param.isSystemServer) return
        resolvedLog("Skopos runtime loaded by ${getFrameworkName()} ${getFrameworkVersion()}")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != SkoposContract.TEST_PACKAGE) return
        resolvedLog("target package accepted in ${param.packageName}")

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
     * Installs the contact interceptor once per process. The Application is *not* required for
     * hook registration; the policy cache starts its fail-closed EMPTY snapshot and reads the
     * real scope either here (when the context is already available) or on the target's first
     * contacts query.
     */
    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != SkoposContract.TEST_PACKAGE) return
        if (!installed.compareAndSet(false, true)) return

        resolvedLog("hook registration started")

        val policy = PolicyCache(
            PrefsPolicySource(getRemotePreferences(SkoposContract.POLICY_GROUP)),
            { currentApplication()?.contentResolver?.let(::AppPolicyResolver) },
        )
        ContactsInterceptor(this, policy).install(param.classLoader)

        val application = currentApplication()
        if (application != null) {
            resolvedLog("application context obtained")
            if (policy.ensureInitialized()) {
                resolvedLog("policy cache initialized")
            } else {
                resolvedLog("policy cache init deferred or failed; fail-closed EMPTY until a later query")
            }
        } else {
            resolvedLog("application context not ready at package-ready; policy deferred until the app's first contacts query")
        }
        resolvedLog("ContactsInterceptor installed")
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