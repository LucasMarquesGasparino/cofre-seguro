package com.cofreseguro;

import java.security.SecureRandom;

/** Gerador de senhas sem caracteres ambíguos. Sem dependências do Android. */
final class PasswordGenerator {
    static final int MIN_LENGTH = 8;
    static final int MAX_LENGTH = 64;

    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String DIGITS = "23456789";
    private static final String SYMBOLS = "!@#$%&*-_=+?";

    private PasswordGenerator() {
    }

    static String generate(SecureRandom random, int length, boolean upper, boolean digits,
                           boolean symbols) {
        int size = Math.max(MIN_LENGTH, Math.min(MAX_LENGTH, length));
        String[] sets = new String[1 + (upper ? 1 : 0) + (digits ? 1 : 0) + (symbols ? 1 : 0)];
        StringBuilder pool = new StringBuilder(LOWER);
        int index = 0;
        sets[index++] = LOWER;
        if (upper) {
            sets[index++] = UPPER;
            pool.append(UPPER);
        }
        if (digits) {
            sets[index++] = DIGITS;
            pool.append(DIGITS);
        }
        if (symbols) {
            sets[index++] = SYMBOLS;
            pool.append(SYMBOLS);
        }

        char[] result = new char[size];
        for (int i = 0; i < sets.length; i++) {
            result[i] = pick(random, sets[i]);
        }
        String all = pool.toString();
        for (int i = sets.length; i < size; i++) {
            result[i] = pick(random, all);
        }
        // Fisher-Yates
        for (int i = size - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char tmp = result[i];
            result[i] = result[j];
            result[j] = tmp;
        }
        return new String(result);
    }

    private static char pick(SecureRandom random, String source) {
        return source.charAt(random.nextInt(source.length()));
    }
}
