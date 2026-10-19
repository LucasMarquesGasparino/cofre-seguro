package com.cofreseguro;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class VaultStore {
    private static final String FILE_NAME = "vault.enc.json";
    private static final String BACKUP_NAME = "vault.enc.json.bak";
    private static final String BEFORE_IMPORT_NAME = "vault.enc.json.antes-importacao";

    static final class VaultUnreadableException extends Exception {
        VaultUnreadableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static final class ImportResult {
        int added;
        int updated;
        int skipped;
    }

    private final File directory;
    private final File vaultFile;
    private final File backupFile;
    private final CryptoStore crypto;
    private final ArrayList<VaultEntry> entries = new ArrayList<>();
    private boolean loaded;
    private boolean recoveredFromBackup;

    VaultStore(Context context) {
        Context appContext = context.getApplicationContext();
        directory = appContext.getFilesDir();
        vaultFile = new File(directory, FILE_NAME);
        backupFile = new File(directory, BACKUP_NAME);
        crypto = new CryptoStore(appContext);
    }

    /**
     * Abre a bolsa. Se o arquivo principal estiver ilegível e existir a cópia anterior
     * (.bak), a cópia é usada, o principal danificado é guardado à parte e o principal
     * é regravado. Se nada for legível, lança VaultUnreadableException.
     */
    void load() throws VaultUnreadableException {
        entries.clear();
        loaded = false;
        recoveredFromBackup = false;
        if (!vaultFile.exists()) {
            if (!backupFile.exists()) {
                loaded = true;
                return;
            }
            try {
                entries.addAll(readVault(backupFile));
            } catch (Exception error) {
                entries.clear();
                throw unreadable(error);
            }
            loaded = true;
            return;
        }
        try {
            entries.addAll(readVault(vaultFile));
            loaded = true;
            return;
        } catch (Exception primaryError) {
            entries.clear();
            if (!backupFile.exists()) {
                throw unreadable(primaryError);
            }
            List<VaultEntry> fromBackup;
            try {
                fromBackup = readVault(backupFile);
            } catch (Exception backupError) {
                throw unreadable(primaryError);
            }
            File damaged = new File(directory, FILE_NAME + ".danificado-" + System.currentTimeMillis());
            if (!vaultFile.renameTo(damaged)) {
                throw unreadable(primaryError);
            }
            entries.addAll(fromBackup);
            recoveredFromBackup = true;
            loaded = true;
            try {
                save();
            } catch (Exception saveError) {
                // A cópia anterior continua no disco; a próxima gravação tenta de novo.
            }
        }
    }

    private static VaultUnreadableException unreadable(Throwable cause) {
        return new VaultUnreadableException("O arquivo da bolsa está ilegível.", cause);
    }

    /** Retorna true uma única vez se a bolsa foi recuperada da cópia anterior. */
    boolean consumeRecoveredFlag() {
        boolean value = recoveredFromBackup;
        recoveredFromBackup = false;
        return value;
    }

    boolean isLoaded() {
        return loaded;
    }

    private List<VaultEntry> readVault(File file) throws Exception {
        String clearText = crypto.decrypt(readFile(file));
        JSONObject root = new JSONObject(clearText);
        if (root.optInt("version", 1) != 1) {
            throw new SecurityException("Versão de cofre não suportada");
        }
        ArrayList<VaultEntry> result = new ArrayList<>();
        JSONArray array = root.optJSONArray("entries");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                result.add(VaultEntry.fromJson(array.getJSONObject(i)));
            }
        }
        return result;
    }

    void save() throws Exception {
        requireLoaded();
        writeAtomically(crypto.encrypt(buildRoot(entries).toString()));
    }

    private static JSONObject buildRoot(List<VaultEntry> list) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("updatedAt", System.currentTimeMillis());
        JSONArray array = new JSONArray();
        for (VaultEntry entry : list) {
            array.put(entry.toJson());
        }
        root.put("entries", array);
        return root;
    }

    /** Somente leitura: a UI não altera a lista diretamente. */
    List<VaultEntry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    void clearMemory() {
        entries.clear();
        loaded = false;
    }

    private void requireLoaded() {
        if (!loaded) {
            throw new IllegalStateException("A bolsa não está aberta");
        }
    }

    // ---- Mutações com rollback em memória se a gravação falhar ----

    void add(VaultEntry entry) throws Exception {
        requireLoaded();
        entries.add(entry);
        try {
            save();
        } catch (Exception error) {
            entries.remove(entry);
            throw error;
        }
    }

    void update(VaultEntry target, VaultEntry values) throws Exception {
        requireLoaded();
        VaultEntry before = target.copy();
        target.copyFrom(values);
        try {
            save();
        } catch (Exception error) {
            target.copyFrom(before);
            throw error;
        }
    }

    void remove(VaultEntry entry) throws Exception {
        requireLoaded();
        int index = entries.indexOf(entry);
        if (index < 0) {
            return;
        }
        entries.remove(index);
        try {
            save();
        } catch (Exception error) {
            entries.add(index, entry);
            throw error;
        }
    }

    void setFavorite(VaultEntry entry, boolean favorite) throws Exception {
        requireLoaded();
        boolean before = entry.favorite;
        entry.favorite = favorite;
        try {
            save();
        } catch (Exception error) {
            entry.favorite = before;
            throw error;
        }
    }

    void recordAccess(VaultEntry entry) throws Exception {
        requireLoaded();
        entry.accessCount++;
        try {
            save();
        } catch (Exception error) {
            entry.accessCount--;
            throw error;
        }
    }

    /**
     * Mescla registros de fora da bolsa. Mesmo id: atualiza se o recebido for mais novo.
     * Sem id igual, mas mesmo site+usuário e mesma senha: é duplicado e é ignorado.
     */
    ImportResult merge(List<VaultEntry> incoming) throws Exception {
        requireLoaded();
        ImportResult result = new ImportResult();
        List<VaultEntry> originals = new ArrayList<>(entries);
        List<VaultEntry> snapshots = copyAll(originals);
        safetyCopyBeforeImport();
        try {
            for (VaultEntry item : incoming) {
                VaultEntry existing = findById(item.id);
                if (existing != null) {
                    if (item.updatedAt > existing.updatedAt) {
                        int accesses = Math.max(existing.accessCount, item.accessCount);
                        existing.copyFrom(item);
                        existing.accessCount = accesses;
                        result.updated++;
                    } else {
                        result.skipped++;
                    }
                } else if (findDuplicate(item) != null) {
                    result.skipped++;
                } else {
                    entries.add(item);
                    result.added++;
                }
            }
            save();
        } catch (Exception error) {
            restore(originals, snapshots);
            throw error;
        }
        return result;
    }

    /** Substitui toda a bolsa pelos registros recebidos, com cópia anterior e rollback. */
    void replaceAll(List<VaultEntry> incoming) throws Exception {
        requireLoaded();
        List<VaultEntry> originals = new ArrayList<>(entries);
        safetyCopyBeforeImport();
        entries.clear();
        entries.addAll(incoming);
        try {
            save();
        } catch (Exception error) {
            entries.clear();
            entries.addAll(originals);
            throw error;
        }
    }

    /** Guarda a bolsa atual em vault.enc.json.antes-importacao antes de uma importação. */
    void safetyCopyBeforeImport() throws Exception {
        if (!vaultFile.exists()) {
            return;
        }
        writeSynced(new File(directory, BEFORE_IMPORT_NAME), readBytes(vaultFile));
    }

    /**
     * Esvazia a bolsa para começar de novo após um arquivo ilegível. Os arquivos
     * principal e .bak são renomeados (nunca apagados).
     */
    void startFresh() throws Exception {
        String stamp = String.valueOf(System.currentTimeMillis());
        if (vaultFile.exists() && !vaultFile.renameTo(new File(directory, FILE_NAME + ".ilegivel-" + stamp))) {
            throw new IllegalStateException("Não foi possível guardar o arquivo ilegível");
        }
        if (backupFile.exists() && !backupFile.renameTo(new File(directory, BACKUP_NAME + ".ilegivel-" + stamp))) {
            throw new IllegalStateException("Não foi possível guardar a cópia anterior");
        }
        entries.clear();
        recoveredFromBackup = false;
        loaded = true;
    }

    private VaultEntry findById(String id) {
        for (VaultEntry entry : entries) {
            if (entry.id.equals(id)) {
                return entry;
            }
        }
        return null;
    }

    private VaultEntry findDuplicate(VaultEntry item) {
        String site = item.site.trim();
        String user = item.username.trim();
        for (VaultEntry entry : entries) {
            if (entry.site.trim().equalsIgnoreCase(site)
                    && entry.username.trim().equalsIgnoreCase(user)
                    && entry.password.equals(item.password)) {
                return entry;
            }
        }
        return null;
    }

    private static List<VaultEntry> copyAll(List<VaultEntry> source) {
        ArrayList<VaultEntry> copies = new ArrayList<>();
        for (VaultEntry entry : source) {
            copies.add(entry.copy());
        }
        return copies;
    }

    private void restore(List<VaultEntry> originals, List<VaultEntry> snapshots) {
        for (int i = 0; i < originals.size(); i++) {
            originals.get(i).copyFrom(snapshots.get(i));
        }
        entries.clear();
        entries.addAll(originals);
    }

    // ---- Backup portátil protegido por senha (formato bolsa-hermione-backup-v1) ----

    static final class ParsedBackup {
        final List<VaultEntry> entries;
        final long exportedAt;

        ParsedBackup(List<VaultEntry> entries, long exportedAt) {
            this.entries = entries;
            this.exportedAt = exportedAt;
        }
    }

    private static final String LEGACY_MESSAGE = "Este arquivo é um backup da versão antiga, preso à chave "
            + "da instalação em que foi criado. Depois de reinstalar o app ele não pode mais ser aberto.";

    /** Texto claro do backup. Chamar na thread da UI, com a bolsa carregada. */
    String buildBackupPlaintext() throws JSONException {
        requireLoaded();
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("exportedAt", System.currentTimeMillis());
        root.put("app", "Bolsa da Hermione");
        JSONArray array = new JSONArray();
        for (VaultEntry entry : entries) {
            array.put(entry.toJson());
        }
        root.put("entries", array);
        return root.toString();
    }

    /** Cifra o texto com a senha do backup. Pode demorar: chamar fora da UI. */
    static String sealBackup(String plaintext, char[] password) throws Exception {
        BackupCrypto.Sealed sealed = BackupCrypto.seal(password,
                plaintext.getBytes(StandardCharsets.UTF_8), BackupCrypto.DEFAULT_ITERATIONS);
        JSONObject packed = new JSONObject();
        packed.put("format", BackupCrypto.FORMAT);
        packed.put("kdf", BackupCrypto.KDF);
        packed.put("iterations", sealed.iterations);
        packed.put("salt", sealed.salt);
        packed.put("iv", sealed.iv);
        packed.put("data", sealed.data);
        packed.put("note", "Backup da Bolsa da Hermione. Abra no app em Backup › Restaurar e informe a senha do backup.");
        return packed.toString();
    }

    /** "backup-v1", "legacy" (formato local antigo) ou "unknown". */
    static String detectFormat(String text) {
        try {
            String format = new JSONObject(text).optString("format", "");
            if (BackupCrypto.FORMAT.equals(format)) {
                return "backup-v1";
            }
            if ("cofre-seguro-aes-gcm-v1".equals(format)) {
                return "legacy";
            }
        } catch (JSONException error) {
            return "unknown";
        }
        return "unknown";
    }

    /** Abre um backup portátil. Pode demorar (PBKDF2): chamar fora da UI. */
    static ParsedBackup openBackup(String text, char[] password) throws Exception {
        JSONObject packed = new JSONObject(text);
        if (!BackupCrypto.FORMAT.equals(packed.optString("format", ""))) {
            throw new BackupCrypto.BackupFormatException("Formato de backup desconhecido");
        }
        byte[] plain = BackupCrypto.open(password, packed.getInt("iterations"),
                packed.getString("salt"), packed.getString("iv"), packed.getString("data"));
        JSONObject root = new JSONObject(new String(plain, StandardCharsets.UTF_8));
        return new ParsedBackup(parseEntries(root), root.optLong("exportedAt", 0));
    }

    /** Abre um arquivo do formato local antigo (só funciona na mesma instalação). */
    ParsedBackup openLegacy(String text) {
        try {
            JSONObject root = new JSONObject(crypto.decrypt(text));
            return new ParsedBackup(parseEntries(root), root.optLong("updatedAt", 0));
        } catch (Exception error) {
            throw new IllegalStateException(LEGACY_MESSAGE);
        }
    }

    private static List<VaultEntry> parseEntries(JSONObject root) throws JSONException {
        if (root.optInt("version", 1) != 1 || root.optJSONArray("entries") == null) {
            throw new JSONException("Backup sem registros");
        }
        JSONArray array = root.getJSONArray("entries");
        ArrayList<VaultEntry> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            result.add(VaultEntry.fromJson(array.getJSONObject(i)));
        }
        return result;
    }

    /**
     * Grava o principal: primeiro o temporário (com sync), depois copia o principal
     * atual para o .bak e troca o temporário pelo principal. Nunca deixa o principal ausente.
     */
    private void writeAtomically(String content) throws Exception {
        File temporary = new File(directory, FILE_NAME + ".tmp");
        writeFile(temporary, content.getBytes(StandardCharsets.UTF_8));
        if (vaultFile.exists()) {
            writeSynced(backupFile, readBytes(vaultFile));
        }
        if (!temporary.renameTo(vaultFile)) {
            throw new IllegalStateException("Não foi possível concluir o salvamento");
        }
    }

    private static void writeSynced(File target, byte[] data) throws Exception {
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        writeFile(temporary, data);
        if (!temporary.renameTo(target)) {
            throw new IllegalStateException("Não foi possível concluir o salvamento");
        }
    }

    private static void writeFile(File file, byte[] data) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(data);
            output.flush();
            output.getFD().sync();
        }
    }

    private static byte[] readBytes(File file) throws Exception {
        try (InputStream input = new FileInputStream(file)) {
            return readAll(input);
        }
    }

    private static String readFile(File file) throws Exception {
        return new String(readBytes(file), StandardCharsets.UTF_8);
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
