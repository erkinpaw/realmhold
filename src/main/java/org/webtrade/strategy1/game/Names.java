package org.webtrade.strategy1.game;

import net.minecraft.util.RandomSource;

/** Names for the provinces, made of old-sounding syllables. */
final class Names {

    private static final String[] START = {"Вел", "Бор", "Зар", "Кам", "Лод", "Мир", "Нов", "Ост", "Рад", "Свят", "Тур", "Ярь",
            "Гор", "Дуб", "Жел", "Крас", "Лес", "Морь", "Пол", "Сер", "Тих", "Хол", "Чер", "Яс", "Бел", "Вол", "Сокол", "Стар"};
    private static final String[] MID = {"", "", "о", "е", "ан", "ин", "ом", "ов"};
    private static final String[] END = {"град", "бор", "поль", "ск", "ца", "мир", "дол", "гора", "ье", "ов", "енск", "ки", "яр", "вежа", "стан"};
    private static final String[] SEA = {"Синее море", "Тихая гавань", "Северный залив", "Серые воды", "Лазурная бухта",
            "Штормовой пролив", "Глубокие воды", "Песчаный залив"};

    private Names() {
    }

    static String province(RandomSource r) {
        return START[r.nextInt(START.length)] + MID[r.nextInt(MID.length)] + END[r.nextInt(END.length)];
    }

    static String sea(RandomSource r) {
        return SEA[r.nextInt(SEA.length)];
    }
}
