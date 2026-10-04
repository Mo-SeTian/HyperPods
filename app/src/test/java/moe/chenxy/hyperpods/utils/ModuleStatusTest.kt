package moe.chenxy.hyperpods.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import moe.chenxy.hyperpods.BuildConfig
import moe.chenxy.hyperpods.utils.data.HyperPodsAction
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class ModuleStatusTest {
    private val request = "00000000-0000-0000-0000-000000000001"
    private val context = mock(Context::class.java)
    private val packages = mock(PackageManager::class.java).also {
        `when`(context.packageManager).thenReturn(it)
        `when`(it.getPackageUid(HyperPodsBroadcasts.BLUETOOTH, 0)).thenReturn(1002)
    }
    private val receiver = mock(BroadcastReceiver::class.java).also {
        `when`(it.sentFromUid).thenReturn(1002)
        `when`(it.sentFromPackage).thenReturn(HyperPodsBroadcasts.BLUETOOTH)
    }
    private val intent = mock(Intent::class.java).also {
        `when`(it.action).thenReturn(HyperPodsAction.ACTION_MODULE_STATUS)
        `when`(it.getStringExtra("request")).thenReturn(request)
        `when`(it.getStringExtra("host")).thenReturn(HyperPodsBroadcasts.BLUETOOTH)
        `when`(it.getIntExtra("version", 0)).thenReturn(BuildConfig.VERSION_CODE)
        `when`(it.getStringExtra("revision")).thenReturn(BuildConfig.BUILD_REVISION)
        `when`(it.getStringExtra("detail")).thenReturn("native_ok")
    }

    @Test fun verifiedResponseShowsTheActualLoadedBuild() {
        assertTrue(ModuleStatus.read(context, receiver, intent, request)!!.matchesInstalled)
        `when`(intent.getIntExtra("version", 0)).thenReturn(BuildConfig.VERSION_CODE - 1)
        assertFalse(ModuleStatus.read(context, receiver, intent, request)!!.matchesInstalled)
    }

    @Test fun allThreeHostsAreVerifiedWithTheirOwnUidPackageAndStatusVocabulary() {
        for ((host, uid, detail) in listOf(
            Triple(HyperPodsBroadcasts.BLUETOOTH, 1002, "native_ok"),
            Triple(HyperPodsBroadcasts.XIAOMI_BLUETOOTH, 1234, "receiver_ready"),
            Triple(HyperPodsBroadcasts.SYSTEM_UI, 1000, "plugin_observed"))) {
            `when`(packages.getPackageUid(host, 0)).thenReturn(uid)
            `when`(receiver.sentFromUid).thenReturn(uid)
            `when`(receiver.sentFromPackage).thenReturn(host)
            `when`(intent.getStringExtra("host")).thenReturn(host)
            `when`(intent.getStringExtra("detail")).thenReturn(detail)
            assertEquals(host, ModuleStatus.read(context, receiver, intent, request)!!.packageName)
            `when`(intent.getStringExtra("detail")).thenReturn("unknown")
            assertNull(ModuleStatus.read(context, receiver, intent, request))
        }
    }

    @Test fun anonymousOrSpoofedSenderCannotReportAnEnabledModule() {
        `when`(receiver.sentFromUid).thenReturn(-1)
        assertNull(ModuleStatus.read(context, receiver, intent, request))
        `when`(receiver.sentFromUid).thenReturn(12345)
        assertNull(ModuleStatus.read(context, receiver, intent, request))
        `when`(receiver.sentFromUid).thenReturn(1002)
        `when`(receiver.sentFromPackage).thenReturn("other.package")
        assertNull(ModuleStatus.read(context, receiver, intent, request))
    }

    @Test fun staleResponseAndUnknownHostAreIgnored() {
        assertNull(ModuleStatus.read(context, receiver, intent, "00000000-0000-0000-0000-000000000002"))
        `when`(intent.getStringExtra("host")).thenReturn("other.package")
        assertNull(ModuleStatus.read(context, receiver, intent, request))
    }

    @Test fun arbitraryStringsCannotEnterTheDiagnosticReport() {
        `when`(intent.getStringExtra("revision")).thenReturn("PRIVATE_VALUE")
        assertNull(ModuleStatus.read(context, receiver, intent, request))
        `when`(intent.getStringExtra("revision")).thenReturn(BuildConfig.BUILD_REVISION)
        `when`(intent.getStringExtra("detail")).thenReturn("PRIVATE_VALUE")
        assertNull(ModuleStatus.read(context, receiver, intent, request))
    }

    @Test fun requestTokenIsBoundedAndContainsNoDeviceInformation() {
        assertTrue(ModuleStatus.validRequest(request))
        assertFalse(ModuleStatus.validRequest(null))
        assertFalse(ModuleStatus.validRequest("PRIVATE_VALUE"))
    }
}
