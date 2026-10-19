package com.cofreseguro;

import java.util.HashSet;
import java.util.Locale;

/** Estimativa simples de força de senha (0 a 4). Sem dependências do Android. */
final class PasswordStrength {
    private static final String[] LABELS = {"Muito fraca", "Fraca", "Razoável", "Boa", "Forte"};
    private static final String[] COMMON = {
            "123456", "12345678", "123456789", "senha", "senha123", "password", "qwerty",
            "qwerty123", "abc123", "111111", "000000", "123123", "102030", "admin", "brasil",
            "mudar123", "iloveyou", "q1w2e3r4"
    };

    private PasswordStrength() {
    }

    static int score(String password) {
        if (password == null || password.length() == 0) {
            return 0;
        }
        String lower = password.toLowerCase(Locale.ROOT);
        for (String common : COMMON) {
            if (common.equals(lower)) {
                return 0;
            }
        }
        if (allSame(password)) {
            return 0;
        }

        boolean hasLower = false;
        boolean hasUpper = false;
        boolean hasDigit = false;
        boolean hasSymbol = false;
        boolean hasOther = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (c >= 'a' && c <= 'z') {
                hasLower = true;
            } else if (c >= 'A' && c <= 'Z') {
                hasUpper = true;
            } else if (c >= '0' && c <= '9') {
                hasDigit = true;
            } else if (c >= 33 && c <= 126) {
                hasSymbol = true;
            } else if (c > 126) {
                hasOther = true;
            } else {
                hasSymbol = true;
            }
        }
        int pool = (hasLower ? 26 : 0) + (hasUpper ? 26 : 0) + (hasDigit ? 10 : 0)
                + (hasSymbol ? 33 : 0) + (hasOther ? 20 : 0);
        double bits = password.length() * log2(Math.max(pool, 2));

        HashSet<Character> distinct = new HashSet<>();
        for (int i = 0; i < password.length(); i++) {
            distinct.add(password.charAt(i));
        }
        int unique = distinct.size();
        if (unique * 2 < password.length()) {
            bits *= 0.75;
        }
        int score = thresholds(bits);
        if (hasSequence(password)) {
            score = Math.min(score, 1);
        }
        return score;
    }

    static String label(int score) {
        int index = Math.max(0, Math.min(LABELS.length - 1, score));
        return LABELS[index];
    }

    private static int thresholds(double bits) {
        if (bits < 28) {
            return 0;
        }
        if (bits < 36) {
            return 1;
        }
        if (bits < 60) {
            return 2;
        }
        if (bits < 80) {
            return 3;
        }
        return 4;
    }

    private static boolean allSame(String password) {
        for (int i = 1; i < password.length(); i++) {
            if (password.charAt(i) != password.charAt(0)) {
                return false;
            }
        }
        return true;
    }

    /** Sequência crescente ou decrescente de quatro ou mais caracteres. */
    private static boolean hasSequence(String password) {
        for (int i = 0; i + 3 < password.length(); i++) {
            int step = password.charAt(i + 1) - password.charAt(i);
            if (step != 1 && step != -1) {
                continue;
            }
            boolean run = true;
            for (int k = 1; k < 3; k++) {
                if (password.charAt(i + k + 1) - password.charAt(i + k) != step) {
                    run = false;
                    break;
                }
            }
            if (run) {
                return true;
            }
        }
        return false;
    }

    private static double log2(double value) {
        return Math.log(value) / Math.log(2);
    }
}
