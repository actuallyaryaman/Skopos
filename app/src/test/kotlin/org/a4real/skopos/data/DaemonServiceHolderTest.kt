package org.a4real.skopos.data

import io.github.libxposed.service.XposedService
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Bind/death state machine behind the single process-wide daemon connection. Real
 * [XposedService] instances come from the daemon binder and cannot be constructed on the
 * host JVM, so uninitialized instances stand in — only reference identity is exercised,
 * which is exactly what the holder relies on.
 */
class DaemonServiceHolderTest {

    private fun fakeService(): XposedService {
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            .apply { isAccessible = true }
        val unsafe = field.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(XposedService::class.java) as XposedService
    }

    @Test
    fun `bind sets service for late readers`() {
        val holder = DaemonServiceHolder()
        assertNull(holder.current())
        val service = fakeService()
        holder.onBind(service)
        assertSame(service, holder.current())
    }

    @Test
    fun `rebind replaces service`() {
        val holder = DaemonServiceHolder()
        holder.onBind(fakeService())
        val newer = fakeService()
        holder.onBind(newer)
        assertSame(newer, holder.current())
    }

    @Test
    fun `death clears only the identical active service`() {
        val holder = DaemonServiceHolder()
        val active = fakeService()
        holder.onBind(active)
        holder.onDied(fakeService())
        assertSame(active, holder.current())
        holder.onDied(active)
        assertNull(holder.current())
    }

    @Test
    fun `stale death does not clear a newer service`() {
        val holder = DaemonServiceHolder()
        val stale = fakeService()
        holder.onBind(stale)
        val newer = fakeService()
        holder.onBind(newer)
        holder.onDied(stale)
        assertSame(newer, holder.current())
    }
}
