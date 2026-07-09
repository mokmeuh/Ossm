# Notes inter-agents

## Journal court
- 2026-06-22: reprise Codex depuis un clone sans fichiers de handoff.
- 2026-06-22: lecture de `README.md`, `git status`, logs locaux, fichiers BLE/UI/tests.
- 2026-06-22: l'utilisateur a confirme que `strokeEngine` est seulement un pattern parmi d'autres.
- 2026-06-22: l'utilisateur a fourni deux threads Codex OSSM historiques comme source utile.
- 2026-06-22: ces threads ont confirme le protocole BLE texte existant et ont evite l'invention de `set:depthMin` / `set:depthMax`.
- 2026-06-22: implementation de la garde anti-saut, refonte des presets, migration du controle vers `pattern actif`.

## Regles de continuité
- Explorer d'abord la verite locale avant de demander.
- Si un ancien thread OSSM confirme un detail de protocole, preferer cette preuve au comportement UI provisoire du clone.
- Quand la verite firmware est incomplete, choisir une degradation honnete plutot qu'un faux comportement.
- Pour ce clone, ne pas casser volontairement la capacite de `installDebug` sur le Samsung deja detecte.

## Verification de fin d'etat
- `testDebugUnitTest` passe
- `installDebug` passe
- device confirme:
  - `SM-S938W - 16`
