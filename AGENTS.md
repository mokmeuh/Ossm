# Notes inter-agents

## Perimetre
- Depot hybride: `app/` est le client Android Kotlin/Compose; `firmware/` est une copie PlatformIO/ESP32; `tools/update-server/` sert les APK de debug.
- Pour le firmware, lire d'abord `firmware/AGENTS.md`, puis `firmware/Software/CLAUDE.md`. Ne pas recopier leur politique de release ici.
- Les instructions de `app/` sont dans `app/AGENTS.md`.
- Les caches, dependances et worktrees a exclure de toute analyse: `.gradle/`, `.kotlin/`, `app/build/`, `firmware/Software/.pio/`, `firmware/Software/.cache/`, `.opencode/node_modules/`, `.claude/worktrees/`, `.omo/`, `.codex/`, `.codegraph/` et `compile_commands.json`.

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

## Commandes Android
- `./gradlew testDebugUnitTest` puis `./gradlew installDebug`; ne pas lancer deux builds Gradle en parallele.
- Le package debug est `com.ossm.remote.debug`. Pour diagnostiquer le BLE: `adb logcat -s OSSM`.
- Le worktree est volontairement sale: ne jamais faire `reset`, `checkout`, `clean`, `stash`, rebase ou commit automatique; conserver les changements et fins de ligne existants.

## Verites BLE
- Protocole texte existant: `go:menu|strokeEngine|streaming|simplePenetration`, `set:speed|stroke|depth|sensation|pattern`, `stream:pos:time`.
- Ne jamais inventer `set:depthMin` ou `set:depthMax`: une plage utilisateur devient `stroke = depthMax - depthMin`, `depth = depthMax`.
- `streaming_logic.h` et `documentation.txt` sont les references firmware pour le sens du stream. Le client conserve l'inversion testee (`100 - raw`, borne 2..98) et la compensation de latence desactivee pour le Live. Ne pas changer sans nouveau test appareil filme.
- Les commandes de stream identiques consecutives sont filtrees pour eviter le piege firmware `distance / abs(distance)`.

## Routage de travail
- Taches legeres: `opencode-go/zen` ou famille DeepSeek.
- Raisonnement difficile: `gpt-sol` ou `gpt-luna`.
- Gros chantiers de code: `gpt-terra`.
- Cette preference documente le routage; elle ne modifie pas `opencode.json` ni les configurations utilisateur.
