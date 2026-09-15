package com.cofreseguro;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class CryptoStore {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "cofre_seguro_aes_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final Context context;

    CryptoStore(Context context) {
        this.context = context.getApplicationContext();
    }

    String encrypt(String clearText) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(clearText.getBytes(StandardCharsets.UTF_8));

        JSONObject packed = new JSONObject();
        packed.put("format", "cofre-seguro-aes-gcm-v1");
        packed.put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP));
        packed.put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP));
        return packed.toString();
    }

    String decrypt(String packedText) throws Exception {
        JSONObject packed = new JSONObject(packedText);
        if (!"cofre-seguro-aes-gcm-v1".equals(packed.optString("format"))) {
            throw new SecurityException("Formato de cofre desconhecido");
        }
        byte[] iv = Base64.decode(packed.getString("iv"), Base64.DEFAULT);
        byte[] encrypted = Base64.decode(packed.getString("data"), Base64.DEFAULT);
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
