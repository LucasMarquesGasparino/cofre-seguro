package com.cofreseguro;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Backup portátil protegido por senha: PBKDF2-HMAC-SHA256 -> AES-256-GCM.
 * Sem dependência de android.* nem org.json (testável na JVM pura).
 */
final class BackupCrypto {
    static final String FORMAT = "bolsa-hermione-backup-v1";
    static final String KDF = "PBKDF2WithHmacSHA256";
    static final int DEFAULT_ITERATIONS = 600000;
    static final int MIN_ITERATIONS = 100000;
    static final int MAX_ITERATIONS = 5000000;
    static final int SALT_BYTES = 16;
    static final int IV_BYTES = 12;

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;
    private static final int KEY_BITS = 256;

    private BackupCrypto() {
    }

    static final class Sealed {
        final int iterations;
        final String salt;
        final String iv;
        final String data;

        Sealed(int iterations, String salt, String iv, String data) {
            this.iterations = iterations;
            this.salt = salt;
            this.iv = iv;
            this.data = data;
        }
    }

    static final class WrongPasswordException extends Exception {
        WrongPasswordException() {
            super("Senha incorreta ou arquivo alterado");
        }
    }

    static final class BackupFormatException extends Exception {
        BackupFormatException(String message) {
            super(message);
        }
    }

    static Sealed seal(char[] password, byte[] plaintext, int iterations)
            throws GeneralSecurityException, BackupFormatException {
        checkIterations(iterations);
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        String saltText = encode(salt);
        byte[] key = deriveKey(password, salt, iterations);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(iterations, saltText));
            byte[] encrypted = cipher.doFinal(plaintext);
            return new Sealed(iterations, saltText, encode(iv), encode(encrypted));
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    static byte[] open(char[] password, int iterations, String salt, String iv, String data)
            throws WrongPasswordException, BackupFormatException, GeneralSecurityException {
        checkIterations(iterations);
        byte[] saltBytes = decode(salt);
        if (saltBytes.length < SALT_BYTES) {
            throw new BackupFormatException("Sal inválido");
        }
        byte[] ivBytes = decode(iv);
        if (ivBytes.length != IV_BYTES) {
            throw new BackupFormatException("IV inválido");
        }
        byte[] encrypted = decode(data);
        byte[] key = deriveKey(password, saltBytes, iterations);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, ivBytes));
            cipher.updateAAD(aad(iterations, salt));
            return cipher.doFinal(encrypted);
        } catch (AEADBadTagException error) {
            throw new WrongPasswordException();
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    static byte[] deriveKey(char[] password, byte[] salt, int iterations)
            throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(KDF).generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    static byte[] aad(int iterations, String saltText) {
        return (FORMAT + "\n" + KDF + "\n" + iterations + "\n" + saltText)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void checkIterations(int iterations) throws BackupFormatException {
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw new BackupFormatException("Parâmetros de criptografia fora do limite");
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] decode(String text) throws BackupFormatException {
        if (text == null) {
            throw new BackupFormatException("Campo ausente");
        }
        try {
            return Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException error) {
            throw new BackupFormatException("Dados em formato inválido");
        }
    }
}
