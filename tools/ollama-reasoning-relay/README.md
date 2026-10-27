# Relais de raisonnement OSSM (Qwen3 / Ollama)

Petit relais Node.js (sans dépendance npm) entre l'app OSSM Remote et l'Ollama du PC.
Il écrit dans `logs/raisonnement.log` le chargement du modèle, le **raisonnement** de Qwen3
puis le **plan** JSON, en direct et horodatés. Il n'ouvre jamais de fenêtre tout seul.

## Démarrer

1. Double-clic sur `Start-OssmReasoningRelay.cmd`.
   Le relais démarre dans une fenêtre minimisée « OSSM - relais raisonnement » et le script
   affiche l'adresse à saisir dans l'app, par exemple `100.x.y.z:11435`
   (IPv4 Tailscale redécouverte à chaque démarrage avec `tailscale ip -4`).
2. Dans l'app, mode Naturel > Planification IA : saisir l'adresse Tailscale du PC
   (`100.x.y.z`, le port est remplacé automatiquement) et activer
   **« Passer par le relais de raisonnement (PC) »** (désactivé par défaut).
   L'app utilise alors le port 11435 et demande `think:true` ; option désactivée,
   rien ne change (port 11434, `think:false`).
3. Pour lire le raisonnement, **quand tu veux** (avant, pendant ou après) :
   double-clic sur `Voir-Raisonnement-Qwen3.cmd`. La console « OSSM - raisonnement Qwen3 »
   montre les ~80 dernières lignes puis le direct. La fermer ou la rouvrir n'a aucun effet
   sur le relais ni sur l'app.

Manuel : `node relay.js` (port modifiable avec la variable `OSSM_RELAY_PORT`).

## Arrêter

Double-clic sur `Stop-OssmReasoningRelay.cmd` : arrête uniquement le processus `node` dont la
ligne de commande contient le `relay.js` de ce dossier (et sa fenêtre), jamais un autre `node`.
Sinon : Ctrl+C dans la fenêtre « OSSM - relais raisonnement » (ou fermer cette fenêtre).
Si le relais tourne déjà, `Start-OssmReasoningRelay.cmd` le détecte (test TCP sur 11435) et
n'en lance pas un second. La fenêtre du relais reste ouverte même en cas d'erreur (`cmd /k`).
Aucune tâche planifiée ni démarrage automatique n'est installé.

## Ce que fait le relais

- `GET /api/tags`, `/api/ps`, `/api/version` : transmis tels quels à `http://127.0.0.1:11434`.
- `POST /api/generate` avec prompt vide (chargement) : transmis tel quel ;
  journal « Chargement de qwen3:8b demandé par l'app… » puis « Modèle chargé (N s) ».
- `POST /api/generate` avec prompt (plan) : envoyé à Ollama en `stream:true` + `think:true`
  (au moins 2048 jetons de sortie et 4096 de contexte), sections RAISONNEMENT et PLAN écrites
  en direct, puis **une seule** réponse équivalente à `stream:false` renvoyée à l'app (même code
  HTTP). Le champ `ossm_pacing` éventuel (avance de gestes, latence) est journalisé puis retiré.
- Toute autre route : 404. Délai maximal 300 s ; une annulation côté app annule la requête Ollama.

Côté app, quand « Utiliser l'IA » est actif et le modèle chargé, **tous** les gestes viennent
de l'IA : au démarrage l'app attend le premier plan validé (vitesse 0), puis demande le plan
suivant dès que la réserve de gestes passe sous 3 × la latence mesurée + 20 s (une requête à
la fois ; plans de 6 phrases via le relais). Le générateur local ne prend le relais que si
l'IA est défaillante (réserve épuisée, erreur, délai, JSON invalide, modèle déchargé) : bascule
affichée « IA défaillante → générateur local (cause) », journalisée, et retour à l'IA au plan
valide suivant. L'app envoie aussi un champ `ossm_pacing` (avance, latence) que le relais
écrit dans le journal (« Avance IA : N s de gestes en réserve… ») puis retire.

## Sécurité

- Écoute **uniquement** sur `127.0.0.1` et l'IPv4 Tailscale du PC (100.64.0.0/10), port 11435.
  Jamais sur `0.0.0.0`. Sans Tailscale, écoute en local seulement.
- Aucune règle de pare-feu n'est créée ni modifiée ; la configuration d'Ollama n'est pas touchée.
- Le journal ne contient ni adresse IP cliente ni secret ; `logs/` est ignoré par git
  (rotation à 5 Mo au démarrage).

## Tests

```
node --test
```
