# DemonPact — Paper 26.3

Pactes avec 12 démons, autel vivant (Demon Stell), contrats aléatoires en interface graphique, 36 pouvoirs fixes (sans évolution).

## 1. Compilation
Prérequis : JDK 25, Maven.
```
mvn clean package
```
Le jar est généré dans `target/DemonPact.jar`.
Avec GitHub : poussez le dossier, le workflow `.github/workflows/build.yml` compile et publie le jar en artefact.
Si la version de l'API change, ajustez `api-version` dans `plugin.yml` et la version de `paper-api` dans `pom.xml`.

## 2. Installation
1. Copiez `DemonPact.jar` dans `plugins/`, démarrez le serveur : `config.yml`, `dialogues.yml` sont créés.
2. Placez-vous sur l'emplacement voulu au spawn et tapez **`/demon setstell`** : la plateforme, les runes (obsidienne), les piliers et lanternes d'âmes sont construits. Attention : la zone (rayon 8, hauteur 8) est dégagée. Désactivable avec `stell.build-on-set: false`.
3. Un modérateur tape **`/demon stell`** : animation d'invocation, apparition d'un démon, contrat proposé à un joueur proche (`stell.target-radius`).

## 3. Commandes
| Commande | Permission | Rôle |
|---|---|---|
| `/demon`, `help`, `info` | demon.info | Menu d'information |
| `/demon pact` / `power` | demon.use | Pacte et pouvoir actuels (cooldown) |
| `/demon contract` | demon.use | Rouvre le contrat en attente |
| `/demon abandon [n] confirm` | demon.use | Rompt un pacte (sacrifices non rendus) |
| `/demon item` | demon.use | Réémet l'objet de pacte ; l'ancien devient inerte |
| `/demon stell [joueur]` | demon.moderator | Invoque un démon aléatoire |
| `/demon summon <démon> [joueur]` | demon.moderator | Invoque un démon précis |
| `/demon despawn` | demon.moderator | Renvoie le démon |
| `/demon setstell` | demon.admin | Définit et construit l'autel |
| `/demon reload` | demon.admin | Recharge config.yml et dialogues.yml |
| `/demon give <joueur> <démon> [contrat]` | demon.admin | Accorde un pacte sans sacrifice |
| `/demon remove <joueur> [pouvoir]` | demon.admin | Retire des pactes |
| `/demon restore <joueur>` | demon.admin | Rend la vie maximale sacrifiée |

Permissions : `demon.admin` (inclut moderator, use, info), `demon.moderator`, `demon.use` (défaut : tous), `demon.info` (défaut : tous).

## 4. Fonctionnement
- **Contrat** : tiré au hasard parmi ceux du démon (jamais deux fois de suite le même si possible). 5 minutes (`negotiation.time-seconds`) pour accepter (émeraude) ou refuser (barrière). Refus / silence : `DESPAWN` ou `NEXT_PLAYER` (`negotiation.on-refuse`, `on-timeout`).
- **Sacrifices** : cœurs permanents (modificateur d'attribut persistant, minimum `sacrifice.min-max-health`), têtes de joueurs authentiques (joueur réel connu du serveur ; les têtes à texture libre sont refusées), ressources/objets non renommés. Tout est vérifié avant, retiré uniquement à la signature.
- **Objets** : identifiant démon + UUID propriétaire + numéro de série dans le PDC (persistant). Seul le propriétaire avec le numéro de série en vigueur active le pouvoir ; non craftables, non empilables, bloqués dans établis/enclumes/fours, conservés à la mort. `/demon item` change le numéro de série : toute copie devient inerte.
- **Pouvoirs** : clic droit, cooldown, anti-spam (`powers-settings.anti-spam-ms`), zones désactivées (`disabled-zones` : mondes, cuboïdes `monde:x1,y1,z1:x2,y2,z2`, régions WorldGuard par réflexion, sans dépendance). Valeurs fixes : aucune évolution.
- **Têtes** : un joueur tué par un autre laisse sa tête (`heads.drop-on-pvp-kill`).
- **Stockage** : `players.yml` (UUID), `walls.yml` (murs temporaires, nettoyés au redémarrage).

## 5. Les 12 démons
AERON (vent), IGNIS (feu), VOLTAR (foudre), CHRONOS (temps), UMBRA (ombres), SANGUIS (sang), TERRAX (terre), AQUARIS (abysses), NOX (mort), PHANTOM (illusion), GRAVITAS (gravité), VOID (néant). Chacun a 3 contrats, un objet, une animation d'invocation propre à son élément et 17 répliques uniques (`dialogues.yml`).

## 6. Limites connues
- Le code n'a pas pu être compilé contre l'API Paper 26.3 dans l'environnement de rédaction (pas d'accès réseau) : seule la syntaxe a été validée. Au premier `mvn package`, corrigez d'éventuels noms d'API renommés.
- Les animations de sol de l'autel et les prisons d'eau sont envoyées au client uniquement (aucun bloc réel modifié).
