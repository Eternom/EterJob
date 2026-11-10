# EterJob

La **guilde des métiers** du réseau (ancien EterMarket, sans boutiques ni hôtel des ventes : ce sera un autre plugin).
Un métier à la fois, mais un **niveau et un rang par métier**, gardés quand on en change ; des quêtes du jour, de la
semaine, rares, et l'**histoire** de chaque métier ; les **avantages du rang** dans le monde ressource (appliqués par
EterResource). Document développeur, à tenir à jour avec le code.

## Prérequis

- **EterLib 1.25.0+** (`depend`) : base, Redis et bus réseau, langues, menus, Dialogs, **actions**, étiquettes.
- **EterQuest 1.0.0+** (`depend`) : le **moteur des quêtes**. Les quêtes de métier sont des quêtes d'EterQuest
  (propriétaire `eterjob`) : EterJob les écrit, les tire, les fait rendre et les paie ; EterQuest compte les objectifs
  (anti-triche compris), gère les étapes, la barre de boss, la sidebar, les rappels et la boîte de récupération.
- **EterEconomy** (`depend`, `EconomyApi`) : les récompenses (« EterJob · <métier> » dans `/ecostats`).
- Facultatif : **EterNpc 2.0.0+** (marqueur « ! » au-dessus du donneur de quêtes).

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

**Répertoire** (`QuestCatalog`) : un fichier par métier, `quests/<métier>.yml` (copié s'il manque ; un fichier
d'EterJob 2.x est mis de côté en `.old`), **au format d'EterQuest**, envoyé à tout le réseau (augmenter `version`
après un changement), relu par `/eterjob reload`. Ce qu'EterJob lit de chaque quête (`meta`) : `kind` (`daily`,
`weekly`, `rare`, `story`), `tier`, `reward`, `xp`, `min-rank`, `rank` (l'étape d'histoire de ce rang). Une quête peut
avoir des **étapes**, des objectifs **bonus**, des conditions de lieu et de moment (`where` : la nuit, sous l'orage,
sous Y 0, dans un biome, dans le Nether), un **temps limité**, des **conditions** (`requires`, ex : réputation auprès
d'un PNJ). 248 quêtes au départ (environ 50 par métier).

**Quêtes du village** (fermier, pêcheur) : une quête de métier peut se faire au lobby, chez un PNJ du village
(Mathurin le Fermier `eternpc:fermier`, Aurèle le Vigneron `eternpc:vigneron`, Gaspard le Poissonnier
`eternpc:poissonnier`) : lui parler, récolter dans les Champs ou pêcher au Lac (signaux d'EterHub), puis lui rendre la
quête. Les récoltes faites pour une quête vont au panier de la commande : ni objet, ni plafond du jour des Champs.
Elles demandent de la réputation auprès du PNJ (réputation d'EterLib × ratio du PNJ, `/eternpc reputation`).

**Tirage** (`JobService#ensureQuests`) : à minuit (`jobs.time-zone`), une quête par niveau de `daily` (emplacements
`daily-0` à `daily-2` d'EterQuest), **jamais une d'hier**, avec `rare-chance` qu'elle soit rare ; la `bonus` quand
elles sont faites ; la `weekly` (le lundi) ; la `story` de son rang. Seules les quêtes dont les conditions sont
remplies sont tirées. La récompense est fixée au tirage (taille du rang, bonus du niveau, bonus, rare). Changer une
quête : une fois par jour (`reroll-cost`).

**Rendre et payer** : au donneur de quêtes (`giver`), à distance avec `/job` (`remote`, sans objet à remettre), ou
chez un PNJ du village (`npc:<pnj>`). EterQuest valide une seule fois (même sur deux serveurs) et lance
`QuestCompleteEvent` : EterJob réserve le paiement (`claim`) puis paie (bonus : évènement, **métier le moins
pratiqué**, **série** ; plafond par jour), l'XP de métier, les rangs atteints. Une quête finie sur un serveur sans
EterJob est payée à l'arrivée suivante. Chaque quête rapporte aussi de la **réputation** (EterLib).

## Menus (`module/menu`)

`/job` : ses quêtes (à distance ; le détail de chaque quête et de ses étapes vient d'EterQuest). Le journal de toutes les quêtes : `/quests` (EterQuest). Donneur de quêtes (`QuestMenu`), chef de guilde (`GuildMenu`), avantages par rang
(`PerksMenu`), historique 7 jours (`HistoryMenu`), classement d'un métier (`RankingMenu`). `/job <joueur>` : son profil.

## Commandes et permissions

| Commande | Rôle | Permission |
|---|---|---|
| `/job` (`jobs`) `[guild \| perks \| history \| top <métier> \| messages \| <joueur>]` | Joueurs | `eterjob.use` (tous) |
| `/eterjob player · setjob · cooldown · clearquests · reroll · remove · setlevel · addxp · setstory` | Un joueur | `eterjob.admin` |
| `/eterjob quests <métier> · reload · stats [jours] · event <métier\|all> <×> <durée> \| stop` | Réglages, évènements | `eterjob.admin` |

## API (pour les autres plugins)

`fr.eternom.eterJob.api.JobApi` (`JobApi.get()`), `compileOnly("com.github.Eternom:EterJob:<tag>")` : `jobs()`,
`jobName`, `job(uuid)`, `level(uuid, métier)`, `rank(uuid, métier)`, `rankName`, `perks(uuid)` (**avantages du rang**,
pour EterResource), `topQuests`, `questRank`. Événements : `JobQuestCompleteEvent` (métier, difficulté, bonus, sorte),
`JobLevelUpEvent`, `JobChangeEvent`.

## Données

`eterjob_members`, `eterjob_levels`, `eterjob_log` (60 jours), `eterjob_events`. Les quêtes en cours sont dans les tables
d'EterQuest (propriétaire `eterjob`). **3.0.0** : `eterjob_quests` n'est plus utilisée (les joueurs reçoivent de
nouvelles quêtes) ; les colis de `eterjob_box` passent une fois dans la boîte d'EterQuest.

**2.0.0** a supprimé une fois les tables `etermarket_*` et les emplacements des PNJ `etermarket:*` (sans reprise) ;
ce nettoyage est retiré depuis la 2.0.1.
