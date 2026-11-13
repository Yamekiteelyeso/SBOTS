package dev.sbot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Downloads public Mojang signed textures asynchronously, with a local disk cache. */
final class SkinService {
    record Skin(String value, String signature) { }

    private final SBotPlugin plugin;
    private final ExecutorService io = Executors.newFixedThreadPool(2, runnable -> {
        Thread t = new Thread(runnable, "SBot-SkinLookup");
        t.setDaemon(true);
        return t;
    });
    private final File cacheFile;
    private final YamlConfiguration cache;

    SkinService(SBotPlugin plugin) {
        this.plugin = plugin;
        cacheFile = new File(plugin.getDataFolder(), "skins.yml");
        cache = YamlConfiguration.loadConfiguration(cacheFile);
    }

    /** Pure local cache lookup; never waits for HTTPS on the server thread. */
    Skin cached(String name) {
        if (!plugin.getConfig().getBoolean("load-premium-skins", true)
                || !plugin.getConfig().getBoolean("skin-cache", true)) return null;
        String key = name.toLowerCase(Locale.ROOT);
        String value = cache.getString(key + ".value");
        String signature = cache.getString(key + ".signature");
        return value == null || signature == null ? null : new Skin(value, signature);
    }

    void load(String name, Consumer<Skin> callback) {
        if (!plugin.getConfig().getBoolean("load-premium-skins", true)) {
            callback.accept(null);
            return;
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (plugin.getConfig().getBoolean("skin-cache", true)) {
            String value = cache.getString(key + ".value");
            String signature = cache.getString(key + ".signature");
            if (value != null && signature != null) {
                callback.accept(new Skin(value, signature));
                return;
            }
        }
        io.execute(() -> {
            Skin skin;
            try {
                skin = requestMojangSkin(name);
            } catch (Exception ex) {
                plugin.getLogger().warning("Skin no disponible para " + name + ": " + ex.getMessage());
                skin = null;
            }
            Skin found = skin;
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (found != null && plugin.getConfig().getBoolean("skin-cache", true)) {
                    cache.set(key + ".value", found.value());
                    cache.set(key + ".signature", found.signature());
                    try {
                        cache.save(cacheFile);
                    } catch (IOException ex) {
                        plugin.getLogger().warning("No se pudo guardar skins.yml: " + ex.getMessage());
                    }
                }
                callback.accept(found);
            });
        });
    }

    private Skin requestMojangSkin(String name) throws IOException {
        JsonObject user;
        try {
            user = getJson("https://api.minecraftservices.com/minecraft/profile/lookup/name/" + name);
        } catch (IOException primaryError) {
            // The older public lookup endpoint provides a fallback during API disruptions.
            user = getJson("https://api.mojang.com/users/profiles/minecraft/" + name);
        }
        if (user == null || !user.has("id")) return null;
        String mojangId = user.get("id").getAsString();
        JsonObject details = getJson("https://sessionserver.mojang.com/session/minecraft/profile/"
                + mojangId + "?unsigned=false");
        if (details == null || !details.has("properties")) return null;
        JsonArray properties = details.getAsJsonArray("properties");
        for (JsonElement element : properties) {
            JsonObject property = element.getAsJsonObject();
            if (!"textures".equals(property.get("name").getAsString())) continue;
            if (!property.has("value") || !property.has("signature")) return null;
            return new Skin(property.get("value").getAsString(), property.get("signature").getAsString());
        }
        return null;
    }

    private JsonObject getJson(String address) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(address).toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(4500);
        conn.setReadTimeout(4500);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "SBot/1.0 (Paper 1.20.1)");
        try {
            int code = conn.getResponseCode();
            if (code == 204 || code == 404) return null;
            if (code != 200) throw new IOException("Mojang HTTP " + code);
            try (var reader = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } finally {
            conn.disconnect();
        }
    }

    void close() { io.shutdownNow(); }
}
