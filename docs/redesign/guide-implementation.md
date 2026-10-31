# Guide d'implémentation du redesign (à lire par chaque agent d'écran)

Worktree : `C:\Users\mikki\Documents\Ossm-redesign` (branche `ossm/redesign`). Travaille UNIQUEMENT là, jamais dans `C:\Users\mikki\Documents\Ossm`.

## Sources de vérité
1. `design-system/ossm-remote/MASTER.md` : couleurs, typo, espacement, composants, accessibilité, sécurité.
2. `docs/redesign/contrat-fonctionnel/` : l'inventaire de chaque écran. **Rien de ce qui y est listé ne peut disparaître ni changer de comportement.**
3. `ui/theme/` : jetons déjà posés (`OssmColor…`, `OssmDimens`, `OssmShapes`, `OssmValue`, `OssmDisplayValue`, `OssmSectionTitle`, `OssmCaption`). Utilise-les ; aucun hex, aucune taille de police en dur dans un écran.

## Ce que tu peux faire
- Changer l'habillage et la mise en page : couleurs via jetons, formes, espacements, hiérarchie visuelle, regroupement en cartes, sections repliables, libellés plus clairs, icônes, états vides, retours visuels.
- Extraire des composables plus petits dans le même fichier ou dans `ui/components/`.
- Ajouter `contentDescription`, `semantics` (rôle, `stateDescription`), `testTag` stables.
- Ajouter des chaînes : FR dans `res/values/`, EN dans `res/values-en/` (même clé dans les deux, `StringsParityTest`). UTF-8 sans BOM. Corriger les textes FR codés en dur qui sont dans ton écran en les passant par `stringResource`.

## Ce que tu ne dois PAS faire
- Toucher à la logique : `session/`, `ble/`, `usb/`, `remote/`, `voice/`, `natural/`, `updater/` (sauf affichage), `viewmodel/`, ni le protocole, ni le mapping Live.
- Changer la signature d'un composable public, d'un callback ou d'un état exposé par un ViewModel. Tu peux ajouter un paramètre avec valeur par défaut si c'est indispensable.
- Supprimer un bouton, un réglage, un mode, une condition d'affichage, une confirmation existante.
- Retirer ou masquer la barre STOP, la rendre plus petite que 64 dp de haut ou la faire recouvrir par du contenu.
- Mentionner une IA, un nom ou des initiales dans le code ou les commentaires.
- Lancer Gradle. **Un seul Gradle à la fois sur tout le PC** : c'est le coordinateur qui compile. Relis ton code avec soin (imports, noms de jetons, paramètres) puisque tu ne peux pas compiler.
- Faire une opération Git (pas de commit, pas de reset, etc.).
- Toucher aux fichiers d'un autre agent (voir la répartition donnée dans ta mission).

## Exigences de qualité
- Cibles tactiles ≥ 48 dp (`Modifier.minimumInteractiveComponentSize()` ou `heightIn(min = OssmDimens.TouchTarget)`), écart ≥ 8 dp.
- Texte ≥ 12 sp ; aucune information portée par la couleur seule (ajoute icône ou mot).
- Valeurs numériques en `OssmValue` / `OssmDisplayValue`.
- Mode compact : l'appli doit rester utilisable sur le Moto G (petit écran) et à 130 % de taille de police sans texte coupé ni débordement horizontal.
- Garder toutes les conditions d'affichage de l'ancien code (mode, connexion, session Remote, sécurité).

## Compte rendu attendu
À la fin, écris dans `docs/redesign/livraison/<ton-ecran>.md` : fichiers modifiés, ce qui a changé visuellement, tableau « élément du contrat → où il est dans le nouveau code », points que tu n'as pas pu vérifier. Puis réponds avec un résumé de 10 lignes.
