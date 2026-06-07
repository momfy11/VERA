package com.vera.android.system

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class VeraNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {}
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
