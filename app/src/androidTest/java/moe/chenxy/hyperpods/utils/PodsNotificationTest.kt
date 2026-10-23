package moe.chenxy.hyperpods.utils

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Builds synthetic notifications only; never posts to a real user's notification center. */
class PodsNotificationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun builder() = Notification.Builder(context, "hyperpods-notification-test")
        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)

    private fun isClearable(notification: Notification) = StatusBarNotification(
        context.packageName, context.packageName, 10003, "BTHeadset-test",
        Process.myUid(), Process.myPid(), 0, notification, Process.myUserHandle(), 0L
    ).isClearable

    private fun assertConnected(notification: Notification) {
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_NO_CLEAR != 0)
        assertFalse(notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
        assertFalse(notification.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertFalse(isClearable(notification))
    }

    @Test fun connectedNotificationKeepsItsContentAndActionsButCannotBeCleared() {
        val content = PendingIntent.getActivity(context, 0,
            Intent(context, moe.chenxy.hyperpods.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val disconnect = PendingIntent.getBroadcast(context, 0,
            Intent("hyperpods.test.disconnect").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
        val notification = builder().setContentTitle("AirPods test").setContentText("Left 80% | Right 75%")
            .setContentIntent(content).setDeleteIntent(disconnect)
            .addAction(Notification.Action.Builder(null, "Disconnect", disconnect).build())
            .setOnlyAlertOnce(true).buildConnectedPodsNotification()

        assertConnected(notification)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals("AirPods test", notification.extras.getCharSequence(Notification.EXTRA_TITLE))
        assertEquals("Left 80% | Right 75%", notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertEquals(content, notification.contentIntent)
        assertEquals(disconnect, notification.deleteIntent)
        assertEquals(disconnect, notification.actions.single().actionIntent)
    }

    @Test fun focusUpdatesKeepTheirPayloadAndClearAllProtection() {
        val focus = """{"param_v2":{"updatable":true,"reopen":"reopen","business":"headset"}}"""
        val notification = builder().addExtras(Bundle().apply {
            putString("miui.focus.param", focus)
        }).buildConnectedPodsNotification()
        val updated = Notification.Builder.recoverBuilder(context, notification)
            .setContentText("Left 79% | Right 74%").buildConnectedPodsNotification()

        assertConnected(notification)
        assertConnected(updated)
        assertEquals(focus, updated.extras.getString("miui.focus.param"))
        assertEquals("Left 79% | Right 74%", updated.extras.getCharSequence(Notification.EXTRA_TEXT))
    }

    @Test fun transientConnectionNotificationRemainsClearable() {
        val notification = builder().setContentText("Connected").build()
        assertEquals(0, notification.flags and (Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT))
        assertTrue(isClearable(notification))
    }
}
