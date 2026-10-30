#!/usr/bin/env python3
"""Copies Salon (the home screen, built standalone from a Free My TV checkout) into this Lumen project.

Usage: python3 tools/sync_salon.py /path/to/free-my-tv

Salon's Java classes keep their package (io.freemytv); only what differs inside Lumen is adapted here:
  - Config.java is written for this app (app id app.lumen.tv, Bezons settings, no keys: they come at runtime, see
    Secrets.java), since config.properties never goes to GitHub;
  - Salon's resources are referenced through Lumen's R class (app.lumen.tv.R);
  - its icon and banner are renamed (Lumen has its own), its styles and booleans get their own file names.
The manifest entries are kept by hand in app/src/main/AndroidManifest.xml.
"""
import os
import re
import shutil
import sys

SRC = sys.argv[1] if len(sys.argv) > 1 else "/tmp/free-my-tv"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, "app", "src", "main")

CONFIG = '''package io.freemytv;

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
'''


def copy_tree(src, dst):
    if os.path.exists(dst):
        shutil.rmtree(dst)
    shutil.copytree(src, dst)


def main():
    # Java sources
    java = os.path.join(MAIN, "java")
    for pkg in ("io/freemytv", "io/nayuki"):
        copy_tree(os.path.join(SRC, "app", "src", pkg), os.path.join(java, pkg))
    with open(os.path.join(java, "io/freemytv/Config.java"), "w") as f:
        f.write(CONFIG)
    for name in os.listdir(os.path.join(java, "io/freemytv")):
        path = os.path.join(java, "io/freemytv", name)
        s = open(path, encoding="utf-8").read()
        before = s
        s = s.replace("R.string.app_name", "R.string.salon_name")   # app_name is Lumen's ("Lumen TV")
        if re.search(r"(?<![.\w])R\.(string|drawable|bool|xml|style|color)\b", s) and "import app.lumen.tv.R;" not in s:
            s = s.replace("package io.freemytv;\n", "package io.freemytv;\n\nimport app.lumen.tv.R;\n", 1)
        if s != before:
            open(path, "w", encoding="utf-8").write(s)

    # Assets (Lumen has none of its own)
    copy_tree(os.path.join(SRC, "app", "assets"), os.path.join(MAIN, "assets"))

    # Resources
    res, sres = os.path.join(MAIN, "res"), os.path.join(SRC, "app", "res")
    os.makedirs(os.path.join(res, "drawable-nodpi"), exist_ok=True)
    shutil.copy(os.path.join(sres, "drawable-nodpi", "banner.png"), os.path.join(res, "drawable-nodpi", "salon_banner.png"))
    shutil.copy(os.path.join(sres, "drawable-nodpi", "icon.png"), os.path.join(res, "drawable-nodpi", "salon_icon.png"))
    os.makedirs(os.path.join(res, "drawable"), exist_ok=True)
    for f in os.listdir(os.path.join(sres, "drawable")):
        shutil.copy(os.path.join(sres, "drawable", f), os.path.join(res, "drawable", f))
    os.makedirs(os.path.join(res, "xml"), exist_ok=True)
    shutil.copy(os.path.join(sres, "xml", "remote_key_service.xml"), os.path.join(res, "xml", "remote_key_service.xml"))
    shutil.copy(os.path.join(sres, "values", "styles.xml"), os.path.join(res, "values", "salon_styles.xml"))
    for v in ("values", "values-v30", "values-v31"):
        os.makedirs(os.path.join(res, v), exist_ok=True)
        shutil.copy(os.path.join(sres, v, "bools.xml"), os.path.join(res, v, "salon_bools.xml"))
    # Strings: Salon's, with its name as salon_name (app_name is Lumen's)
    for v in ("values", "values-ru"):
        s = open(os.path.join(sres, v, "strings.xml"), encoding="utf-8").read()
        s = s.replace('<string name="app_name">', '<string name="salon_name">')
        os.makedirs(os.path.join(res, v), exist_ok=True)
        open(os.path.join(res, v, "salon_strings.xml"), "w", encoding="utf-8").write(s)
    print("Salon synced from", SRC)


if __name__ == "__main__":
    main()
