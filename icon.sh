#!/bin/sh
# Ha van icon.png, abból készít Android app-ikont (a cap add/sync után fut le)
if [ -f icon.png ]; then
  mkdir -p assets
  cp icon.png assets/icon-only.png
  npx --yes @capacitor/assets generate --android --iconBackgroundColor '#0e142c' --iconBackgroundColorDark '#0e142c' || echo "Ikon generalas sikertelen, marad az alap ikon"
  # Az Android 8+ az "adaptive" XML-t részesíti előnyben (az alap Capacitor ikon) -> töröljük, hogy a saját PNG legyen használva
  rm -f android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml
fi
exit 0
