#!/bin/sh
# Ha van icon.png, abból készít Android app-ikont (a cap sync után fut le)
if [ -f icon.png ]; then
  mkdir -p assets
  cp icon.png assets/icon-only.png
  npx --yes @capacitor/assets generate --android --iconBackgroundColor '#0e142c' --iconBackgroundColorDark '#0e142c' || echo "Ikon generalas sikertelen, marad az alap ikon"
fi
exit 0
