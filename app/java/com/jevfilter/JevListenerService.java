package com.jevfilter;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/** Слушатель уведомлений: вся логика в Pipeline. */
public class JevListenerService extends NotificationListenerService {

    /** Живой признак: служба подключена к системе прямо сейчас. */
    static volatile boolean CONNECTED = false;

    @Override
    public void onListenerConnected() {
        CONNECTED = true;
        Pipeline.channels(this);
        JeLog.add(this, "слушатель подключён");
        // если процесс убили в момент решения по отложенному уведомлению - возвращаем его
        Pipeline.restorePending(this);
    }

    @Override
    public void onListenerDisconnected() {
        CONNECTED = false;
        JeLog.add(this, "слушатель отключён системой");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        Pipeline.onPosted(this, sbn);
    }
}
