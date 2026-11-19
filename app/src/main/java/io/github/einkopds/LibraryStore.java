package io.github.einkopds;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.UUID;

final class LibraryStore {
    static final class Source {
        final String id;
        final String name;
        final String address;
        final String user;
        final String password;

        Source(String id, String name, String address, String user, String password) {
            this.id = id;
            this.name = name;
            this.address = address;
            this.user = user;
            this.password = password;
        }

        Source(String name, String address, String user, String password) {
            this(UUID.randomUUID().toString(), name, address, user, password);
        }
    }

    private final SharedPreferences preferences;
    final ArrayList<Source> sources = new ArrayList<>();

    LibraryStore(Context context) {
        preferences = context.getSharedPreferences("library", Context.MODE_PRIVATE);
        try {
            JSONArray array = new JSONArray(preferences.getString("sources", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null || !item.has("id") || !item.has("address")) continue;
                sources.add(new Source(item.optString("id"), item.optString("name"),
                        item.optString("address"), item.optString("user"), item.optString("password")));
            }
        } catch (JSONException ignored) {
        }
    }

    Source find(String id) {
        for (Source source : sources) {
            if (source.id.equals(id)) return source;
        }
        return null;
    }

    void save(Source source) {
        Source previous = find(source.id);
        if (previous == null) sources.add(source);
        else sources.set(sources.indexOf(previous), source);
        saveSources();
    }

    void remove(Source source) {
        sources.remove(source);
        saveSources();
    }

    private void saveSources() {
        JSONArray array = new JSONArray();
        for (Source source : sources) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", source.id);
                item.put("name", source.name);
                item.put("address", source.address);
                item.put("user", source.user);
                item.put("password", source.password);
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
            array.put(item);
        }
        preferences.edit().putString("sources", array.toString()).apply();
    }

    Uri folder() {
        String value = preferences.getString("folder", null);
        return value == null ? null : Uri.parse(value);
    }

    void setFolder(Uri uri) {
        preferences.edit().putString("folder", uri.toString()).remove("folder_name").apply();
    }
}
