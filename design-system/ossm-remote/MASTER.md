# Design system : OSSM Remote « Instrument »

Source de vérité du redesign de l'appli Android (Compose, Material 3). Ne s'applique qu'à l'interface : aucune logique de contrôle, de BLE, de session ni de protocole n'est touchée.

## 1. Principe

L'appli pilote une machine physique, souvent d'une main, parfois dans le noir. Elle doit ressembler à un **instrument de précision** : calme, sombre, lisible d'un coup d'œil, avec **une seule couleur d'action** et un STOP impossible à rater.

Règles directrices :
1. **Le STOP gagne toujours.** Visible et jamais recouvert sur tous les écrans de contrôle, la seule grande surface rouge de l'appli. Exception voulue par l'utilisateur : l'écran Live « nu » (pad plein écran) n'affiche volontairement pas de barre STOP. Ne pas la rajouter.
2. **Une couleur = un sens.** Turquoise = action et vitesse, bleu = profondeur, lilas = sensation, ambre = prudence ou limite imposée, vert = connecté, rouge = arrêt ou erreur. Aucune couleur décorative.
3. **L'état se lit sans la couleur** : chaque état a aussi une icône ou un mot.
4. **Les valeurs sont des chiffres.** Toute valeur réglable affiche son nombre en police à chiffres de largeur fixe.
5. **Pas d'effet pour l'effet** : pas de flou, de lueur ni de dégradé décoratif. Les surfaces se distinguent par leur niveau (voir §3).
6. **Mode sombre uniquement** (usage nocturne, écran OLED). Pas de mode clair.

## 2. Couleurs (jetons)

Tous les ratios sont mesurés (WCAG) sur `fond` / `surface`.

| Jeton | Hex | Rôle | Contraste vs surface |
|---|---|---|---|
| `bg` | `#0D1014` | fond d'écran | n/a |
| `surface` | `#151A20` | cartes, panneaux | n/a |
| `surfaceRaised` | `#1D242C` | éléments interactifs au repos, champs | n/a |
| `surfaceHigh` | `#26303A` | élément pressé, sélection, menu | n/a |
| `border` | `#2E3945` | filets décoratifs (jamais seul porteur d'info) | 1,5 |
| `outline` | `#6B7A89` | contour de composant interactif | 4,0 |
| `text` | `#EAF0F5` | texte principal | 15,2 |
| `textMuted` | `#A3B1BF` | texte secondaire | 8,0 |
| `textFaint` | `#7F8D9B` | aide, unités (≥ 12 sp seulement) | 5,2 |
| `primary` | `#3DD6C3` | action principale, vitesse, focus | 9,7 |
| `onPrimary` | `#04201C` | texte sur `primary` | 9,5 |
| `depth` | `#6EA8FF` | canal profondeur | 7,3 |
| `sensation` | `#C79BFF` | canal sensation | 8,0 |
| `warn` | `#FFB84D` | limite imposée (Remote), prudence | 10,2 |
| `ok` | `#5BDB95` | connecté, à jour | 10,0 |
| `error` | `#FF8A8A` | texte d'erreur | 7,7 |
| `stop` | `#C93036` | fond du STOP | texte blanc : 5,3 |
| `onStop` | `#FFFFFF` | texte et icône du STOP | n/a |

Interdit : violet « néon » de l'ancienne version (`#B69AFF`), tout hex en dur dans un écran (utiliser les jetons).

## 3. Élévation par niveaux (pas d'ombres)

`bg` → `surface` (cartes) → `surfaceRaised` (contrôles) → `surfaceHigh` (pressé/sélectionné). Une carte = `surface` + filet `border` de 1 dp + coins 16 dp.

## 4. Typographie

Polices du système (aucun fichier à embarquer, rendu fiable hors ligne). Les valeurs numériques utilisent `FontFamily.Monospace` avec `fontFeatureSettings = "tnum"` pour que les chiffres ne « dansent » pas.

| Style | Taille / interligne | Graisse | Usage |
|---|---|---|---|
| `display` | 32 / 38 sp | SemiBold, mono | grande valeur (vitesse courante) |
| `title` | 20 / 26 sp | SemiBold | titre d'écran |
| `section` | 14 / 20 sp | SemiBold, MAJUSCULES, +0,8 sp | titre de carte |
| `body` | 16 / 24 sp | Normal | texte courant |
| `bodySmall` | 14 / 20 sp | Normal | secondaire |
| `label` | 13 / 18 sp | Medium | libellés de boutons et de sliders |
| `value` | 16 / 20 sp | Medium, mono | valeur d'un slider |
| `caption` | 12 / 16 sp | Normal | aide (minimum absolu : 12 sp) |

Respecter l'échelle de police du système : aucun texte ne doit être tronqué à 130 %.

## 5. Espacement, formes, zones tactiles

- Grille de **4 dp**. Échelle : 4, 8, 12, 16, 24, 32. Gouttière d'écran 16 dp.
- Coins : 8 dp (champs, puces), 16 dp (cartes), 24 dp (feuilles), cercle (boutons ronds).
- **Cible tactile ≥ 48 × 48 dp** pour tout élément interactif, **≥ 8 dp** entre deux cibles. Une icône de 24 dp se place dans une zone de 48 dp.
- Le **STOP** : hauteur ≥ 64 dp, pleine largeur de la barre, jamais plus petit.
- Curseur de slider : poignée 28 dp de diamètre dans une zone tactile de 48 dp ; piste 8 dp.

## 6. Composants

| Composant | Règle |
|---|---|
| **Barre STOP** | Fixe en bas de tous les écrans de contrôle, au-dessus de la navigation. Fond `stop`, icône + mot « STOP », contraste 5,3. Reste visible clavier ouvert. Jamais déplacé par le contenu. |
| **Carte** | `surface`, filet `border`, coins 16, marge interne 16. Titre en `section`. |
| **Slider de canal** | Nom du canal (couleur du canal) à gauche, valeur `value` à droite, piste 8 dp, poignée 28 dp. Les plafonds imposés par le Remote s'affichent en `warn` avec une icône cadenas et le mot « limité » (jamais la couleur seule). |
| **Bouton principal** | Fond `primary`, texte `onPrimary`, hauteur 48, coins 12. |
| **Bouton secondaire** | Fond `surfaceRaised`, contour `outline`, texte `text`. |
| **Bouton texte** | Texte `primary`, zone 48 dp. |
| **Puce / mode** | `surfaceRaised`, sélectionnée : fond `surfaceHigh` + contour `primary` + coche. |
| **Interrupteur / case** | Rangée entière cliquable (≥ 48 dp), libellé à gauche, contrôle à droite, aide en `caption` dessous. |
| **Indicateur de connexion** | Icône + mot (« USB », « Bluetooth », « Hors ligne »), couleur `ok` / `warn` / `textMuted`. |
| **Pad Live** | Zone de geste large, repère d'ancre visible, valeur affichée en `display`, fond `surfaceRaised`, aucun texte superposé au doigt. |
| **Message d'état** | Icône + texte, `caption` ou `bodySmall`, jamais seul en couleur. |

## 7. Mouvement

Courts et utiles : 120 à 200 ms, `FastOutSlowIn`. Un mouvement signifie un changement d'état (apparition d'une carte, sélection). Pas d'animation de décor. Respecter « Supprimer les animations » du système (durées à 0). Retour haptique léger sur les actions importantes uniquement (STOP, connexion), jamais en continu.

## 8. Accessibilité (obligatoire)

- Contraste texte ≥ 4,5:1 ; composants et icônes porteurs de sens ≥ 3:1.
- `contentDescription` sur toute icône cliquable ; pas de description sur les icônes décoratives.
- Rôles sémantiques (`Role.Switch`, `Role.Button`, `Role.Checkbox`), états lus (« activé », « limité à 40 »).
- Les sliders exposent `stateDescription` (valeur et unité) et restent réglables par TalkBack.
- Ordre de focus = ordre visuel ; le STOP est atteignable en premier ou en dernier, jamais enterré.
- Rien ne repose sur la couleur seule.

## 9. Règles de sécurité de l'interface

1. Aucun élément ne peut recouvrir ou pousser la barre STOP hors de l'écran.
2. Aucun écran ne peut désactiver visuellement le STOP.
3. Les gestes de contrôle continu (pad Live, sliders) ne peuvent pas être déclenchés par un simple balayage de navigation : les marges de bord réservent le geste « retour » du système.
4. Toute action qui change l'état de la machine reste identique en comportement à l'ancienne interface (contrat : inventaire).

## 10. Livraison côté code

- Jetons dans `ui/theme/` (`Color.kt`, `Type.kt`, `Shape.kt`, `Dimens.kt`) ; composants partagés dans `ui/components/`.
- Les écrans ne contiennent **aucun** hex, `sp` ou `dp` de style en dur hors de `Dimens`.
- Aucun changement de signature de callback ou de ViewModel : seul l'habillage et la mise en page changent.
- La variante « redesign » s'installe à côté de l'appli actuelle (identifiant d'application distinct, nom et icône distincts).
