package moe.chenxy.hyperpods.utils

/** One physical stream, serialized by L2CAPController's lock. A call can preempt
 * a preview, but a late preview stop must never stop a call-owned stream. */
class HeadTrackingStream(private val send: (start: Boolean) -> Boolean) {
    enum class Owner { CALL, PREVIEW }
    private var owner: Owner? = null
    private var interrupted: (() -> Unit)? = null
    var stopPending = false
        private set

    fun acquire(request: Owner, onInterrupted: () -> Unit = {}): Boolean {
        if (stopPending && !retryStop()) return false
        if (owner == request) return true
        if (owner == Owner.CALL) return false
        if (owner == Owner.PREVIEW) {
            val callback = interrupted
            if (!release(Owner.PREVIEW)) {
                callback?.invoke()
                return false
            }
            callback?.invoke()
        }
        if (!send(true)) return false
        owner = request
        interrupted = onInterrupted
        return true
    }

    fun release(request: Owner): Boolean {
        if (owner != request) return true
        if (!send(false)) {
            stopPending = true
            return false
        }
        owner = null
        interrupted = null
        stopPending = false
        return true
    }

    fun retryStop(): Boolean = if (stopPending) owner?.let(::release) ?: true else true

    /** Only after the associated control socket has been closed/discarded. */
    fun reset() {
        owner = null
        interrupted = null
        stopPending = false
    }
}
