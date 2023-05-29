package com.example.mediacontroller;

import android.service.notification.NotificationListenerService;

// This class exists just to conform to Android's permission requirements.
// For some reason, grabbing all media sessions requires this to happen.
// See Special Notification Permissions and MEDIA_CONTENT_CONTROL permission
public class NotificationListener extends NotificationListenerService {
    public NotificationListener() {
    }
}