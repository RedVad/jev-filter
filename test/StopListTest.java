import java.lang.reflect.Method;

/** Проверка локальных решений без телефона: стоп-лист и маскировка цифр. */
public class StopListTest {

    public static void main(String[] a) throws Exception {
        Class<?> sl = Class.forName("com.jevfilter.StopList");
        Method reason = sl.getDeclaredMethod("reason", String.class, String.class, String.class);
        Method digits = sl.getDeclaredMethod("hasDigits", String.class, String.class);
        reason.setAccessible(true);
        digits.setAccessible(true);

        Class<?> jc = Class.forName("com.jevfilter.JevClient");
        Method mask = jc.getDeclaredMethod("mask", String.class);
        mask.setAccessible(true);

        String[][] cases = {
                // pkg, title, text, ожидание (null = пустить к Джеву)
                {"ru.ozon.app.android", "Ozon", "Скидка 50% только сегодня, успейте купить!", null},
                {"ru.ozon.app.android", "Ozon", "Промокод 12345 на первый заказ", null},
                {"ru.ozon.app.android", "Ozon", "Ваш заказ 48213 готов к выдаче", null},
                {"ru.ozon.app.android", "Ozon", "Код подтверждения: 4321", "код"},
                {"ru.ozon.app.android", "Ozon", "Ваш код 8821 для входа", "код"},
                {"ru.ozon.app.android", "Ozon", "Verification code 1234 for a payment", "код"},
                {"ru.sberbankmobile", "Сбербанк", "Списание 1500 RUB, баланс 12000 RUB", "банк"},
                {"ru.sberbankmobile", "Сбербанк", "Купите подписку со скидкой", "банк"},
                {"ru.ozon.app.android", "Ozon", "Перевод выполнен, получатель Иван", "перевод"},
                // «Подтвердите» - глагол без «ж»: это просьба магазина подтвердить заказ,
                // а не подтверждение операции. Такое к Джеву пускать можно.
                {"ru.ozon.app.android", "Ozon", "\u041f\u043e\u0434\u0442\u0432\u0435\u0440\u0434\u0438\u0442\u0435 \u0430\u0434\u0440\u0435\u0441 \u0434\u043e\u0441\u0442\u0430\u0432\u043a\u0438", null},
                {"ru.ozon.app.android", "Ozon", "\u041f\u043e\u0434\u0442\u0432\u0435\u0440\u0436\u0434\u0435\u043d\u0438\u0435 \u043e\u043f\u0435\u0440\u0430\u0446\u0438\u0438", "подтвержд"},
                {"ru.ozon.app.android", "", "", "текст"},
                {"ru.ozon.app.android", "Ozon", "Новое устройство вошло в аккаунт", "устройство"},
                {"ru.ozon.app.android", "Ozon", "Заканчивается подписка, продлите со скидкой", null},
                {"com.whatsapp", "Мама", "Ты дома?", null},
                {"ru.ozon.app.android", "Ozon", "Начислено 300 баллов за покупки", null},
        };

        int bad = 0;
        for (String[] c : cases) {
            String got = (String) reason.invoke(null, c[0], c[1], c[2]);
            boolean ok = c[3] == null ? got == null : (got != null && got.contains(c[3]));
            if (!ok) bad++;
            System.out.printf("%-8s %-34s -> %s%n", ok ? "ok" : "ПЛОХО", cut(c[1] + " :: " + c[2]), got);
        }

        System.out.println();
        System.out.println("цифры (порог выше):");
        String[] dig = {"Скидка 50%", "Заказ 48213 готов", "Курьер с 18:00 до 20:00", "Нет цифр"};
        for (String s : dig) {
            System.out.printf("   %-28s -> %s%n", s, digits.invoke(null, "", s));
        }

        System.out.println();
        System.out.println("что уходит в модель:");
        String[] msgs = {
                "Заказ 48213 готов к выдаче",
                "Скидка 50% и кэшбэк 3000 RUB",
                "Курьер приедет с 18:00 до 20:00, заказ 2025-10-07",
                "Код 4321",
                "Баланс 1234567 RUB",
        };
        for (String s : msgs) {
            System.out.printf("   %-42s -> %s%n", s, mask.invoke(null, s));
        }
        System.out.println();
        System.out.println(bad == 0 ? "СТОП-ЛИСТ ОК" : "ОШИБОК: " + bad);
    }

    static String cut(String s) {
        return s.length() > 34 ? s.substring(0, 33) + "…" : s;
    }
}
