package io.github.einkopds;

import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

enum AppLanguage {
    SYSTEM("", R.string.language_system),
    ENGLISH("en", R.string.language_en),
    CHINESE("zh-Hans", R.string.language_zh),
    JAPANESE("ja", R.string.language_ja);

    private static final String LANGUAGE = "language";
    final String tag;
    final int label;

    AppLanguage(String tag, int label) {
        this.tag = tag;
        this.label = label;
    }

    static AppLanguage selected(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleList locales = context.getSystemService(LocaleManager.class).getApplicationLocales();
            return locales.isEmpty() ? SYSTEM : fromTag(locales.get(0).toLanguageTag());
        }
        return fromTag(preferences(context).getString(LANGUAGE, ""));
    }

    void select(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager.class).setApplicationLocales(LocaleList.forLanguageTags(tag));
        } else {
            preferences(context).edit().putString(LANGUAGE, tag).apply();
        }
    }

    static Context localizedContext(Context base) {
        if (Build.VERSION.SDK_INT >= 33) return base;
        AppLanguage language = selected(base);
        if (language == SYSTEM) return base;
        Configuration override = new Configuration();
        override.setLocale(Locale.forLanguageTag(language.tag));
        return base.createConfigurationContext(override);
    }

    static void migrateLegacyPreference(Context context) {
        if (Build.VERSION.SDK_INT < 33) return;
        SharedPreferences preferences = preferences(context);
        String previous = preferences.getString(LANGUAGE, null);
        if (previous == null) return;
        preferences.edit().remove(LANGUAGE).apply();
        AppLanguage language = fromTag(previous);
        if (language != SYSTEM && selected(context) == SYSTEM) language.select(context);
    }

    private static AppLanguage fromTag(String tag) {
        switch (Locale.forLanguageTag(tag).getLanguage()) {
            case "en": return ENGLISH;
            case "zh": return CHINESE;
            case "ja": return JAPANESE;
            default: return SYSTEM;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("app_settings", Context.MODE_PRIVATE);
    }
}
