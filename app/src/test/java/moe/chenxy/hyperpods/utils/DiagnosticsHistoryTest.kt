package moe.chenxy.hyperpods.utils

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class DiagnosticsHistoryTest {
    private val preferences = mock(SharedPreferences::class.java)
    private val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF).also {
        `when`(preferences.edit()).thenReturn(it)
    }
    private val snapshot = mock(Bundle::class.java).also {
        `when`(it.getString("failure")).thenReturn("setting_write_failed")
        `when`(it.getString("stage")).thenReturn("STATUS")
        `when`(it.getLong("recorded_at")).thenReturn(5000L)
        `when`(it.getLong("last_packet_at")).thenReturn(4000L)
        `when`(it.getInt("received")).thenReturn(8)
        `when`(it.getInt("retries")).thenReturn(2)
        `when`(it.getString("setting_key")).thenReturn(HyperPodsPrefsKey.CONVERSATION_AWARENESS)
    }

    @Test fun persistenceUsesOnlyFixedNonSensitiveFields() {
        DiagnosticsHistory.save(preferences, snapshot)
        verify(editor).clear()
        verify(editor).putString("failure", "setting_write_failed")
        verify(editor).putString("stage", "STATUS")
        verify(editor).putLong("recorded_at", 5000L)
        verify(editor).putLong("last_packet_at", 4000L)
        verify(editor).putInt("received", 8)
        verify(editor).putInt("retries", 2)
        verify(editor).putString("setting_key", HyperPodsPrefsKey.CONVERSATION_AWARENESS)
        verify(editor).apply()
        verifyNoMoreInteractions(editor)
        verify(snapshot, never()).keySet()
        verify(snapshot, never()).getString("device_name")
        verify(snapshot, never()).getString("mac")
        verify(snapshot, never()).getString("serial")
        verify(snapshot, never()).getByteArray("packet")
    }

    @Test fun arbitraryFailureTextCannotBePersisted() {
        `when`(snapshot.getString("failure")).thenReturn("UNRECOGNIZED_FAILURE")
        DiagnosticsHistory.save(preferences, snapshot)
        verify(preferences, never()).edit()
    }

    @Test fun unknownStageAndSettingAreDiscardedAndCountsCannotBeNegative() {
        `when`(snapshot.getString("stage")).thenReturn("UNRECOGNIZED_STAGE")
        `when`(snapshot.getString("setting_key")).thenReturn("UNRECOGNIZED_KEY")
        `when`(snapshot.getInt("received")).thenReturn(-1)
        `when`(snapshot.getInt("retries")).thenReturn(-1)
        DiagnosticsHistory.save(preferences, snapshot)
        verify(editor).putString("stage", "NONE")
        verify(editor).putString("setting_key", "")
        verify(editor).putInt("received", 0)
        verify(editor).putInt("retries", 0)
    }

    @Test fun reopeningTheAppLoadsTheLastFailureWithoutALiveSession() {
        `when`(preferences.getString("failure", "")).thenReturn("status_timeout")
        `when`(preferences.getLong("recorded_at", 0)).thenReturn(5000L)
        mockConstruction(Bundle::class.java).use { construction ->
            assertNotNull(DiagnosticsHistory.read(preferences))
            val report = construction.constructed().single()
            verify(report).putString("failure", "status_timeout")
            verify(report).putLong("recorded_at", 5000L)
        }
        verify(preferences, never()).edit()
    }

    @Test fun noFailureDoesNotProduceAHistoricalReport() {
        `when`(preferences.getString("failure", "")).thenReturn("")
        assertNull(DiagnosticsHistory.read(preferences))
    }

    @Test fun receiverRejectsSpoofedAndAnonymousSenders() {
        val context = mock(Context::class.java)
        val packages = mock(PackageManager::class.java)
        `when`(context.packageManager).thenReturn(packages)
        `when`(packages.getPackageUid(HyperPodsBroadcasts.BLUETOOTH, 0)).thenReturn(1002)
        val intent = mock(Intent::class.java)
        `when`(intent.action).thenReturn(HyperPodsAction.ACTION_PODS_DIAGNOSTICS_RECORD)
        val receiver = spy(DiagnosticsHistory())
        for (uid in listOf(-1, 12345)) {
            doReturn(uid).`when`(receiver).sentFromUid
            receiver.onReceive(context, intent)
        }
        verify(intent, never()).getBundleExtra("diagnostics")
    }

    @Test fun verifiedBluetoothSenderCanRecordAFailureWithTheActivityClosed() {
        val context = mock(Context::class.java)
        val packages = mock(PackageManager::class.java)
        `when`(context.packageManager).thenReturn(packages)
        `when`(packages.getPackageUid(HyperPodsBroadcasts.BLUETOOTH, 0)).thenReturn(1002)
        `when`(context.getSharedPreferences("diagnostics_history", Context.MODE_PRIVATE)).thenReturn(preferences)
        val intent = mock(Intent::class.java)
        `when`(intent.action).thenReturn(HyperPodsAction.ACTION_PODS_DIAGNOSTICS_RECORD)
        `when`(intent.getBundleExtra("diagnostics")).thenReturn(snapshot)
        val receiver = spy(DiagnosticsHistory())
        doReturn(1002).`when`(receiver).sentFromUid
        receiver.onReceive(context, intent)
        verify(editor).putString("failure", "setting_write_failed")
        verify(editor).apply()
    }
}
