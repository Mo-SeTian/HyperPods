package moe.chenxy.hyperpods.utils

/** One physical stream, serialized by L2CAPController's lock. A call can preempt
 * a preview, but a late preview stop must never stop a call-owned stream. */
class HeadTrackingStream(private val send: (start: Boolean) -> Boolean) {
    enum class Owner { CALL, PREVIEW }
    private var owner: Owner? = null
    private var interrupted: (() -> Unit)? = null

    fun acquire(request: Owner, onInterrupted: () -> Unit = {}): Boolean {
        if (owner == request) return true
        if (owner == Owner.CALL) return false
        if (owner == Owner.PREVIEW) {
            val callback = interrupted
            release(Owner.PREVIEW)
            callback?.invoke()
        }
        if (!send(true)) return false
        owner = request
        interrupted = onInterrupted
        return true
    }

    fun release(request: Owner) {
        if (owner != request) return
        owner = null
        interrupted = null
        send(false)
    }
}
