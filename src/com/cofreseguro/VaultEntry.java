package com.cofreseguro;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

final class VaultEntry {
    final String id;
    String site;
    String username;
    String password;
    int accessCount;
    long createdAt;
    long updatedAt;

    VaultEntry(String site, String username, String password) {
        this(UUID.randomUUID().toString(), site, username, password, 0,
                System.currentTimeMillis(), System.currentTimeMillis());
    }

    private VaultEntry(String id, String site, String username, String password,
                       int accessCount, long createdAt, long updatedAt) {
        this.id = id;
        this.site = site;
        this.username = username;
        this.password = password;
        this.accessCount = accessCount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("site", site);
        object.put("username", username);
        object.put("password", password);
        object.put("accessCount", accessCount);
        object.put("createdAt", createdAt);
        object.put("updatedAt", updatedAt);
        return object;
    }

    static VaultEntry fromJson(JSONObject object) {
        String site = object.optString("site", "").trim();
        if (site.length() == 0) {
            throw new IllegalArgumentException("Registro sem nome de site");
        }
        String id = object.optString("id", UUID.randomUUID().toString());
        return new VaultEntry(
                id,
                site,
                object.optString("username", ""),
                object.optString("password", ""),
                Math.max(0, object.optInt("accessCount", 0)),
                object.optLong("createdAt", System.currentTimeMillis()),
                object.optLong("updatedAt", System.currentTimeMillis())
        );
    }
}
