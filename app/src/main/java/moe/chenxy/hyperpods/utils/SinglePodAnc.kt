package moe.chenxy.hyperpods.utils

/** Preserve an existing ANC choice across one removal, never enforce ANC continuously. */
class SinglePodAnc {
    private var worn: Int? = null
    private var mode: Int? = null
    private var selectedMode: Int? = null
    private var fallbackAt: Long? = null
    private var restoringAt: Long? = null
    private var retried = false
    val restoring: Boolean get() = restoringAt != null

    fun onWear(count: Int, enabled: Boolean, now: Long): Boolean {
        require(count in 0..2)
        val previous = worn
        worn = count
        val preserve = enabled && previous == 2 && count == 1 && selectedMode == null &&
            (mode == 2 || fallbackAt?.let { withinWindow(it, now) } == true)
        clearTransition()
        if (preserve) restoringAt = now
        return preserve
    }

    fun onMode(value: Int, enabled: Boolean, now: Long): Boolean {
        val previous = mode
        mode = value
        if (selectedMode != null) {
            if (selectedMode == value) selectedMode = null
            clearTransition()
            return false
        }
        if (!enabled || value == 2) {
            clearTransition()
            return false
        }
        // Firmware can publish its fallback mode immediately before the wear event.
        if (worn == 2 && previous == 2) fallbackAt = now
        val started = restoringAt ?: return false
        if (worn != 1 || retried || !withinWindow(started, now)) return false
        retried = true
        return true
    }

    fun selectMode(value: Int) {
        selectedMode = value
        clearTransition()
    }

    fun requestFailed() {
        selectedMode = null
        mode = null
        clearTransition()
    }

    fun cancel() = clearTransition()

    fun reset() {
        worn = null
        mode = null
        selectedMode = null
        clearTransition()
    }

    private fun clearTransition() {
        fallbackAt = null
        restoringAt = null
        retried = false
    }

    private fun withinWindow(start: Long, now: Long) = now >= start && now - start <= 1000
}
