package dev.skyblockplus.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class SkinFetcher {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** Blocking! call async. Returns {value, signature} or null. */
    public static String[] fetch(String playerName) {
        try {
            String r1 = get("https://api.mojang.com/users/profiles/minecraft/" + playerName);
            if (r1 == null) return null;
            String id = JsonParser.parseString(r1).getAsJsonObject().get("id").getAsString();
            String r2 = get("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false");
            if (r2 == null) return null;
            JsonArray props = JsonParser.parseString(r2).getAsJsonObject().getAsJsonArray("properties");
            for (var el : props) {
                JsonObject o = el.getAsJsonObject();
                if (o.get("name").getAsString().equals("textures"))
                    return new String[]{o.get("value").getAsString(), o.has("signature") ? o.get("signature").getAsString() : ""};
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String get(String url) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode() == 200 ? r.body() : null;
    }
}
