package com.bidaide.github.ui

/** A tab of the tool window that only talks to `gh` once somebody looks at it. */
interface LazyTab {
    /** False until the first [reload]. */
    val loaded: Boolean

    fun reload()

    fun ensureLoaded() {
        if (!loaded) reload()
    }
}
