package moe.chenxy.hyperpods.utils

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Own only the player paused by this conversation, never a global Play command. */
@SuppressLint("MissingPermission") // Runs in the Bluetooth process, which owns media-control permission.
internal class ConversationMedia(private val context: Context) {
    private var started = false
    private var player: MediaController? = null
    private var callback: MediaController.Callback? = null
    private var pauseConfirmed = false

    val awaitingPause: Boolean
        @Synchronized get() = player != null && !pauseConfirmed

    @Synchronized
    fun start() {
        if (started) return
        started = true
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        if (!audio.isMusicActive || audio.mode != AudioManager.MODE_NORMAL) return
        val target = runCatching {
            val sessions = context.getSystemService(MediaSessionManager::class.java)
            val token = sessions?.mediaKeyEventSession
            sessions?.getActiveSessions(null)?.firstOrNull { it.sessionToken == token }
        }.getOrNull()
        if (target == null) {
            // Players without a readable session can still pause, but cannot be
            // safely resumed automatically. Do not request extra app permissions.
            runCatching { MediaControl.sendPause() }
            return
        }
        if (target.playbackState?.state != PlaybackState.STATE_PLAYING ||
            target.playbackInfo?.playbackType != MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL) return
        val listener = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) = synchronized(this@ConversationMedia) {
                if (callback !== this) return@synchronized
                if (state?.state == PlaybackState.STATE_PAUSED) pauseConfirmed = true
                else if (pauseConfirmed || state?.state != PlaybackState.STATE_PLAYING) clearPlayer()
            }

            override fun onSessionDestroyed() = synchronized(this@ConversationMedia) {
                if (callback === this) clearPlayer()
            }
        }
        player = target
        callback = listener
        try {
            target.registerCallback(listener, Handler(Looper.getMainLooper()))
            target.transportControls.pause()
        } catch (_: Exception) {
            clearPlayer()
            Log.w("HyperPods-Media", "Unable to pause conversation media")
        }
    }

    @Synchronized
    fun finish(resume: Boolean) {
        val target = player
        val restore = runCatching {
            val audio = context.getSystemService(AudioManager::class.java)
            resume && pauseConfirmed && target != null &&
                target.playbackState?.state == PlaybackState.STATE_PAUSED &&
                audio?.mode == AudioManager.MODE_NORMAL && !audio.isMusicActive &&
                context.getSystemService(MediaSessionManager::class.java)?.mediaKeyEventSession == target.sessionToken
        }.getOrDefault(false)
        clearPlayer()
        started = false
        if (restore) runCatching { target?.transportControls?.play() }
            .onFailure { Log.w("HyperPods-Media", "Unable to resume conversation media") }
    }

    private fun clearPlayer() {
        val target = player
        val listener = callback
        player = null
        callback = null
        pauseConfirmed = false
        if (listener != null) runCatching { target?.unregisterCallback(listener) }
    }
}
