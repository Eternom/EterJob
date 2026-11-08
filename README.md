# EterMarket

Le marché du réseau : **PNJ boutiques**, **stock commun**, **guilde des métiers** (quêtes quotidiennes de livraison et d'action,
le vrai revenu du serveur) et **hôtel des ventes** (entre joueurs, sur un PNJ).
Conception complète : voir la mémoire de projet et les « Repères économiques » d'EterEconomy.
Document développeur, à tenir à jour avec le code.

## Prérequis

- **EterLib 1.15.0+** (`depend`) : base, Redis et bus réseau, langues et textes communs, menus (cadre, Dialogs), économie (`Money`), sidebar temporaire, étiquettes de la sidebar.
- **EterNpc 1.1.0+** (`depend`) : les PNJ (Mannequins, emplacements, clics).
- **EterTab** (facultatif) : affiche la quête suivie et le métier (`<tag_job>`) dans la sidebar.
- **EterEconomy 2.2.2+** (son API `EconomyApi`) pour payer (sinon : « économie indisponible »).
- Client 1.21.6+ pour les Dialogs (quantité, éditeur).

## PNJ (`module/npc`)

**Définis une fois, placés partout.** `etermarket_npcs` : la définition (identifiant, nom MiniMessage, skin, rôle,
vente sur stock), commune à tout le réseau. **L'apparition, le regard et les emplacements sont à EterNpc** (`NpcApi`) :
`NpcService` donne à EterNpc l'apparence de chaque PNJ (`etermarket:<pnj>`, rôle ou métier sous le nom) à chaque
chargement et répond à ses clics (`NpcListener` sur `NpcClickEvent`). `/market place` et `/market remove` passent par
EterNpc ; un emplacement posé sur un lobby vaut pour tous les lobbys (voir EterNpc).

**Reprise** (1.8.0) : les anciens emplacements de `etermarket_placements` passent à EterNpc au premier démarrage, chacun
une seule fois (seul le serveur qui a pu supprimer la ligne la copie), avec le même serveur ou `@lobbies`.

**Synchronisation** : une définition modifiée sur un serveur (éditeur, création, suppression) est rechargée par les
autres via le bus réseau d'EterLib (canal `etermarket`, message `reload`, ignoré par l'émetteur). Redis en panne :
`/market reload` sur chaque serveur.

## Boutiques (`module/shop`)

Chaque PNJ vend **sa propre liste** (`etermarket_shop_items` : l'objet exact sérialisé, avec la quantité d'un lot, et
le prix du lot). **Les boutiques ne rachètent rien** : l'argent entre par les quêtes des métiers.

**Achat** (`ShopService`) : place dans l'inventaire vérifiée sur une copie (sinon refus) → stock commun retiré si la
boutique vend sur stock (atomique) → paiement par EterEconomy (atomique) ; paiement refusé = stock rendu. Les objets ne sont
donnés qu'à la fin. Clic gauche : un lot ; clic droit : quantité choisie dans un Dialog (1 à 64 lots).

**Éditeur** (`ShopEditorMenu`, cadre rouge ; `/market edit <pnj>` ou Maj + clic droit, `etermarket.edit`) : ajouter
l'objet tenu (la quantité tenue = un lot) avec son prix (Dialog), changer un prix, retirer (confirmation), vente sur
stock oui/non, nom et skin du PNJ.

## Guilde des métiers (`module/job`)

**Un métier par joueur** (`etermarket_job_members`) parmi `jobs.list` (Mineur, Bûcheron, Fermier, Chasseur, Pêcheur).
Le premier est gratuit ; en changer coûte `change-cost` (2 000) et n'est possible qu'une fois par
`change-cooldown-days` (7). On le choisit au PNJ de référence du métier (`/market create <pnj> job <métier>`).
La table compte aussi les quêtes accomplies de chaque joueur (base de futurs niveaux).

**Répertoire** (`etermarket_job_templates`) : les quêtes possibles de chaque métier, avec un **niveau** (`Tier` :
facile, normale, difficile), une récompense et des **objectifs** (`Objective`, en base `KIND:CIBLE:QUANTITÉ;...`) :
- `ITEM` : objets à livrer, un ou plusieurs (« commande » : 8 fer + 4 or + 16 charbon) ;
- `KILL`, `BREAK`, `FISH` : tuer, casser, pêcher, **jusqu'à 3 actions par quête** (`MAX_ACTIONS`, un compteur chacune :
  colonnes `progress`, `progress_2`, `progress_3`), avec des objets (« tue 10 zombies, 10 squelettes, 10 araignées »,
  « casse 16 minerais de fer et rapporte 16 lingots »). Cible `ANY` : n'importe quel monstre / prise. Une même créature
  compte pour chaque action qui lui correspond (« tuer des zombies » et « tuer des monstres »). Un **minerai et sa version des
  abîmes** comptent pareil pour `BREAK` (1.9.2 : `REDSTONE_ORE` accepte aussi `DEEPSLATE_REDSTONE_ORE`, et inversement).

**Quêtes du jour** (`etermarket_job_daily`) : à minuit (`jobs.time-zone`), une quête par niveau de `daily`
(facile, normale, difficile ; un niveau vide prend dans les autres), recopiées depuis le répertoire (le modifier ne
change pas les quêtes tirées), puis une **quête bonus** de niveau `bonus-tier` (récompense × `bonus-multiplier`) quand
elles sont faites. **Changer une quête** pas encore faite contre une autre du même niveau : une fois par jour
(`reroll_day`, réservé en SQL avant de payer), pour `reroll-cost` (150) : une sortie d'argent.

**Actions** (`JobProgress`, `JobProgressListener`) : comptées sur le thread principal pour les joueurs connectés
(quêtes chargées à la connexion), écrites en base par paquets toutes les 30 s, à la déconnexion et avant chaque
validation, en **ajoutant**, compteur par compteur (`progress_N = LEAST(cible, progress_N + n)`) : deux serveurs ne s'écrasent pas. La clé d'un
progrès inclut les objectifs : celui d'une quête changée entre-temps ne compte pas pour la nouvelle. Contre la triche :
créatures apparues naturellement seulement (pas de spawner, d'œuf, d'élevage, de commande) et tuées par le joueur ;
blocs posés par un joueur ignorés (retenus en mémoire sur le serveur, 200 000 au plus) ; cultures seulement mûres ;
rien en créatif. Action bar à chaque progrès, message quand l'objectif est atteint.

**Quête suivie** : clic droit sur une quête → elle s'affiche dans la **sidebar** (objectifs et progression en direct,
« retourne voir ton PNJ » quand tout est prêt). EterMarket ne touche pas au tableau de scores : il dépose le contenu
dans EterLib (`getSidebars()`), qu'EterTab dessine à la place de sa sidebar. Sans EterTab, pas de sidebar.

**Sidebar** : sous l'argent, le métier et les quêtes encore dispo aujourd'hui (« Métier Mineur (2 à faire) », clés
`job.sidebar` et `job.sidebar-none`, langue du joueur), posés dans EterLib (`getPlayerTags()`, étiquette `job`) :
EterTab-Paper l'affiche à la place de `<tag_job>`. Mis à jour au chargement des quêtes, à chaque validation et chaque minute (nouveau
jour).

**Valider** (`JobService#deliver`, clic gauche) : action accomplie et objets présents (simples, sans nom ni
enchantement) → objets retirés → la base marque la quête faite **une seule fois** (`UPDATE ... WHERE done = FALSE AND
progress >= cible`) → objets versés dans le **stock commun** → récompense par EterEconomy. Refus (double clic, autre
serveur) : objets rendus.

**Le PNJ de métier** ouvre la guilde (rejoindre) ou les quêtes du jour ; un onglet mène à sa **boutique** (outils et objets utiles
au métier, `Jobs#shop`, posée à la création du PNJ ; même éditeur que les boutiques). **Répertoire de départ** (`Jobs#defaults`, 17 quêtes par métier, chacune avec plusieurs
choses à faire) posé **une seule fois par métier** sur tout le réseau : le serveur qui inscrit le métier dans
`etermarket_job_seeded` (`INSERT IGNORE`) est le seul à le poser, même si plusieurs démarrent ensemble ; un métier vidé
exprès le reste. **Les quêtes se règlent dans le code** (`Jobs#defaults`), pas en jeu : `/market jobs reset <métier> confirm`
remet les quêtes ET la boutique de départ des PNJ du métier. Repères : facile 110-140, normale 170-210,
difficile 270-320 ; une journée complète ≈ 850.

**Contrôle d'arbitrage** : l'éditeur de boutique signale un objet vendu moins cher à l'unité que sa part de récompense (la
récompense partagée entre les objets demandés). Repères : « Repères économiques » d'EterEconomy.

## Hôtel des ventes (`module/auction`)

Sur un PNJ (`/market create <pnj> auction`), **entre joueurs**, commun à tout le réseau, **sans lien avec le stock
commun**. Tables : `etermarket_auction_listings` (annonces en cours) et `etermarket_auction_collection` (boîte de
récupération). Chaque sortie d'une annonce passe par un `DELETE ... WHERE id = ?` : un seul gagnant.

- **Vendre** l'objet en main (prix dans un Dialog) : l'objet quitte la main tout de suite → limite d'annonces
  (`etermarket.auction.listings.<n>`, sinon `default-listings`) → frais de mise en vente (`listing-fee`, 1 Helok
  minimum) → annonce écrite. Au moindre refus, l'objet est rendu. Prix entre `min-price` et `max-price`.
  **Acheter** demande `auction.min-buy-playtime-hours` (5) heures de jeu actif (`BuyGate`, source `playtime_total` d'EterSuccess
  1.5.1+, lue par EterLib : EterSuccess dépend déjà d'EterMarket) : un second compte ne passe pas son argent au principal en achetant très cher un objet sans valeur. Le menu
  l'affiche (tête du joueur et chaque annonce : « Achats après 5 h de jeu : encore … »).
- **Acheter** (confirmation) : l'acheteur paie → l'annonce est réservée ; perdue = acheteur remboursé. Le vendeur
  reçoit le prix moins `tax`, même hors ligne, et est prévenu où qu'il soit (`notify` du bus réseau, canal `etermarket`).
- **Retirer** une annonce depuis « Mes ventes » ; les **expirées** (`duration-hours`) passent dans la boîte de leur
  vendeur (tâche chaque minute, sur chaque serveur, sans doublon grâce au `DELETE`).
- **Boîte de récupération** : invendus, annonces retirées, achats sans place. Un colis ne se prend que s'il rentre.
  **Joueur parti pendant l'opération** (EterLib `Tasks` avec `offline`) : l'objet acheté, retiré, refusé à la vente ou
  sorti de la boîte, comme les objets d'une quête refusée (déjà validée ailleurs), y est remis (raison `returned`) au
  lieu d'être perdu ; un achat en boutique est remboursé (et le
  stock rendu).
- **Recherche** par nom d'objet ou de matière ; clic droit sur la loupe pour l'effacer.

## Stock commun (`module/stock`)

**Un seul stock pour tout** (toutes les boutiques, tous les serveurs), par matière (`etermarket_stock`). Les livraisons
des quêtes l'alimentent toujours ; une boutique n'y puise que si elle est réglée « vente sur stock » (sinon :
illimitée). Seuls les objets simples (sans nom ni enchantement) passent par le stock. L'hôtel des ventes n'y touchera
jamais (il est entre joueurs).

## Commandes et permissions

| Commande | Rôle |
|---|---|
| `/market create <pnj>` | Crée une boutique (une fois pour tout le réseau) |
| `/market create <pnj> job <métier>` | Crée le PNJ de référence d'un métier |
| `/market create <pnj> auction` | Crée un PNJ hôtel des ventes |
| `/market place <pnj>` | Le place là où tu es |
| `/market remove` | Retire l'emplacement le plus proche (5 blocs) |
| `/market edit <pnj>` | Éditeur de la boutique |
| `/market delete <pnj> confirm` | Supprime le PNJ partout, avec ses emplacements et sa boutique |
| `/market list` · `/market reload` | Liste · rechargement |
| `/market jobs reset <métier> confirm` | Remet le répertoire de quêtes de départ du métier (efface les modifications) |

`/market` : `etermarket.admin` (op). Éditeur au clic : `etermarket.edit` (dans `etermarket.admin`).
Pas de `/shop` : les joueurs vont voir les PNJ.

## API (pour les autres plugins)

`fr.eternom.eterMarket.api.MarketApi` (`MarketApi.get()`) : personne d'autre ne lit les tables `etermarket_*`.

- `jobs()`, `jobName(lecteur, métier)`, `job(uuid)` : le métier d'un joueur (bloquant) — utilisé par EterResource pour
  les bonus de métier ;
- `stock(matière)`, `addStock(matière, n)`, `takeStock(matière, n)` : le stock commun (bloquant), pour les futurs
  apports (événements, récompenses...).
- `topQuests(n)`, `questRank(uuid)` : classement des quêtes validées (bloquants), pour `/classement` d'EterReward ;
- événement `JobQuestCompleteEvent` (joueur, métier, difficulté `easy`/`normal`/`hard`, quête bonus) : lancé une fois
  par quête validée, après l'enregistrement et le paiement — l'XP du pass d'EterReward l'écoute.
- événement `AuctionSaleEvent` (acheteur, vendeur, objet, prix) : une annonce achetée, une seule fois (succès d'EterSuccess) ;

L'argent passe par l'API d'EterEconomy, avec sa source : « EterMarket · boutique », « · enchères », « · métiers ».
