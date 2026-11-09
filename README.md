# EterJob

La **guilde des métiers** du réseau (ancien EterMarket, sans boutiques ni hôtel des ventes : ce sera un autre plugin).
Un métier à la fois, mais un **niveau et un rang par métier**, gardés quand on en change ; des quêtes du jour, de la
semaine, rares, et l'**histoire** de chaque métier ; les **avantages du rang** dans le monde ressource (appliqués par
EterResource). Document développeur, à tenir à jour avec le code.

## Prérequis

- **EterLib 1.22.0+** (`depend`) : base, Redis et bus réseau, langues, menus, Dialogs, **actions**, sidebar, étiquettes.
- **EterEconomy** (`depend`, `EconomyApi`) : les récompenses (« EterJob · <métier> » dans `/ecostats`).
- Facultatifs : **EterNpc 2.0.0+** (marqueur « ! » au-dessus du donneur de quêtes, nettoyage des anciens PNJ),
  **EterClan** (pas de progrès sur le terrain d'un autre clan), **EterSuccess** (temps de jeu pour l'anti-triche).

## PNJ : des actions, plus de rôle

EterJob ne définit plus de PNJ : il **déclare des actions** dans EterLib, que le staff met sur des PNJ d'EterNpc
(`/eternpc create`, éditeur) :

| Action | Rôle |
|---|---|
| `eterjob:guild` | Le **chef de guilde** : choisir ou changer de métier, son niveau dans chacun, avantages, classement |
| `eterjob:quests <métier>` | Le **donneur de quêtes** du métier (un joueur d'un autre métier est envoyé au chef de guilde) |
| `eterjob:story <métier>` | Le **maître du métier** : l'étape d'histoire en cours (lore dans le chat), puis les quêtes |
| `eterjob:perks [métier]` | Les avantages par rang (ex : un PNJ du monde ressource) |
| `eterjob:menu` · `eterjob:ranking <métier>` | `/job` · le classement d'un métier |
| `?eterjob:has-job [métier]` · `?eterjob:rank <métier> <rang>` | Conditions |

Un « ! » apparaît au-dessus du donneur de quêtes de son métier quand une quête est prête (`quest-givers`, par défaut
`eternpc:<métier>`). Une action d'un autre serveur y envoie le joueur (actions réseau d'EterLib).

## Métiers, niveaux et rangs (`module/job`)

- **Un métier** (`eterjob_members`) : le premier gratuit, en changer coûte `change-cost` et pas avant `change-cooldown-days`.
  Le **niveau de chaque métier** est gardé (`eterjob_levels` : XP, quêtes accomplies, rang récompensé, histoire).
- **Niveaux** (`Levels`, testé) : XP du niveau n au suivant = `base-xp + growth-xp × (n − 1)`, 50 niveaux. Chaque niveau
  augmente un peu la récompense (`reward-bonus-per-level`, plafonné).
- **Rangs** (`Rank`) : Apprenti (1), Compagnon (10), Artisan (20), Expert (35), Maître (50). Le rang **agrandit les
  quêtes** (`scale` : quantités et récompense), débloque des quêtes (`min-rank`), le changement de quête gratuit
  (`reroll-free-rank`), une étape d'histoire, une **récompense** (une fois, base départage), et des **avantages** dans le
  monde ressource (`perks`, cumulés). Titre de rang : étiquette `<tag_job_rank>` (EterTab). Un nouveau Maître est annoncé
  sur tout le réseau. Événements `JobLevelUpEvent`, `JobChangeEvent`.

## Quêtes

**Répertoire** (`QuestCatalog`) : un fichier par métier, `quests/<métier>.yml` (copié s'il manque, relu par
`/eterjob reload`, rapport au chargement) : `daily`, `weekly`, `rare`, `story` (une étape par rang, avec titre et texte).
**Objectifs** (`Objective`, testé ; `ObjectiveTargets`) : `ITEM` (à livrer), et les actions `BREAK`, `HARVEST`, `KILL`,
`FISH`, `CRAFT`, `SMELT`, `BREED`, `EXPLORE` (biome), `ENCHANT`, `TRADE` ; cible : matière, créature, biome, `ANY`, ou
groupe `#ores #logs #crops #fish #stone #flowers` (un minerai et sa version des abîmes comptent pareil). 3 actions au plus.

**Tirage** (`JobService#ensureQuests`) : à minuit (`jobs.time-zone`, aussi pour les joueurs connectés : plus besoin de
repasser au PNJ), une quête par niveau de `daily`, **jamais une d'hier**, avec `rare-chance` qu'elle soit rare ; la bonus
quand elles sont faites ; la **quête de la semaine** (le lundi) ; l'**étape d'histoire** de son rang. Changer une quête :
une fois par jour (`reroll-cost`).

**Progression** (`JobProgress`, `JobProgressListener`) : comptée sur le thread principal, écrite par paquets (30 s,
départ, avant chaque validation) en **s'ajoutant** (deux serveurs ne s'écrasent pas). Barre de boss pendant une action,
quête suivie automatiquement, sidebar (quête suivie ou résumé de toutes), rappel « quête prête », messages coupables.
**Anti-triche** : créatures naturelles (trial chamber réglable) tuées par le joueur (pas de chute ni de piège), blocs non
posés, cultures mûres, pas sur le terrain d'un autre clan, poissons seulement (réglable), rien avant N minutes de jeu,
cadence anormale signalée au staff.

**Valider** (`JobService#deliver`) : au donneur de quêtes, ou à distance avec `/job` pour une quête sans objet à livrer ;
« tout valider ». Les objets (simples) sont pris, puis la base marque la quête faite **seulement si c'est toujours la même**
(mêmes objectifs : pas changée ailleurs), pas déjà faite, actions accomplies : un seul paiement. La **récompense est lue en
base**, avec les bonus : évènement, **métier le moins pratiqué** de la semaine, **série** (jours d'affilée), et le plafond
par jour. Après une validation réussie, plus rien n'est rendu (pas de duplication) ; une quête refusée rend les objets, et
ce qui ne tient pas va dans la **boîte de récupération** (`eterjob_box`, jamais au sol). Journal : `eterjob_log`.

## Menus (`module/menu`)

`/job` : ses quêtes (à distance). Donneur de quêtes (`QuestMenu`), chef de guilde (`GuildMenu`), avantages par rang
(`PerksMenu`), historique 7 jours (`HistoryMenu`), classement d'un métier (`RankingMenu`). `/job <joueur>` : son profil.

## Commandes et permissions

| Commande | Rôle | Permission |
|---|---|---|
| `/job` (`jobs`, `metier`) `[guilde \| avantages \| historique \| top <métier> \| messages \| <joueur>]` | Joueurs | `eterjob.use` (tous) |
| `/eterjob player · setjob · cooldown · clearquests · reroll · remove · setlevel · addxp · setstory` | Un joueur | `eterjob.admin` |
| `/eterjob quests <métier> · reload · stats [jours] · event <métier\|all> <×> <durée> \| stop` | Réglages, évènements | `eterjob.admin` |

## API (pour les autres plugins)

`fr.eternom.eterJob.api.JobApi` (`JobApi.get()`), `compileOnly("com.github.Eternom:EterJob:<tag>")` : `jobs()`,
`jobName`, `job(uuid)`, `level(uuid, métier)`, `rank(uuid, métier)`, `rankName`, `perks(uuid)` (**avantages du rang**,
pour EterResource), `topQuests`, `questRank`. Événements : `JobQuestCompleteEvent` (métier, difficulté, bonus, sorte),
`JobLevelUpEvent`, `JobChangeEvent`.

## Données

`eterjob_members`, `eterjob_levels`, `eterjob_quests` (emplacements 0-2 du jour, 3 bonus, 4 semaine, 5 histoire),
`eterjob_box`, `eterjob_log` (60 jours), `eterjob_events`.

**2.0.0** a supprimé une fois les tables `etermarket_*` et les emplacements des PNJ `etermarket:*` (sans reprise) ;
ce nettoyage est retiré depuis la 2.0.1.
