package com.tunas.app;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

public class TuneFavorites {
    public enum StarColor {
        STAR(0xFFFFD700, "★");

        private final int color;
        private final String symbol;

        StarColor(int color, String symbol) {
            this.color = color;
            this.symbol = symbol;
        }

        public int getColor() {
            return color;
        }

        public String getSymbol() {
            return symbol;
        }

        public String getFilledSymbol() {
            return symbol;
        }

        public String getEmptySymbol() {
            return "☆"; // Empty star for unfavorited
        }
    }

    private static final String PREFS_NAME = "tunas_favorites";
    private static final String FAVORITES_KEY = "favorites";

    private final SharedPreferences preferences;
    private final Gson gson;
    private Map<String, Map<StarColor, Boolean>> favorites;

    public TuneFavorites(Context context) {
        this.preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.gson = new Gson();
        this.favorites = loadFavorites();
    }

    private Map<String, Map<StarColor, Boolean>> loadFavorites() {
        String json = preferences.getString(FAVORITES_KEY, null);
        if (json != null) {
            Type type = new TypeToken<Map<String, Map<StarColor, Boolean>>>(){}.getType();
            Map<String, Map<StarColor, Boolean>> loaded = gson.fromJson(json, type);
            return loaded != null ? loaded : new HashMap<>();
        }
        return new HashMap<>();
    }

    private void saveFavorites() {
        String json = gson.toJson(favorites);
        preferences.edit().putString(FAVORITES_KEY, json).apply();
    }

    public boolean isFavorited(String tuneName, StarColor color) {
        Map<StarColor, Boolean> tuneFavorites = favorites.get(tuneName);
        if (tuneFavorites == null) {
            return false;
        }
        Boolean favorited = tuneFavorites.get(color);
        return favorited != null && favorited;
    }

    public void setFavorited(String tuneName, StarColor color, boolean favorited) {
        Map<StarColor, Boolean> tuneFavorites = favorites.get(tuneName);
        if (tuneFavorites == null) {
            tuneFavorites = new HashMap<>();
            favorites.put(tuneName, tuneFavorites);
        }
        tuneFavorites.put(color, favorited);
        saveFavorites();
    }

    public void toggleFavorited(String tuneName, StarColor color) {
        boolean current = isFavorited(tuneName, color);
        setFavorited(tuneName, color, !current);
    }

    public boolean hasAnyFavorites(String tuneName) {
        Map<StarColor, Boolean> tuneFavorites = favorites.get(tuneName);
        if (tuneFavorites == null) {
            return false;
        }
        for (Boolean favorited : tuneFavorites.values()) {
            if (favorited != null && favorited) {
                return true;
            }
        }
        return false;
    }

    public boolean hasFavorite(String tuneName, StarColor color) {
        return isFavorited(tuneName, color);
    }
}
