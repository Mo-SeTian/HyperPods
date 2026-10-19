package moe.chenxy.hyperpods.utils

/** Test-only recognition: deliberately has no Context, Telecom or audio APIs.
 * tick/heartbeat and packets are serialized by the Bluetooth connection owner. */
class HeadTrackingPreview(private val stream: HeadTrackingStream, private val emit: (Snapshot) -> Unit) {
    data class Snapshot(val session: String, val sequence: Long, val state: String,
        val packets: Int = 0, val sample: HeadPose.Sample? = null,
        val nods: Int = 0, val shakes: Int = 0, val gesture: String = "")
    private val pose = HeadPose()
    private val detector = HeadGestureDetector()
    private var snapshot = Snapshot("", 0, "idle")
    var active = false
        private set
    private var started = 0L
    private var heartbeat = 0L
    private var lastPacket = 0L
    private var lastEmit = 0L
    private var lastGesture: Long? = null
    private var stoppingReason: String? = null

    private fun publish() {
        snapshot = snapshot.copy(sequence = snapshot.sequence + 1)
        emit(snapshot)
    }

    fun start(session: String, at: Long, connected: Boolean, supported: Boolean, worn: Boolean, inCall: Boolean) {
        if (session.length !in 1..64) return
        if (session == snapshot.session) return // A delayed duplicate must not resurrect a stopped session.
        if (active) stop("stopped")
        snapshot = Snapshot(session, 0, "starting")
        if (stream.stopPending) {
            stoppingReason = "stopped"
            snapshot = snapshot.copy(state = "stop_failed")
            publish()
            return
        }
        val failure = when {
            !connected -> "disconnected"
            !supported -> "unsupported"
            !worn -> "not_worn"
            inCall -> "interrupted"
            else -> null
        }
        if (failure != null) {
            snapshot = snapshot.copy(state = failure)
            publish()
            return
        }
        if (!stream.acquire(HeadTrackingStream.Owner.PREVIEW) { stop("interrupted") }) {
            snapshot = snapshot.copy(state = "unavailable")
            publish()
            return
        }
        active = true
        started = at
        heartbeat = at
        lastPacket = at
        lastEmit = at
        lastGesture = null
        pose.reset()
        detector.reset()
        publish()
    }

    fun command(session: String, command: String, at: Long) {
        if (!active || session != snapshot.session) return
        when (command) {
            "heartbeat" -> heartbeat = at
            "stop" -> stop("stopped")
            "center" -> {
                pose.reset()
                detector.reset()
                lastGesture = null
                snapshot = snapshot.copy(state = "calibrating", sample = null, gesture = "")
                publish()
            }
        }
    }

    fun tick(at: Long) {
        if (stoppingReason != null) {
            if (!stream.stopPending) {
                snapshot = snapshot.copy(state = stoppingReason!!, sample = null)
                stoppingReason = null
                publish()
            }
            return
        }
        if (!active) return
        when {
            at - heartbeat >= 7000 -> stop("expired") // App death cannot leave tracking on.
            at - started >= 120000 -> stop("timeout")
            at - lastPacket >= 5000 -> stop("no_data")
        }
    }

    fun onPacket(packet: ByteArray, at: Long) {
        tick(at)
        if (!active) return
        val sample = pose.process(packet) ?: return
        lastPacket = at
        // Recognition uses every valid sample; UI telemetry is limited to 10 Hz.
        val gesture = if (lastGesture?.let { at - it < 1000 } == true) null else
            detector.process(HeadGestureDetector.Orientation(sample.horizontal, sample.vertical), at)
        if (gesture != null) lastGesture = at
        snapshot = snapshot.copy(state = if (sample.calibrated) "tracking" else "calibrating",
            packets = snapshot.packets + 1, sample = sample,
            nods = snapshot.nods + if (gesture == HeadGestureDetector.Gesture.NOD) 1 else 0,
            shakes = snapshot.shakes + if (gesture == HeadGestureDetector.Gesture.SHAKE) 1 else 0,
            gesture = gesture?.name ?: snapshot.gesture)
        if (gesture != null || at - lastEmit >= 100) {
            lastEmit = at
            publish()
        }
    }

    fun stop(reason: String) {
        if (!active && stoppingReason == null) return
        active = false
        val released = stream.release(HeadTrackingStream.Owner.PREVIEW)
        stoppingReason = reason.takeUnless { released }
        detector.reset()
        pose.reset()
        snapshot = snapshot.copy(state = if (released) reason else "stop_failed", sample = null)
        publish()
    }
}
