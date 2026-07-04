# Contrôle à distance (onglet « Remote ») — Design

**Branche dédiée : `claude/remote-control`. WIP — à compléter puis merger dans la branche principale une fois testé et fonctionnel.**

## But
Synchroniser deux instances de l'app pour piloter UNE machine OSSM à distance, via Internet.
- **Client A** = l'hôte : possède la machine (connexion BLE locale).
- **Client B** = le distant : ailleurs, sans machine, veut contrôler celle de A.
- Appairage par un **code** affiché chez A ; B colle le code et se connecte.
- Option côté B : **prendre le contrôle exclusif** (A ne pilote plus, seul B pilote).

## Contrainte « sans serveur » — analyse honnête
Un vrai P2P Internet **sans aucun point de rendez-vous est impossible** (NAT/pare-feu). Il faut au minimum un relais de signalisation. Options, du plus simple au plus « pur » :

1. **Broker MQTT public** (ex. `broker.hivemq.com`, `test.mosquitto.org`) — le code = espace de topics. A publie l'état, B publie les commandes. ✅ simple, traverse le NAT, aucun serveur à héberger. ⚠️ broker public = non garanti + à chiffrer nous-mêmes. → **choix recommandé pour le MVP.**
2. **WebRTC DataChannel** (P2P direct) + petite signalisation — vrai pair-à-pair, faible latence. ⚠️ complexe en natif Android, nécessite STUN/TURN. → **cible d'évolution** une fois le MVP validé.
3. **Relais Nostr** (pub/sub décentralisé) — « sans serveur » dans l'esprit, robuste. Bon compromis intermédiaire.
4. Firebase / relais maison — écartés (serveur à gérer/payer).

**Décision MVP : transport abstrait (`RemoteTransport`) + implémentation MQTT public.** Le code à 9 chiffres sert d'ID de session (namespace de topics). WebRTC pourra remplacer l'implémentation sans toucher au reste (c'est tout l'intérêt de l'abstraction). **À confirmer avec l'utilisateur avant d'écrire la couche réseau.**

## Sécurité / consentement (CRITIQUE — appareil intime piloté à distance)
- **A garde toujours la main sur l'arrêt.** Le « contrôle exclusif » de B ne bloque JAMAIS le bouton STOP de A ni sa capacité à **couper la session**. Exclusif = B envoie les consignes (vitesse/profondeur/pattern), mais A peut à tout moment : STOP, et « Terminer la session ».
- **Consentement explicite** : A doit être dans l'onglet Remote avec le partage ACTIF pour qu'un code soit valide. Fermer l'onglet / couper le partage invalide le code.
- **Le code est un secret partagé** : quiconque l'a peut piloter. 9 chiffres = 1e9 combinaisons → risque de balayage sur un broker public. Mitigations : dérivation d'un secret plus long à partir du code + sel de session ; expiration de session ; le partage n'est actif que pendant la fenêtre où A l'affiche ; possibilité de régénérer le code.
- **Chiffrement applicatif** des messages (le broker public ne voit que du chiffré) — clé dérivée du code + sel échangé.
- **Les gardes existantes s'appliquent aussi aux commandes distantes** : confirmation d'à-coup, bornes de profondeur, mapping Live. Une commande distante passe par le MÊME `ControlViewModel`/`BleManager` que le local.
- **Indicateur visuel fort chez A** : « Contrôlé à distance par B » + qui a le contrôle, en permanence.

## Architecture (couches propres, compatibles port iOS)
```
remote/
  RemoteTransport.kt        interface (connect(code)/send(msg)/incoming: Flow/disconnect)   [contrat commun iOS]
  MqttRemoteTransport.kt    impl Android (MVP)                                              [spécifique Android]
  RemoteProtocol.kt         sérialisation des messages (réutilise OssmCommand + MachineState)
  RemoteModels.kt           RemoteRole, RemoteConnectionState, RemoteControlOwner, génération de code
viewmodel/
  RemoteViewModel.kt        état de session, rôle, propriété du contrôle ; pont commandes<->transport<->BleManager
ui/screens/
  RemoteScreen.kt           onglet : mon code / coller un code + connecter / statut / case contrôle exclusif
```
- **Rôles** : `HOST` (A, a la machine) / `REMOTE` (B). L'app détecte : connectée en BLE → peut être HOST ; sinon REMOTE.
- **Flux HOST** : reçoit les commandes de B → les passe à `ControlViewModel`/`BleManager` (avec gardes) ; publie l'état machine vers B.
- **Flux REMOTE** : l'UI de contrôle envoie les commandes au `RemoteTransport` au lieu du BLE ; reçoit et affiche l'état de la machine de A.
- **Propriété du contrôle** (`RemoteControlOwner = LOCAL | REMOTE`) : par défaut LOCAL (A). Si B coche « contrôle exclusif », owner = REMOTE ; l'UI de A passe en lecture seule SAUF STOP/fin de session.

## Protocole de messages (JSON, réutilise l'existant)
- `hello {role, sessionCode}` — établissement.
- `cmd {type, params}` — mappe 1:1 sur `OssmCommand` (speed/depth/stroke/sensation/pattern/stream/stop/enterStreaming/activatePattern…).
- `state {…MachineState…}` — l'hôte diffuse l'état (position, stroke, depth, speed, connexion).
- `control {owner}` — transfert de propriété du contrôle.
- `bye {}` — fin de session.

## Découpage en incréments
1. **(cet incrément)** Design + squelette : onglet Remote (UI), `RemoteViewModel` (génère le code, état, stubs), interface `RemoteTransport` + stub no-op. **Compile, isolé, aucun réseau encore.**
2. Transport MQTT public + `RemoteProtocol` (sérialisation) + chiffrement dérivé du code. Appairage A↔B réel.
3. Pont HOST : commandes reçues → `BleManager` (avec gardes) ; diffusion de l'état.
4. Pont REMOTE : UI de contrôle → transport ; affichage de l'état distant.
5. Contrôle exclusif + garde-fous d'arrêt de A + indicateurs visuels.
6. Tests bout-à-bout (deux appareils), gestion des pertes de connexion, expiration/regeneration du code.
7. Évolution : WebRTC (P2P direct) derrière la même interface.

## À confirmer avec l'utilisateur avant l'incrément 2
- **Transport** : broker MQTT public (simple, MVP) — OK ? ou viser WebRTC directement ?
- **Longueur/format du code** : 9 chiffres confirmés (on dérive un secret plus long en interne pour la sécurité).
- **Contrôle exclusif** : confirmer que A garde TOUJOURS STOP + fin de session, même en exclusif.
