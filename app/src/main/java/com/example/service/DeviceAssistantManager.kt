package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.ContactsContract
import androidx.core.app.NotificationCompat
import com.example.data.model.ContactItem
import com.example.data.model.InstalledAppItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DeviceAssistantManager(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "jarvis_updates_channel"
        const val CHANNEL_NAME = "Jarvis Updates & Reminders"
    }

    private val _isOnlineFlow = MutableStateFlow(checkIsOnline())
    val isOnlineFlow: StateFlow<Boolean> = _isOnlineFlow.asStateFlow()

    init {
        createNotificationChannel()
        registerNetworkCallback()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Channel for Jarvis updates and alerts"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _isOnlineFlow.value = true
                }
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    _isOnlineFlow.value = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
                override fun onLost(network: Network) {
                    _isOnlineFlow.value = checkIsOnline()
                }
            })
        } catch (_: Exception) {}
    }

    fun isOnline(): Boolean {
        val current = checkIsOnline()
        _isOnlineFlow.value = current
        return current
    }

    private fun checkIsOnline(): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun getBatteryLevel(): Int {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }

    fun getInstalledApps(): List<InstalledAppItem> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        val apps = mutableListOf<InstalledAppItem>()
        for (info in resolveInfos) {
            val appName = info.loadLabel(pm).toString()
            val packageName = info.activityInfo.packageName
            apps.add(InstalledAppItem(name = appName, packageName = packageName))
        }
        return apps.distinctBy { it.packageName }.sortedBy { it.name.lowercase() }
    }

    fun launchAppByPackage(packageName: String): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun launchAppByName(query: String): String {
        val apps = getInstalledApps()
        val normalized = query.trim().lowercase()
        val match = apps.firstOrNull { it.name.lowercase().contains(normalized) }
            ?: apps.firstOrNull { it.packageName.lowercase().contains(normalized) }

        return if (match != null) {
            val success = launchAppByPackage(match.packageName)
            if (success) {
                "Opening ${match.name}..."
            } else {
                "Failed to launch ${match.name}."
            }
        } else {
            "App '$query' not found on this device."
        }
    }

    fun getContacts(): List<ContactItem> {
        val list = mutableListOf<ContactItem>()
        val hasPermission = context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return emptyList()

        val cursor: Cursor? = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.PHOTO_URI
            ),
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )

        cursor?.use {
            val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val photoIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)

            while (it.moveToNext()) {
                val id = if (idIdx != -1) it.getString(idIdx) ?: "" else ""
                val name = if (nameIdx != -1) it.getString(nameIdx) ?: "Unknown" else "Unknown"
                val number = if (numberIdx != -1) it.getString(numberIdx) ?: "" else ""
                val photoUri = if (photoIdx != -1) it.getString(photoIdx) else null
                list.add(ContactItem(id = id, name = name, phoneNumber = number, photoUri = photoUri))
            }
        }
        return list.distinctBy { it.phoneNumber }
    }

    fun dialPhoneNumber(phoneNumber: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:${phoneNumber.trim()}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun sendNotification(title: String, message: String, notificationId: Int = 1001) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        try {
            manager.notify(notificationId, builder.build())
        } catch (_: SecurityException) {
            // Notification permission might not be granted
        }
    }
}
