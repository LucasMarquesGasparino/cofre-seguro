package com.cofreseguro;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

final class VaultStore {
    private static final String FILE_NAME = "vault.enc.json";

    private final File vaultFile;
    private final CryptoStore crypto;
    private final ArrayList<VaultEntry> entries = new ArrayList<>();

    VaultStore(Context context) {
        Context appContext = context.getApplicationContext();
        vaultFile = new File(appContext.getFilesDir(), FILE_NAME);
        crypto = new CryptoStore(appContext);
    }

    void load() throws Exception {
        entries.clear();
        if (!vaultFile.exists()) {
            return;
        }
        String clearText = crypto.decrypt(readFile(vaultFile));
        JSONObject root = new JSONObject(clearText);
        if (root.optInt("version", 1) != 1) {
            throw new SecurityException("Versão de cofre não suportada");
        }
        JSONArray array = root.optJSONArray("entries");
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            entries.add(VaultEntry.fromJson(array.getJSONObject(i)));
        }
    }

    void save() throws Exception {
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("updatedAt", System.currentTimeMillis());
        JSONArray array = new JSONArray();
        for (VaultEntry entry : entries) {
            array.put(entry.toJson());
        }
        root.put("entries", array);
        writeAtomically(crypto.encrypt(root.toString()));
    }

    ArrayList<VaultEntry> getEntries() {
        return entries;
    }

    void clearMemory() {
        entries.clear();
    }

    void exportTo(ContentResolver resolver, Uri destination) throws Exception {
        if (!vaultFile.exists()) {
            save();
        }
        try (InputStream input = new FileInputStream(vaultFile);
             OutputStream output = resolver.openOutputStream(destination, "w")) {
            if (output == null) {
                throw new IllegalStateException("Não foi possível abrir o destino");
            }
            copy(input, output);
        }
    }

    void importFrom(ContentResolver resolver, Uri source) throws Exception {
        String packedText;
        try (InputStream input = resolver.openInputStream(source)) {
            if (input == null) {
                throw new IllegalStateException("Não foi possível abrir o arquivo");
            }
            packedText = new String(readAll(input), StandardCharsets.UTF_8);
        }
        String clearText = crypto.decrypt(packedText);
        JSONObject root = new JSONObject(clearText);
        if (root.optInt("version", 1) != 1 || root.optJSONArray("entries") == null) {
            throw new SecurityException("Arquivo de cofre inválido");
        }
        JSONArray array = root.getJSONArray("entries");
        for (int i = 0; i < array.length(); i++) {
            VaultEntry.fromJson(array.getJSONObject(i));
        }
        writeAtomically(packedText);
        load();
    }

    private void writeAtomically(String content) throws Exception {
        File temporary = new File(vaultFile.getParentFile(), FILE_NAME + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }
        if (!temporary.renameTo(vaultFile)) {
            throw new IllegalStateException("Não foi possível concluir o salvamento");
        }
    }

    private static String readFile(File file) throws Exception {
        try (InputStream input = new FileInputStream(file)) {
            return new String(readAll(input), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readAll(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(input, output);
        return output.toByteArray();
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
    }
}
