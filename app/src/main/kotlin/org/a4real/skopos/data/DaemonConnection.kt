package org.a4real.skopos.data

import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure bind/death state machine for the daemon service, host-testable: bind sets (rebind
 * replaces), death clears only the still-active instance so a stale death never drops a
 * newer connection, and late readers always observe the current service.
 */
class DaemonServiceHolder {
    @Volatile
    private var active: XposedService? = null

    fun onBind(service: XposedService) {
        active = service
    }

    fun onDied(service: XposedService) {
        if (active === service) active = null
    }

    fun current(): XposedService? = active
}

/**
 * The single process-wide daemon connection. `XposedServiceHelper` stores exactly one static
 * listener (last registration wins), so the whole app process registers exactly once here;
 * per-package [PolicyRepository] instances only read the shared service and must never
 * register listeners themselves — otherwise they steal the callback slot and all other
 * repositories stay permanently disconnected.
 */
object DaemonConnection {
    private val holder = DaemonServiceHolder()
    private val registered = AtomicBoolean(false)

    fun ensure() {
        if (registered.compareAndSet(false, true)) {
            XposedServiceHelper.registerListener(
                object : XposedServiceHelper.OnServiceListener {
                    override fun onServiceBind(xposedService: XposedService) {
                        holder.onBind(xposedService)
                    }

                    override fun onServiceDied(xposedService: XposedService) {
                        holder.onDied(xposedService)
                    }
                },
            )
        }
    }

    val service: XposedService?
        get() = holder.current()

    val connected: Boolean
        get() = service != null
}
