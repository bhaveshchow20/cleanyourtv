package com.gathin.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Only here so tvos27 Control Center's Now Playing may read the active media sessions: MediaSessionManager hands
 * them to an app whose notification listener is allowed. It reads no notification, and asks to be unbound as soon
 * as it connects, so it holds nothing open; the permission stays granted. Enable it once with
 * {@code adb shell cmd notification allow_listener com.gathin.tvhome/.NowPlayingListener}.
 */
public class NowPlayingListener extends NotificationListenerService {
    @Override
    public void onListenerConnected() {
        try {
            requestUnbind();
        } catch (RuntimeException ignored) {
        }
    }
}
