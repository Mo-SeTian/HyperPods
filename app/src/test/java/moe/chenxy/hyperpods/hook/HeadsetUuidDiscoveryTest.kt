package moe.chenxy.hyperpods.hook

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*

class HeadsetUuidDiscoveryTest {
    private val app = mock(Context::class.java)
    private val first = mock(Context::class.java).apply { `when`(applicationContext).thenReturn(app) }
    private val current = mock(Context::class.java).apply { `when`(applicationContext).thenReturn(app) }
    private val device = mock(BluetoothDevice::class.java)
    private val queried = mutableListOf<Context>()
    private val connected = mutableListOf<Context>()
    private var pods = true
    private var active = true
    private val discovery = HeadsetUuidDiscovery({ pods }, { service, _ -> queried.add(service); active },
        { service, _ -> connected.add(service) })

    private fun receiver(): BroadcastReceiver {
        val capture = ArgumentCaptor.forClass(BroadcastReceiver::class.java)
        verify(app).registerReceiver(capture.capture(), any(IntentFilter::class.java), eq(Context.RECEIVER_EXPORTED))
        verify(first, never()).registerReceiver(any(), any(IntentFilter::class.java), anyInt())
        verify(current, never()).registerReceiver(any(), any(IntentFilter::class.java), anyInt())
        return capture.value
    }

    private fun result(receiver: BroadcastReceiver, resolved: BluetoothDevice = device) {
        val intent = mock(Intent::class.java)
        `when`(intent.action).thenReturn(BluetoothDevice.ACTION_UUID)
        `when`(intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)).thenReturn(resolved)
        receiver.onReceive(app, intent)
    }

    @Test fun replacementServiceReceivesTheResultEvenAfterOldServiceDisconnects() {
        discovery.discover(first, device)
        discovery.discover(current, device)
        discovery.disconnected(first, device)
        val receiver = receiver()
        result(receiver)
        result(receiver) // Duplicate broadcasts must not create a second control connection.
        assertEquals(listOf(current), queried)
        assertEquals(listOf(current), connected)
    }

    @Test fun currentDisconnectAndUnrelatedDevicesCannotCreateConnections() {
        discovery.discover(first, device)
        val receiver = receiver()
        result(receiver, mock(BluetoothDevice::class.java))
        assertTrue(queried.isEmpty())
        discovery.disconnected(first, device)
        result(receiver)
        assertTrue(queried.isEmpty())
    }

    @Test fun nonPodsAndDisconnectedResultsAreIgnored() {
        discovery.discover(first, device)
        val receiver = receiver()
        pods = false
        result(receiver)
        assertTrue(queried.isEmpty())
        discovery.discover(current, device)
        pods = true
        active = false
        result(receiver)
        assertEquals(listOf(current), queried)
        assertTrue(connected.isEmpty())
    }
}
