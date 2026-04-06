package com.omnix.agent.core

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.omnix.agent.improvements.EventTriggerEngine
import kotlinx.coroutines.*

class OmnixNotificationService : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        scope.launch {
            // Route to EventTriggerEngine trigger 4: NotificationReceived
            EventTriggerEngine.onNotificationReceived(packageName, title, text)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {}

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
