package cn.tracktimer.core

import java.io.Closeable

class ResourceGate<T>(private val release: (T) -> Unit) : Closeable {
    private var cancelled = false
    private var owned: T? = null

    @Synchronized fun accept(resource: T): Boolean {
        if (cancelled) { release(resource); return false }
        check(owned == null) { "Resource already accepted" }
        owned = resource
        return true
    }

    @Synchronized override fun close() {
        cancelled = true
        val resource = owned
        owned = null
        if (resource != null) release(resource)
    }
}
