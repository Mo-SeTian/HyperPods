package moe.chenxy.hyperpods.utils

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** Read-only IPC. Never expose files, arbitrary keys, or writes to another UID. */
class AppPreferencesProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        val allowed = try {
            Binder.getCallingUid() == context.packageManager.getPackageUid(HyperPodsBroadcasts.BLUETOOTH, 0)
        } catch (_: PackageManager.NameNotFoundException) { false }
        if (!allowed) throw SecurityException("Only Bluetooth may read HyperPods policy")
        if (method != AppPreferences.READ) return null
        return Bundle().apply { putString(AppPreferences.EXTRA, AppPreferences.read(context).encode()) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read-only")
}
