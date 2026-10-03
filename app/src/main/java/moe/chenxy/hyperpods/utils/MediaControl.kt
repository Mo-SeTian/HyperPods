/*
 * Copyright (C) 2021-2023 Matthias Urhahn
 *               2023 someone5678
 * SPDX-License-Identifier: GPL-3.0-or-later
 * License-Filename: LICENSE
 */
package moe.chenxy.hyperpods.utils

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@SuppressLint("StaticFieldLeak")
object MediaControl {
    var mContext: Context? = null
    private val audioManager: AudioManager?
        get() = mContext?.getSystemService(AudioManager::class.java)

    val isPlaying: Boolean?
        get() = audioManager?.isMusicActive

    @Synchronized
    fun sendPlay() {
        sendKey(KeyEvent.KEYCODE_MEDIA_PLAY)
    }

    @Synchronized
    fun sendPause() {
        sendKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
    }

    @Synchronized
    fun sendPlayPause() {
        if (isPlaying == true) {
            sendPause()
        } else {
            sendPlay()
        }
    }

    private fun sendKey(keyCode: Int) {
        val eventTime = SystemClock.uptimeMillis()
        audioManager?.dispatchMediaKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        )
        audioManager?.dispatchMediaKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)
        )
    }
}
