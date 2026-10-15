#!/bin/sh
# Fix aláírókulcs: a debug.keystore-t a build elé tesszük, hogy minden verzió ugyanazzal a kulccsal készüljön
# (így az új APK a régi fölé telepíthető). A cap sync után, a gradle build előtt fut le.
if [ -f debug.keystore ]; then
  mkdir -p "$HOME/.android" && cp debug.keystore "$HOME/.android/debug.keystore" && echo "Fix alairo kulcs beallitva"
else
  echo "FIGYELEM: nincs debug.keystore a repoban"
fi
# Ha van icon.png, abból készít Android app-ikont (a cap add/sync után fut le)
if [ -f icon.png ]; then
  mkdir -p assets
  cp icon.png assets/icon-only.png
  npx --yes @capacitor/assets generate --android --iconBackgroundColor '#0e142c' --iconBackgroundColorDark '#0e142c' || echo "Ikon generalas sikertelen, marad az alap ikon"
  # Az Android 8+ az "adaptive" XML-t részesíti előnyben (az alap Capacitor ikon) -> töröljük, hogy a saját PNG legyen használva
  rm -f android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
fi
exit 0
