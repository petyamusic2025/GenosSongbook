GENOS SONGBOOK – APK építése GitHubon (ingyen)

1. github.com -> Sign up (ingyenes fiók)
2. Új repository: jobb felül "+" -> New repository -> név: genos-songbook -> Private -> Create
3. "uploading an existing file" -> húzd be a zip KICSOMAGOLT tartalmát
   (a .github mappa is kell! www, native, package.json, capacitor.config.json)
   -> Commit changes
4. Fent "Actions" fül -> "Build APK" fut (kb. 5-8 perc). Zöld pipa = kész.
5. Kattints a futásra -> lent "Artifacts" -> GenosSongbook-APK -> letöltés (zip, benne app-debug.apk)
6. APK telepítése (ismeretlen forrás engedélyezése), első indításkor "Összes fájl elérése" engedély.
7. App: 📂 Betöltés -> "Documents/GenosSongbook beolvasása". Almappák = kategóriák.
