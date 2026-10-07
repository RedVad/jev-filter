package com.jev.spamgen;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Bundle;
import android.widget.TextView;

/**
 * Test helper: posts spam, a code, a bank warning and a delivery notification, then closes.
 *
 * Набор подобран под проверки Jev Filter:
 *  - 101, 103 - чистая реклама: должны исчезнуть и не вернуться;
 *  - 102, 105 - код подтверждения: локальный стоп-лист, в сеть уходить не должно;
 *  - 104     - доставка с окном времени: должна вернуться из «отложено» со звуком.
 */
public class MainActivity extends Activity {

    private static final String CH = "spamgen";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        // am start -n com.jev.spamgen/.MainActivity --ez cancel true - убрать за собой
        if (getIntent() != null && getIntent().getBooleanExtra("cancel", false)) {
            nm.cancelAll();
            TextView c = new TextView(this);
            c.setText("SpamGen: убрал свои уведомления");
            setContentView(c);
            finish();
            return;
        }

        TextView t = new TextView(this);
        t.setText("SpamGen: отправил 5 уведомлений");
        setContentView(t);

        nm.createNotificationChannel(new NotificationChannel(CH, "spamgen", NotificationManager.IMPORTANCE_DEFAULT));

        post(nm, 101, "spam1", "Wildberries",
                "SALE 50 percent OFF today only! Cashback 20 percent, buy now, huge discounts on everything");
        post(nm, 102, "keep1", "Sberbank",
                "Verification code 1234 for a payment of 15000 RUB. Never share this code with anyone.");
        post(nm, 103, "spam2", "Ozon",
                "Your personal 70 percent discount is waiting! Rate us and get cashback, limited offer!");
        post(nm, 104, "keep2", "Доставка",
                "Курьер приедет сегодня с 18:00 до 20:00. Нужно быть дома, чтобы принять заказ.");
        post(nm, 105, "keep3", "Магазин",
                "Код подтверждения: 4321. Никому не сообщайте этот код.");

        finish();
    }

    private void post(NotificationManager nm, int id, String tag, String title, String text) {
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .build();
        nm.notify(tag, id, n);
    }
}
