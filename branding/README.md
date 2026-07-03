# Identité visuelle OSSM Remote

## Concept
Un « O » ouvert en haut et en bas (l'anneau ne se referme pas : la course le traverse),
traversé par une tige à double chevron = **mouvement linéaire contrôlé**.
Deux arcs à droite = **signal BLE** émis par la machine.
Dégradé violet → cyan repris directement du thème de l'app
(`OssmPrimary #7C4DFF` → `OssmAccent #00E5FF` sur fond `#0A0A0F`).

## Fichiers
- `ossm_logo_principal.svg` — logo complet sur fond sombre arrondi (marketing, splash).
- `ossm_logo_monochrome.svg` — 1 couleur (`currentColor`), fond transparent : filigranes,
  notifications, impression.
- `ossm_icone_app.svg` — motif réduit à 62 % pour la zone sûre des adaptive icons Android
  (masques rond / squircle). Fond plein → utilisable aussi comme foreground+background.

## Intégration Android (étape future)
1. Android Studio → `File > New > Image Asset` → Launcher Icons (Adaptive and Legacy).
2. Foreground : `ossm_icone_app.svg` (le motif), Background : `#0A0A0F`.
3. Générer les mipmap ; le nom d'app reste `OSSM V<version>` (resValue existant).

## Déclinaisons prévues
- iOS : le même motif fonctionne sans les coins arrondis (masque appliqué par iOS).
- Favicon / petit format : garder anneau + tige, retirer les arcs BLE sous 48 px.
