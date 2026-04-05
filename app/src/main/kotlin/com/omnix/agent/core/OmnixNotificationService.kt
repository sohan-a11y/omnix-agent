package com.omnix.agent.core

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.omnix.agent.improvements.EventTriggerEngine
import kotlinx.coroutines.*

class OmnixNotificationService : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val title = sbn.notification.extras.getString("android.title") ?: ""
        val text = sbn.notification.extras.getString("android.text") ?: ""

        scope.launch {
            EventTriggerEngine.checkContentTriggers(packageName)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
