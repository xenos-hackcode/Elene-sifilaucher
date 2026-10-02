package com.example.scifilauncher

import java.io.Closeable

/** A stopped session can never accept fresh resources from an old DNS worker. */
internal class VpnSessionResources : Closeable {
    private val resources = mutableSetOf<Closeable>()
    @Volatile var isOpen = true
        private set

    @Synchronized fun register(resource: Closeable): Boolean {
        if (!isOpen) {
            runCatching { resource.close() }
            return false
        }
        resources.add(resource)
        return true
    }

    @Synchronized fun release(resource: Closeable) {
        resources.remove(resource)
        runCatching { resource.close() }
    }

    @Synchronized fun whileOpen(action: () -> Unit) {
        if (isOpen) action()
    }

    @Synchronized override fun close() {
        if (!isOpen) return
        isOpen = false
        resources.forEach { runCatching { it.close() } }
        resources.clear()
    }
}
