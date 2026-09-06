# Plan de correction — Ingestion & planification des rappels entrants

## 1. Problème corrigé

Aujourd'hui, un rappel qu'un collaborateur t'envoie (`shared_reminders` où `receiverId == moi`)
est **fusionné uniquement dans le Flow d'affichage** de l'écran d'accueil
(`ReminderRepositoryImpl.observePersonalActive`). Il n'est **jamais** :

- écrit dans Room (le store canonique qui pilote les alarmes/geofences), ni
- programmé dans `AlarmManager` / `Geofencing`.

Conséquence : **le rappel s'affiche mais ne se déclenche jamais** sur le téléphone du
destinataire. De plus le listener temps réel ne vit que pendant que l'écran d'accueil est
affiché (`WhileSubscribed(5000)`) et `SyncManager` (squelette) ne descend jamais les
`shared_reminders` → aucune livraison en arrière-plan.

Preuve dans le code : les deux receivers résolvent le rappel par son **id Room local**
(`reminderRepository.getById(id)` — `AlarmReceiver.kt:35`, `GeofenceBroadcastReceiver.kt:41`).
Tant qu'un rappel entrant n'a pas de ligne Room avec un id local, il ne peut pas se déclencher.

## 2. Principe de la solution

**Le Cloud (Firestore) est un transport, pas le magasin d'exécution. Room reste la source
de vérité qui pilote les alarmes/geofences.**

On introduit un pipeline d'**ingestion** : `shared_reminders (receiverId==moi)` → **upsert dans
Room** (clé = `remoteId` = docId Firestore) → **(re)planification** alarme/geofence sur l'id
Room local → **purge** locale des rappels supprimés côté Cloud.

L'ingestion tourne à deux niveaux :

1. **Temps réel (app active)** : un collecteur à portée application écoute
   `observeIncomingSharedReminders(uid)` et ingère chaque snapshot. Mises à jour instantanées.
2. **Arrière-plan (app fermée)** : un `WorkManager` périodique (contrainte réseau) appelle
   `syncOnce()` — c'est le vrai filet de livraison sans push (plan Spark, pas de Cloud Functions).

Après ingestion, l'affichage de « Mon espace » observe **Room uniquement** (plus de fusion
live) : une seule source de vérité, les rappels entrants et personnels cohabitent dans la table
`reminders` (`listId IS NULL`), et l'UI se met à jour réactivement quand Room change.

## 3. Modèle de données

`ReminderEntity` possède déjà les champs nécessaires : `remoteId`, `authorId`, `authorName`,
`syncState`, `listId`. Aucune migration destructive n'est requise (`fallbackToDestructiveMigration`
est déjà actif).

Correspondance clé :

| Concept                    | Champ                                            |
|----------------------------|--------------------------------------------------|
| id d'exécution (alarmes)   | `ReminderEntity.id` (autogénéré, local)          |
| id Firestore (docId)       | `ReminderEntity.remoteId` = doc.id               |
| expéditeur                 | `authorId` (= `senderId`), `authorName`          |

On ajoute `remoteId: String? = null` au **modèle de domaine** `Reminder` pour que le repository
sache quel document Firestore mettre à jour/supprimer quand le destinataire complète/supprime un
rappel entrant.

## 4. Changements fichier par fichier

### Nouveaux fichiers
- `sync/SharedReminderSyncManager.kt` — cœur de l'ingestion :
  - `start()` : collecteur temps réel à portée application (démarré par `RemindlyApp`).
  - `suspend fun syncOnce()` : fetch ponctuel `.get()` + ingestion (appelé par le worker).
  - `ingest(remote)` (sous `Mutex`) : upsert par `remoteId`, planification, purge.
- `sync/SharedReminderSyncWorker.kt` — `@HiltWorker` périodique appelant `syncManager.syncOnce()`.

### Fichiers modifiés
- `domain/model/Reminder.kt` : + `remoteId: String? = null`.
- `data/db/dao/ReminderDao.kt` : + `getByRemoteId(remoteId)`, + `getAllIncoming()` (remoteId non nul).
- `data/remote/FirestoreDataSource.kt` :
  - `saveSharedReminder` : ajoute `placeRadiusM` et `placeLabel` (perte de données corrigée).
  - `observeIncomingSharedReminders` : renseigne `remoteId = doc.id`, `id = 0`, lit
    `placeRadiusM`/`placeLabel`.
  - + `getIncomingSharedRemindersOnce(uid)` : version `.get()` pour le worker.
  - `observeSharedReminders` (vue collaborateur/sortante) : renseigne aussi `remoteId` + place
    (comportement d'id inchangé pour ne pas casser la vue sortante — hors périmètre).
- `data/repo/ReminderRepositoryImpl.kt` :
  - `observePersonalActive` (branche perso) → **Room seul** (suppression de la fusion live).
  - `toDomain` mappe `remoteId`.
  - `setStatus` / `delete` (branche perso) → ciblent Firestore via **`entity.remoteId`**
    (et non plus `id.toString()`, qui visait le mauvais document après ingestion).
- `ui/capture/CaptureViewModel.kt` : n'ajoute des pièces jointes que si `reminderId > 0`
  (évite les pièces jointes orphelines créées quand on envoie à un collaborateur, où `save()` renvoie 0).
- `RemindlyApp.kt` : injecte `SharedReminderSyncManager`, appelle `start()` dans `onCreate`,
  et planifie le `WorkManager` périodique (unique, `KEEP`, contrainte réseau).

## 5. Règles de planification (à l'ingestion)

Pour chaque rappel entrant upserté :

- **status != ACTIVE** → `alarmScheduler.cancel(id)` + `geofenceManager.removeGeofence(id)`.
- **TIME / BOTH** et `triggerTimeMillis` dans le **futur** → `alarmScheduler.schedule(reminder, t)`.
  (Un temps déjà passé n'est **pas** reprogrammé — voir limitations.)
- **PLACE / BOTH** et coordonnées présentes → `geofenceManager.addGeofence(reminder)`.

Idempotence : `schedule` (FLAG_UPDATE_CURRENT) et `addGeofence` (requestId = id) écrasent
proprement ; l'upsert par `remoteId` est idempotent. Faire tourner le collecteur temps réel
**et** le worker en même temps est donc sûr.

## 6. Purge (suppression côté Cloud)

Chaque snapshot (temps réel) et chaque `.get()` (worker) fournit **l'ensemble courant** des
`shared_reminders` du destinataire. Purge = pour chaque ligne Room entrante (`remoteId != null`)
absente de l'ensemble distant : `cancel` alarme + `removeGeofence` + suppression de la ligne Room.

## 7. Livraison (contrainte plan Spark)

- **App active** : collecteur temps réel → latence quasi nulle.
- **App fermée/arrière-plan** : `WorkManager` périodique (~15 min, min. imposé, contrainte
  réseau) → `syncOnce()`. C'est le seul filet possible sans FCM/Cloud Functions (Blaze).
- **Au lancement** : `RemindlyApp.onCreate` → `start()` rattache le listener et ingère
  immédiatement dès que l'utilisateur est connecté.

## 8. Effet de bord positif

Quand l'alarme/geofence d'un rappel entrant se déclenche, les receivers appellent
`setStatus(id, COMPLETED)`. Comme `setStatus` propage désormais vers Firestore via `remoteId`,
**l'expéditeur voit le rappel passer à “complété”**. Cohérent avec le modèle « contribution ».

## 9. Limitations connues (hors périmètre de ce correctif)

- **Rappels TIME déjà échus à l'ingestion** : affichés dans la liste mais non renotifiés
  (évite le spam). Une notification « manqué » pourra être ajoutée plus tard.
- **Vue collaborateur (sortante)** : conserve sa logique d'id actuelle ; on n'y touche pas.
- **docId = timestamp millis** : conservé pour préserver le round-trip de la vue sortante ;
  risque de collision négligeable pour un usage mono-utilisateur. `remoteId` rend l'ingestion
  robuste indépendamment de ce point.
- **Pièces jointes** : non synchronisées (plan Spark = texte + trigger). Reste local au perso.

## 10. Vérification (checklist manuelle)

1. Compte A bascule sur l'espace de B, crée un rappel TIME (+2 min) → doc dans `shared_reminders`
   avec `placeRadiusM`/`placeLabel` si PLACE.
2. Compte B (app ouverte) : le rappel apparaît immédiatement dans « Mon espace » et une ligne
   Room existe (`remoteId` renseigné).
3. Compte B ferme l'app → l'alarme se déclenche à l'heure (worker/alarme déjà programmée).
4. Rappel PLACE : géofence enregistrée sur l'id local, notification à l'entrée dans la zone.
5. B complète le rappel → statut COMPLETED propagé à Firestore, alarme annulée ; A le voit complété.
6. A supprime le rappel → purge chez B : ligne Room supprimée, alarme/geofence annulées.
7. Redémarrage du téléphone : `RescheduleAlarmsWorker`/`ReRegisterGeofencesWorker` re-planifient
   aussi les rappels entrants (ils sont maintenant dans Room).
