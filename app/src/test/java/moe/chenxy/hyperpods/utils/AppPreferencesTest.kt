package moe.chenxy.hyperpods.utils

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.util.AtomicFile
import moe.chenxy.hyperpods.utils.data.HyperPodsPrefsKey as Key
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.*
import java.io.FileInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AppPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val snapshot = AppPreferences.Snapshot(false, true, false, LowBatterySettings(false, 35, true, 15))

    @Test fun allPolicyChoicesRoundTripWithoutResettingOtherSwitches() {
        assertEquals(snapshot, AppPreferences.decode(snapshot.encode()))
        assertEquals(8, JSONObject(snapshot.encode()).length())
        assertEquals(snapshot.copy(headGestures = true), AppPreferences.decode(snapshot.copy(headGestures = true).encode()))
    }

    @Test fun existingPrivateFileMigratesWithGesturesOffAndOtherPoliciesUnchanged() {
        val previous = JSONObject(snapshot.encode()).apply { remove(Key.HEAD_GESTURES) }.toString()
        assertEquals(snapshot, AppPreferences.decode(previous))
        assertNull(AppPreferences.decode(JSONObject(snapshot.encode()).put(Key.HEAD_GESTURES, "true").toString()))
    }

    @Test fun partialMalformedOrOutOfRangeSnapshotsAreRejected() {
        assertNull(AppPreferences.decode(null))
        assertNull(AppPreferences.decode("{}"))
        assertNull(AppPreferences.decode("invalid"))
        for (value in listOf(9, 51, 22)) {
            assertNull(AppPreferences.decode(JSONObject(snapshot.encode()).put(Key.LOW_BATTERY_CASE_THRESHOLD, value).toString()))
        }
        assertNull(AppPreferences.decode(JSONObject(snapshot.encode()).put(Key.EAR_DETECTION, "false").toString()))
    }

    @Test fun migrationPreservesEveryOldPolicyButNeverCopiesUnrelatedFields() {
        val legacy = mock(SharedPreferences::class.java)
        `when`(legacy.all).thenReturn(mapOf(Key.EAR_DETECTION to false, Key.EAR_DETECTION_SWITCH_SPEAKER to true,
            Key.CONVERSATION_PHONE_VOLUME to false, Key.LOW_BATTERY_EARS to false, Key.LOW_BATTERY_EARS_THRESHOLD to 35,
            Key.LOW_BATTERY_CASE to true, Key.LOW_BATTERY_CASE_THRESHOLD to 15, "unrelated" to "TEST_ONLY"))
        assertEquals(snapshot, AppPreferences.fromLegacy(legacy))
        assertFalse(AppPreferences.fromLegacy(legacy).encode().contains("unrelated"))
    }

    @Test fun badLegacyValuesUseDefaultsIndividuallyNotForTheEntirePolicy() {
        val legacy = mock(SharedPreferences::class.java)
        `when`(legacy.all).thenReturn(mapOf(Key.EAR_DETECTION to false, Key.CONVERSATION_PHONE_VOLUME to "false",
            Key.LOW_BATTERY_EARS_THRESHOLD to 99, Key.LOW_BATTERY_CASE_THRESHOLD to 35))
        assertEquals(AppPreferences.Snapshot(false, true, true, LowBatterySettings(true, 20, true, 35)),
            AppPreferences.fromLegacy(legacy))
    }

    @Test fun privatizationRetainsTheLegacyValuesAndReportsWriteFailure() {
        val legacy = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java, RETURNS_SELF)
        `when`(legacy.all).thenReturn(mapOf(Key.EAR_DETECTION to false))
        `when`(legacy.edit()).thenReturn(editor)
        `when`(editor.commit()).thenReturn(false, true)
        assertFalse(AppPreferences.secureLegacy(legacy))
        assertTrue(AppPreferences.secureLegacy(legacy))
        verify(editor, never()).clear()
        verify(editor, times(2)).commit()
    }

    @Test fun freshInstallDoesNotCreateALegacyXml() {
        val legacy = mock(SharedPreferences::class.java)
        `when`(legacy.all).thenReturn(emptyMap())
        assertTrue(AppPreferences.secureLegacy(legacy))
        verify(legacy, never()).edit()
    }

    @Test fun diskWriteFailureRollsBackAndInvalidPolicyNeverOpensTheFile() {
        val store = mock(AtomicFile::class.java)
        val output = mock(FileOutputStream::class.java)
        `when`(store.startWrite()).thenReturn(output)
        doThrow(IOException()).`when`(output).write(any(ByteArray::class.java))
        assertFalse(AppPreferences.write(store, snapshot))
        verify(store).failWrite(output)
        verify(store, never()).finishWrite(output)
        clearInvocations(store)
        assertFalse(AppPreferences.write(store, snapshot.copy(lowBattery = LowBatterySettings(earsThreshold = 99))))
        verifyNoInteractions(store)
    }

    @Test fun newFileTakesPrecedenceOverOldXml() {
        val context = mock(Context::class.java)
        val legacy = mock(SharedPreferences::class.java)
        `when`(context.filesDir).thenReturn(File("/TEST_ONLY"))
        `when`(context.getSharedPreferences(anyString(), eq(Context.MODE_PRIVATE))).thenReturn(legacy)
        `when`(legacy.all).thenReturn(emptyMap())
        val input = temporary.newFile().apply { writeText(snapshot.encode()) }
        mockConstruction(AtomicFile::class.java) { store, _ ->
            `when`(store.openRead()).thenReturn(FileInputStream(input))
        }.use { stores ->
            assertEquals(snapshot, AppPreferences.read(context))
            verify(stores.constructed().single(), never()).startWrite()
        }
    }

    @Test fun failedInitialCopyDoesNotRetireTheOnlyExistingSettings() {
        val context = mock(Context::class.java)
        val legacy = mock(SharedPreferences::class.java)
        `when`(context.filesDir).thenReturn(File("/TEST_ONLY"))
        `when`(context.getSharedPreferences(anyString(), eq(Context.MODE_PRIVATE))).thenReturn(legacy)
        `when`(legacy.all).thenReturn(mapOf(Key.EAR_DETECTION to false))
        mockConstruction(AtomicFile::class.java) { store, _ ->
            `when`(store.openRead()).thenThrow(FileNotFoundException())
            `when`(store.startWrite()).thenThrow(IOException())
        }.use {
            assertFalse(AppPreferences.read(context).earDetection)
            verify(legacy, never()).edit()
        }
    }

    @Test fun providerFailureOrBadReplyDoesNotReturnADefaultPolicy() {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        val reply = mock(Bundle::class.java)
        `when`(context.contentResolver).thenReturn(resolver)
        `when`(resolver.call(AppPreferences.AUTHORITY, AppPreferences.READ, null, null)).thenReturn(reply)
        `when`(reply.getString(AppPreferences.EXTRA)).thenReturn("{}")
        assertNull(AppPreferences.readRemote(context))
        `when`(reply.getString(AppPreferences.EXTRA)).thenReturn(snapshot.encode())
        assertEquals(snapshot, AppPreferences.readRemote(context))
        `when`(resolver.call(AppPreferences.AUTHORITY, AppPreferences.READ, null, null)).thenThrow(SecurityException())
        assertNull(AppPreferences.readRemote(context))
    }

    @Test fun blockedRemoteReadKeepsItsOriginalCallbackAndReadsFreshPolicyForTheLatestQueuedRequest() {
        val context = mock(Context::class.java)
        val resolver = mock(ContentResolver::class.java)
        val oldReply = mock(Bundle::class.java)
        val newReply = mock(Bundle::class.java)
        val updated = snapshot.copy(earDetection = !snapshot.earDetection)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        var reads = 0
        var originalResult: AppPreferences.Snapshot? = null
        var supersededCalled = false
        var result: AppPreferences.Snapshot? = null
        `when`(context.contentResolver).thenReturn(resolver)
        `when`(oldReply.getString(AppPreferences.EXTRA)).thenReturn(snapshot.encode())
        `when`(newReply.getString(AppPreferences.EXTRA)).thenReturn(updated.encode())
        `when`(resolver.call(AppPreferences.AUTHORITY, AppPreferences.READ, null, null)).thenAnswer {
            if (++reads == 1) {
                entered.countDown()
                check(release.await(3, TimeUnit.SECONDS))
                oldReply
            } else newReply
        }
        try {
            AppPreferences.requestRemote(context) { originalResult = it }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            AppPreferences.requestRemote(context) { supersededCalled = true }
            AppPreferences.requestRemote(context) { result = it; completed.countDown() }
            verify(resolver, times(1)).call(AppPreferences.AUTHORITY, AppPreferences.READ, null, null)
            release.countDown()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(snapshot, originalResult)
            assertFalse(supersededCalled)
            assertEquals(updated, result)
            verify(resolver, times(2)).call(AppPreferences.AUTHORITY, AppPreferences.READ, null, null)
        } finally { release.countDown() }
    }

    @Test fun providerRejectsOtherUidsBeforeReadingAnySettings() {
        val context = mock(Context::class.java)
        val packages = mock(PackageManager::class.java)
        `when`(context.packageManager).thenReturn(packages)
        `when`(packages.getPackageUid(HyperPodsBroadcasts.BLUETOOTH, 0)).thenReturn(1002)
        val provider = spy(AppPreferencesProvider())
        doReturn(context).`when`(provider).context
        mockStatic(Binder::class.java).use { binder ->
            for (uid in listOf(0, 1000, 2000, 12345)) {
                binder.`when`<Int> { Binder.getCallingUid() }.thenReturn(uid)
                assertThrows(SecurityException::class.java) { provider.call(AppPreferences.READ, null, null) }
            }
            binder.`when`<Int> { Binder.getCallingUid() }.thenReturn(1002)
            assertNull(provider.call("write", null, null))
        }
        verify(context, never()).filesDir
        verify(context, never()).getSharedPreferences(anyString(), anyInt())
    }

    @Test fun verifiedBluetoothUidCanReadTheFixedPolicySnapshot() {
        val context = mock(Context::class.java)
        val packages = mock(PackageManager::class.java)
        val legacy = mock(SharedPreferences::class.java)
        `when`(context.filesDir).thenReturn(File("/TEST_ONLY_PROVIDER"))
        `when`(context.getSharedPreferences(anyString(), eq(Context.MODE_PRIVATE))).thenReturn(legacy)
        `when`(legacy.all).thenReturn(emptyMap())
        `when`(context.packageManager).thenReturn(packages)
        `when`(packages.getPackageUid(HyperPodsBroadcasts.BLUETOOTH, 0)).thenReturn(1002)
        val provider = spy(AppPreferencesProvider())
        doReturn(context).`when`(provider).context
        val input = temporary.newFile().apply { writeText(snapshot.encode()) }
        mockConstruction(AtomicFile::class.java) { store, _ ->
            `when`(store.openRead()).thenReturn(FileInputStream(input))
        }.use {
            mockConstruction(Bundle::class.java).use { replies ->
                mockStatic(Binder::class.java).use { binder ->
                    binder.`when`<Int> { Binder.getCallingUid() }.thenReturn(1002)
                    val reply = provider.call(AppPreferences.READ, null, null)
                    assertSame(replies.constructed().single(), reply)
                    verify(requireNotNull(reply)).putString(AppPreferences.EXTRA, snapshot.encode())
                }
            }
        }
    }
}
