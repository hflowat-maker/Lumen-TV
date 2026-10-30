package io.freemytv;

/** Salon's settings inside Lumen (written by tools/sync_salon.py; keys come at runtime, see Secrets). */
final class Config {
    private Config() {}

    static final String APP_ID = "app.lumen.tv";
    static final String TMDB_KEY = "";
    static final String WATCHMODE_KEY = "";
    static final String TMDB_REGION = "FR";
    static final String LATITUDE = "48.931";
    static final String LONGITUDE = "2.208";
    static final String CITY = "Bezons";
    static final String LANGUAGE = "fr";
    static final String CURRENCY = "EUR";
    static final String TEMPERATURE = "C";
    static final String HIDE_SERVICES = "";
    static final boolean DISABLE_ASSISTANT = false;
    static final boolean SHOW_CRYPTO = true;
    static final boolean HIDE_ANIMATION = false;
}
