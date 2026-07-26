package com.example.scifilauncher

/** Tracks which app is currently "owned" as unlocked, plus a short grace window right after an
 * unlock so the same app relaunching into the foreground isn't immediately re-gated as a fresh
 * entry. Reconstructed after an accidental deletion (original had no git history) - rebuilt to
 * match every call site still present in MainActivity.kt and ScifiAccessibilityService.kt. */
object AppLockCoordinator {
    private const val GRACE_WINDOW_MS = 3000L

    private var authorizedAtMs: Long = 0L
    private var activePackage: String? = null

    fun markAuthorized() {
        authorizedAtMs = System.currentTimeMillis()
    }

    fun isWithinGrace(): Boolean =
        authorizedAtMs != 0L && System.currentTimeMillis() - authorizedAtMs < GRACE_WINDOW_MS

    fun markActive(pkg: String) {
        activePackage = pkg
    }

    fun isActive(pkg: String): Boolean = activePackage == pkg

    fun clearActive() {
        activePackage = null
    }
}
