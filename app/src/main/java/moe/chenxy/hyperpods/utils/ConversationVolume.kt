package moe.chenxy.hyperpods.utils

/** Only restore volume that we changed and that the user has not subsequently adjusted. */
class ConversationVolume(
    private val getVolume: () -> Int,
    private val setVolume: (Int) -> Unit,
    private val isPlaying: () -> Boolean,
) {
    private var original: Int? = null
    private var applied: Int? = null
    private var restoring = false

    fun onStatus(status: Int, smoothRestore: Boolean = false) {
        when (status) {
            1, 2, 3 -> {
                if (original != null && (!restoring || getVolume() != applied)) return
                if (!isPlaying()) return
                // A new conversation during a fade retains the pre-conversation volume.
                val volume = original ?: getVolume()
                val target = volume / 5
                if (target == volume) return
                setVolume(target)
                original = volume
                applied = getVolume()
                restoring = false
            }
            6, 7, 8, 9 -> if (smoothRestore) restoring = original != null else reset()
        }
    }

    /** Called at 50 ms intervals. Stop if the user adjusts volume or Android rejects a step. */
    fun restoreStep(): Boolean {
        val target = original ?: return false
        val previous = applied ?: return false
        if (!restoring) return false
        if (getVolume() != previous) {
            original = null
            applied = null
            restoring = false
            return false
        }
        setVolume(minOf(target, previous + maxOf(1, (target + 9) / 10)))
        val actual = getVolume()
        applied = actual
        if (actual <= previous || actual >= target) {
            original = null
            applied = null
            restoring = false
            return false
        }
        return true
    }

    fun reset() {
        val restore = original
        original = null
        if (restore != null && getVolume() == applied) setVolume(restore)
        applied = null
        restoring = false
    }
}
