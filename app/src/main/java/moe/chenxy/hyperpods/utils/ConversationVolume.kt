package moe.chenxy.hyperpods.utils

/** Only restore volume that we changed and that the user has not subsequently adjusted. */
class ConversationVolume(
    private val getVolume: () -> Int,
    private val setVolume: (Int) -> Unit,
    private val isPlaying: () -> Boolean,
) {
    private var original: Int? = null
    private var applied: Int? = null

    fun onStatus(status: Int) {
        when (status) {
            1, 2, 3 -> {
                if (original != null || !isPlaying()) return
                val volume = getVolume()
                val target = volume / 5
                if (target == volume) return
                setVolume(target)
                original = volume
                applied = getVolume()
            }
            6, 7, 8, 9 -> reset()
        }
    }

    fun reset() {
        val restore = original
        original = null
        if (restore != null && getVolume() == applied) setVolume(restore)
        applied = null
    }
}
