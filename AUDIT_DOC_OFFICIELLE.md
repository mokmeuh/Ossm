# Audit — app OSSM Remote vs documentation officielle

Source : https://docs.researchanddesire.com/ossm/Software/ (copie locale `documentation.txt`, vérifiée conforme).
App auditée : v1.28.6 (versionCode 98). Portée : conformité au protocole BLE officiel, pas de modification du mapping directionnel du Live (verrouillé par tests appareil).

## Couverture des caractéristiques BLE

| Caractéristique | UUID | Doc | App | État |
| --- | --- | --- | --- | --- |
| Commande principale | `…1000…` | set/go/stream | `writeRaw`, `sendCommand` | ✅ complet |
| Config bouton vitesse | `…1010…` | `true`/`false` | `writeSpeedKnobIndependent` (`false`) | ✅ (voir note 1) |
| Config WiFi | `…1020…` | `set:wifi:ssid\|pwd` | `WIFI_CHAR_UUID` | ✅ présent |
| Compensation latence | `…1030…` | `true`/`false` | `LATENCY_UUID` | ✅ (voir note 2) |
| État courant | `…2000…` | JSON NOTIFY | souscrit + parsé | ✅ complet |
| Liste patterns | `…3000…` | JSON | `PATTERN_LIST_UUID` | ✅ présent |
| Description pattern | `…3010…` | write idx / read | `PATTERN_DESC_UUID` | ✅ présent |
| **Contrôle GPIO** | **`…4000…`** | `pin:state`, read `pins:[1,2,3,4]` | — | ❌ **absent** |
| Device Information | `180A` (2A29/2A23) | identification | — | ⚪ non implémenté (optionnel) |
| Émulation FTS | `ffe0/ffe1` | test only | — | ⚪ non implémenté (déconseillé par la doc) |

## Commandes (caractéristique 1000)

Toutes les commandes documentées sont implémentées : `set:speed|stroke|depth|sensation|pattern`,
`go:simplePenetration|strokeEngine|streaming|menu`, `stream:<pos>:<time>`. Les 7 patterns (0–6) sont
gérés. Conversion plage utilisateur `min/max` → `stroke = max − min`, `depth = max` conforme au
modèle StrokeEngine (depth = point profond, stroke = amplitude). Ordre d'envoi `depth` avant `stroke`
correct (évite le passage transitoire au fond).

## Écarts et notes

1. **Bouton vitesse en mode `false` (indépendant).** La doc donne `true` (bouton = plafond) par
   défaut ; l'app force `false`. Choix délibéré et nécessaire : en streaming le bouton physique doit
   être à ~0 pour entrer (preflight), donc en mode « plafond » toute vitesse effective resterait 0 et
   les `stream:` seraient ignorés. Conforme à l'usage documenté.

2. **Compensation de latence désactivée pour le Live (corrigé en v1.28.6).** La doc précise :
   *« If the time between commands does not match the intime variable this should not be enabled »*.
   Le Live suit le doigt → timing irrégulier → la compensation faisait des corrections de vitesse
   erratiques. Désactivée. (À réévaluer pour la lecture Funscript, où le timing est pré-planifié.)

3. **Sens du Live (mapping 0↔100).** La doc dit `stream:0 = home`, `stream:100 = extended`. La formule
   réelle du firmware (`streaming_logic.h`) et les tests appareil donnent l'inverse ; l'utilisateur a
   choisi un mapping ergonomique confirmé. **Divergence délibérée, non corrigée** (verrouillée par
   l'historique de tests).

4. **Garde anti-crash firmware.** L'app bloque deux positions `stream:` consécutives identiques
   (division par zéro côté firmware). Bonne défense, non documentée mais correcte.

## Recommandations (par priorité)

- **Cadence Live (fait, v1.28.6)** : la cadence 5 ms / move 5 ms de la v1.28.5 saturait la pile BLE et
  provoquait les saccades. Repassée à ~30 ms / move 45 ms (chevauchement) + latence désactivée.
- **GPIO (`…4000…`)** : seule caractéristique documentée non implémentée. Feature « maker » (LED,
  relais). À ajouter si besoin — écran de bascule 4 broches high/low + lecture `pins:[…]`.
- **Device Information Service** : optionnel, utile seulement pour afficher fabricant/modèle.
