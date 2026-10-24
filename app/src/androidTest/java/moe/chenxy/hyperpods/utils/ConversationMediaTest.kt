package moe.chenxy.hyperpods.utils

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** A synthetic silent player on the test device; never operates Bluetooth or another app's media. */
class ConversationMediaTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var track: AudioTrack
    private lateinit var session: MediaSession
    private lateinit var conversation: ConversationMedia
    private val plays = AtomicInteger()
    private val pauses = AtomicInteger()

    private fun state(value: Int) = PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
        .setState(value, 0L, if (value == PlaybackState.STATE_PLAYING) 1f else 0f).build()

    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 5000
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(20)
        assertTrue("Media session did not reach the expected state", condition())
    }

    private fun pauseConfirmed(): Boolean = synchronized(conversation) {
        conversation.javaClass.getDeclaredField("pauseConfirmed").apply { isAccessible = true }.getBoolean(conversation)
    }

    @Before fun setUp() {
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL")
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        track = AudioTrack.Builder().setAudioAttributes(attributes)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(8000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(16000).build()
        assertEquals(8000, track.write(ShortArray(8000), 0, 8000))
        assertEquals(AudioTrack.SUCCESS, track.setLoopPoints(0, 8000, -1))
        session = MediaSession(context, "HyperPods-conversation-test")
        session.setPlaybackToLocal(attributes)
        session.setCallback(object : MediaSession.Callback() {
            override fun onPause() {
                pauses.incrementAndGet()
                track.pause()
                session.setPlaybackState(state(PlaybackState.STATE_PAUSED))
            }

            override fun onPlay() {
                plays.incrementAndGet()
                track.play()
                session.setPlaybackState(state(PlaybackState.STATE_PLAYING))
            }
        }, Handler(Looper.getMainLooper()))
        session.isActive = true
        session.setPlaybackState(state(PlaybackState.STATE_PLAYING))
        track.play()
        conversation = ConversationMedia(context)
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        await { manager.mediaKeyEventSession == session.sessionToken && audio.isMusicActive }
    }

    @After fun tearDown() {
        if (::conversation.isInitialized) conversation.finish(resume = false)
        if (::session.isInitialized) session.release()
        if (::track.isInitialized) track.release()
        instrumentation.uiAutomation.dropShellPermissionIdentity()
    }

    @Test fun pauseAndResumeOnlyTheSameRealMediaSession() {
        conversation.start()
        conversation.start()
        await { pauses.get() == 1 && pauseConfirmed() }
        val audio = context.getSystemService(AudioManager::class.java)
        await { !audio.isMusicActive }
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
        assertEquals(session.sessionToken, context.getSystemService(MediaSessionManager::class.java).mediaKeyEventSession)
        conversation.finish(resume = true)
        await { plays.get() == 1 }
        conversation.finish(resume = true)
        assertEquals(1, plays.get())
    }

    @Test fun manualPlaybackRevokesAutomaticResumeEvenIfTheUserPausesAgain() {
        conversation.start()
        await { pauses.get() == 1 && pauseConfirmed() }
        session.controller.transportControls.play()
        await { plays.get() == 1 && !pauseConfirmed() }
        session.controller.transportControls.pause()
        await { pauses.get() == 2 }
        conversation.finish(resume = true)
        instrumentation.waitForIdleSync()
        assertEquals(1, plays.get())
    }
}
