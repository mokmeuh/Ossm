# Tache OSSM Android

## Demande utilisateur consolidee
- L'app Android native OSSM doit traiter `strokeEngine` comme un pattern reel parmi d'autres, pas comme un mode global cache.
- Quand `strokeEngine` est actif, l'UI principale doit montrer seulement:
  - `Vitesse`
  - `Profondeur` sous forme de plage decalable `min/max`
- La profondeur percue par l'utilisateur doit pouvoir representer:
  - `0 -> 100`
  - `50 -> 100`
  - `0 -> 50`
  - et toute autre plage intermediaire
- Les anciens sliders `strokeLength` et `sensation` ne doivent plus etre exposes comme s'ils etaient des controles utilisateur normaux pour `strokeEngine`.
- Les patterns ne doivent plus etre inventes localement sous la forme `Pattern 1/2/3`.
- Si le firmware fournit une vraie liste, l'app doit afficher les vrais noms.
- Si la liste n'est pas recuperable de facon fiable, l'app ne doit pas faker une liste complete; seul `strokeEngine` est explicitement connu comme valide localement.
- Ajouter une protection contre les changements brusques manuels de plus de `5 %`:
  - popup de confirmation
  - option `Ne pas me le rappeler pour cette session`
  - option `Ne plus jamais me le redemander`
  - option visible dans l'app pour activer/desactiver cette protection
- Mettre les presets en phase avec cette nouvelle semantique.
- Creer `TASK.md`, `HANDOFF.md`, `CLAUDE.md` et `AGENTS.md` pour faciliter une reprise manuelle dans Claude si necessaire.

## Regles produit retenues
- `strokeEngine` est un pattern disponible parmi plusieurs patterns.
- L'UI retenue est `UI specifique par pattern`.
- Les autres patterns restent selectionnables/lancables meme si leurs reglages reels ne sont pas encore exposes.
- La protection anti-saut s'applique aux changements manuels de controles, pas aux presets ni au simple lancement d'un pattern.

## Traduction technique importante
- Les threads OSSM historiques confirment le protocole BLE texte existant:
  - `go:strokeEngine`
  - `go:home`
  - `go:restart`
  - `set:speed`
  - `set:stroke`
  - `set:depth`
  - `set:sensation`
  - `set:pattern`
- Le firmware connu ne confirme pas `set:depthMin` / `set:depthMax`.
- La plage utilisateur `depthMin/depthMax` doit donc etre traduite en:
  - `stroke = depthMax - depthMin`
  - `depth = depthMax`

## Validation attendue
- `./gradlew installDebug` doit continuer a passer.
- L'app doit s'installer sur l'appareil Android deja utilise.
- Aucun pattern fictif `Pattern 1/2/3` ne doit rester affiche.
