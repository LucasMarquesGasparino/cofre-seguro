package com.cofreseguro;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

final class VaultEntry {
    final String id;
    String site;
    String username;
    String password;
    String notes;
    boolean favorite;
    int accessCount;
    long createdAt;
    long updatedAt;

    VaultEntry(String site, String username, String password) {
        this(UUID.randomUUID().toString(), site, username, password, "", false, 0,
                System.currentTimeMillis(), System.currentTimeMillis());
    }

    VaultEntry(String id, String site, String username, String password, String notes,
               boolean favorite, int accessCount, long createdAt, long updatedAt) {
        this.id = id;
        this.site = site == null ? "" : site;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        this.notes = notes == null ? "" : notes;
        this.favorite = favorite;
        this.accessCount = accessCount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    VaultEntry copy() {
        return new VaultEntry(id, site, username, password, notes, favorite, accessCount,
                createdAt, updatedAt);
    }

    /** Copia todos os campos, exceto o id. */
    void copyFrom(VaultEntry other) {
        site = other.site;
        username = other.username;
        password = other.password;
        notes = other.notes;
        favorite = other.favorite;
        accessCount = other.accessCount;
        createdAt = other.createdAt;
        updatedAt = other.updatedAt;
    }

    JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("site", site);
        object.put("username", username);
        object.put("password", password);
        object.put("notes", notes);
        object.put("favorite", favorite);
        object.put("accessCount", accessCount);
        object.put("createdAt", createdAt);
        object.put("updatedAt", updatedAt);
        return object;
    }

    static VaultEntry fromJson(JSONObject object) {
        String site = text(object, "site").trim();
        if (site.length() == 0) {
            throw new IllegalArgumentException("Registro sem nome de site");
        }
        String id = text(object, "id").trim();
        if (id.length() == 0) {
            id = UUID.randomUUID().toString();
        }
        return new VaultEntry(
                id,
                site,
                text(object, "username"),
                text(object, "password"),
                text(object, "notes"),
                object.optBoolean("favorite", false),
                Math.max(0, object.optInt("accessCount", 0)),
                object.optLong("createdAt", System.currentTimeMillis()),
                object.optLong("updatedAt", System.currentTimeMillis())
        );
    }

    /** Lê um texto do JSON tratando ausência e JSONObject.NULL como vazio. */
    private static String text(JSONObject object, String key) {
        return object.isNull(key) ? "" : object.optString(key, "");
    }
}
