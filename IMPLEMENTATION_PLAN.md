# Plan d'implémentation — Application Android « Reminder » (temps + lieu + collaboration gratuite)

> **Statut** : version à jour. Décisions intégrées : (1) **déclencheur CATÉGORIE GPS supprimé** (batterie) ; (2) **collaboration temps réel via Firebase** ; (3) **mode « contribution seule »** — un invité **ne voit pas** les rappels du propriétaire, il ne fait qu'en **ajouter** ; (4) **100 % plan gratuit Firebase (Spark)** — pas de Cloud Storage, pas de Cloud Functions, pas de push serveur.
>
> **Portée** : plan uniquement, pas de code d'application.

---

## 1. Contexte & périmètre

Application Android neuve. Objectif : rappels/todo où **créer un rappel est le plus simple possible** (style Google Keep), UI **moderne** (Material 3 / Material You).

Un rappel possède :
- **Contenu** : texte **et/ou** note vocale **et/ou** image.
- **Déclencheur**, de l'un de ces types :
  1. **AUCUN** — simple todo.
  2. **TEMPS** — date/heure (répétition optionnelle).
  3. **LIEU précis** — un point posé sur la carte (lat/lng + rayon).
- Appartient à un **espace personnel** (privé, local) **ou** à une **liste partagée** (collaboration).

Fonctions transverses :
- **Lieux enregistrés** réutilisables.
- **Collaboration — mode « contribution seule »** : un invité peut **ajouter** des rappels à une liste partagée ; il **ne voit pas** les rappels du propriétaire et ne peut ni les modifier ni les supprimer. Le propriétaire voit tout et garde le contrôle total.

**Exclu du périmètre gratuit** : Cloud Storage et Cloud Functions (payants) → pas de médias sur les rappels partagés, pas de push serveur.

---

## 2. Vue d'ensemble de l'architecture

Deux sources de données, un rôle clair pour chacune :

```
┌─────────────────────────── Appareil (Android) ───────────────────────────┐
│   UI (Compose / Material 3, MVVM)                                         │
│        │                                                                  │
│   Repositories                                                            │
│        │                                                                  │
│   Room  ◀── SOURCE LOCALE CANONIQUE : tout rappel (perso + partagé) est   │
│   (SQLite)   projeté ici. Les DÉCLENCHEURS (alarmes, geofences) lisent    │
│        ▲     TOUJOURS Room → fonctionne hors-ligne, réveille l'appareil.  │
│        │                                                                  │
│   SyncManager  ◀── synchronise Room ⇄ Firestore pour les listes partagées │
│        │                                                                  │
└────────┼──────────────────────────────────────────────────────────────────┘
         │  (Internet)
┌────────▼──────────────────────── Firebase (GRATUIT / Spark) ──────────────┐
│  Auth (Google Sign-In)   ·   Cloud Firestore (listes / membres / rappels  │
│  texte + déclencheur, temps réel)   ·   Règles de sécurité = garantie     │
│  « contribution seule » (l'invité ne voit ni ne touche tes rappels).      │
│  PAS de Storage, PAS de Functions (payants).                              │
└───────────────────────────────────────────────────────────────────────────┘
```

**Principes clés** :
- Firestore = **couche de collaboration/transport** ; Room = **base locale** d'où l'on programme alarmes et geofences.
- Un rappel **personnel** (`listId == null`) vit **uniquement dans Room** (jamais dans le cloud) → c'est là que vivent aussi voix/image.
- Un rappel **partagé** existe dans Firestore (texte + déclencheur) et est projeté dans Room sur les appareils autorisés à le lire.
- **Contribution seule** : l'invité ne lit **que ses propres** contributions ; le **propriétaire lit tout** (c'est lui qui doit être rappelé) → c'est sur **l'appareil du propriétaire** que les rappels contribués déclenchent alarmes/geofences.

Stack : **Kotlin** · **Compose + Material 3** · MVVM, single-Activity + Navigation Compose · **Room** · Coroutines/Flow · **Hilt** · **WorkManager** · **Fused Location + Geofencing API** · carte **osmdroid** (OSM, gratuit) · **Firebase Auth + Firestore** (Spark). minSdk 26 / targetSdk 35 · build Android Studio (JDK 17).

---

## 3. Stack & dépendances

**Ajouts Firebase (gratuits uniquement)** : `firebase-bom`, `firebase-auth-ktx`, `firebase-firestore-ktx`, `play-services-auth` (Google Sign-In) ; plugin Gradle `com.google.gms.google-services` + fichier **`google-services.json`**.
**NON inclus** (payants) : ~~`firebase-storage`~~, ~~`firebase-messaging`~~, Cloud Functions.

**Conservé** : Compose BOM (material3, navigation-compose, icons-extended), Room (KSP), Hilt (+ hilt-work), WorkManager, play-services-location, kotlinx-coroutines(-play-services), osmdroid-android, Coil (images locales), DataStore.

**Retiré** : Retrofit + client Overpass. *OkHttp uniquement si l'on veut la recherche d'adresse Nominatim (optionnel) — sinon retirable.*

Versions visées (à confirmer au build) : AGP 8.7.x / Gradle 8.11.x / Kotlin 2.0.21 + plugin Compose Compiler + KSP 2.0.21-1.0.x · Room 2.6.1 · Hilt 2.52 · WorkManager 2.9.x · play-services-location 21.3.0 · osmdroid 6.1.20 · Firebase BoM 33.x · Coil 2.7. *Gotcha* : KSP aligné sur Kotlin ; `userAgentValue` osmdroid ; émulateur **image Google APIs** (Play Services requis pour geofencing ET Firebase).

---

## 4. Configuration Firebase (une fois, plan gratuit Spark)

1. Créer un projet sur [console.firebase.google.com](https://console.firebase.google.com) — **rester sur le plan Spark (aucune carte bancaire demandée)**.
2. Ajouter une app Android (package `com.remindly`, renommable) ; télécharger **`google-services.json`** → dans `app/`.
3. **Authentication → Google** : activer. Déclarer les empreintes **SHA-1 et SHA-256** des clés **debug ET release** (sinon Google Sign-In échoue silencieusement). *(Ne pas utiliser Phone Auth : non gratuit.)*
4. **Cloud Firestore** : créer (mode production) et coller les **règles de sécurité** de la §7.
5. **Ne pas activer Cloud Storage** (payant/Blaze) ni Cloud Functions.
6. **Quotas gratuits à garder en tête** (Firestore) : 50k lectures / 20k écritures / 20k suppressions par jour · 1 Gio stocké · 10 Gio/mois de sortie. Auth : jusqu'à 50k utilisateurs actifs/mois. Largement suffisant pour un usage familial/petits groupes ; §17 explique comment rester dans ces bornes.

---

## 5. Modèle de données local (Room)

Une table `reminders` (discriminateur `triggerType` + colonnes nullables). Enums en `String` via `TypeConverter`.

### `reminders` (`ReminderEntity`)
| champ | type | notes |
|---|---|---|
| `id` | Long PK | id local |
| `remoteId` | String? | id du doc Firestore (null si perso) |
| `listId` | String? | **null = rappel personnel/local** ; sinon liste partagée |
| `authorId` | String? | uid de l'auteur (listes partagées) |
| `authorName` | String? | nom affiché de l'auteur |
| `text` | String? | |
| `createdAt` / `updatedAt` | Long | epoch millis |
| `status` | ReminderStatus | ACTIVE / COMPLETED / SNOOZED / ARCHIVED |
| `pinned` | Boolean | |
| `colorTag` | Int? | |
| `triggerType` | TriggerType | NONE / TIME / PLACE |
| `isRepeating` | Boolean | |
| `lastFiredAt` | Long? | |
| **TIME** `triggerTimeMillis` | Long? | |
| **TIME** `repeatRule` | RepeatRule? | NONE/DAILY/WEEKLY/MONTHLY/CUSTOM |
| **TIME** `repeatIntervalMin` | Int? | pour CUSTOM |
| **TIME** `repeatDaysMask` | Int? | bitmask jours (hebdo) |
| **PLACE** `placeLat` / `placeLng` | Double? | |
| **PLACE** `placeRadiusM` | Float? | défaut 120 |
| **PLACE** `savedPlaceId` | Long? | FK → saved_places (SET NULL) |
| **PLACE** `placeLabel` | String? | nom dénormalisé |
| `syncState` | SyncState | SYNCED / PENDING_UPLOAD / PENDING_DELETE (file offline) |

### `reminder_attachments` (`ReminderAttachmentEntity`) — **local uniquement**
`id` · `reminderId`(FK CASCADE, indexé) · `type`(IMAGE/AUDIO) · `localPath` · `mimeType` · `durationMs`? · `orderIndex`.
> **Contrainte gratuite** : les pièces jointes (voix/image) ne sont **pas** synchronisées (Storage = payant). Un rappel qui porte une pièce jointe reste donc **personnel/local** ; les rappels partagés sont **texte + déclencheur**.

### `saved_places` (`SavedPlaceEntity`)
`id` · `name` · `lat` · `lng` · `defaultRadiusM` · `iconTag`? · `usageCount` · `createdAt`.

### `geofence_registrations` (`GeofenceRegistrationEntity`)
`requestId`(PK, = `pt_{reminderId}`) · `reminderId` · `lat/lng/radiusM` · `registeredAt`.

### `shared_lists` (`SharedListEntity`) — cache local des listes
`listId`(PK) · `name` · `ownerId` · `myRole`(OWNER/GUEST) · `color` · `updatedAt`.

> Dév : `fallbackToDestructiveMigration()`. Entités définies dès le départ.

---

## 6. Modèle de données cloud (Firestore) — texte + déclencheur seulement

```
users/{uid}                     { displayName, email, photoUrl }
users/{uid}/lists/{listId}      { role, name, joinedAt }        // miroir « mes listes »
lists/{listId}                  { name, ownerId, color, createdAt }
lists/{listId}/members/{uid}    { role: 'owner'|'guest', displayName, email, joinedAt, inviteCode? }
lists/{listId}/reminders/{rid}  { authorId, authorName, text, status,
                                  triggerType, timeMillis?, repeatRule?, repeatDaysMask?,
                                  place?: { lat, lng, radiusM, label },
                                  createdAt, updatedAt }         // PAS de média (Storage payant)
invites/{code}                  { listId, role:'guest', createdBy, createdAt, expiresAt, active }
```

---

## 7. Règles de sécurité Firestore (cœur de « contribution seule »)

> ⚠️ Ces règles sont la **seule vraie garantie** — l'UI ne fait que refléter ce qu'elles autorisent. À **tester** avec l'émulateur Firebase (§18).
>
> Rappel important : **les règles ne filtrent pas les requêtes**. Comme l'invité n'a le droit de lire que ses propres contributions, son app **doit** requêter avec `where('authorId','==', sonUid)` ; une requête non filtrée sur les rappels lui est **refusée** en bloc (c'est précisément ce qui l'empêche de voir tes rappels).

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    function signedIn() { return request.auth != null; }
    function uid() { return request.auth.uid; }

    function isMember(listId) {
      return signedIn() &&
        exists(/databases/$(database)/documents/lists/$(listId)/members/$(uid()));
    }
    function myRole(listId) {
      return get(/databases/$(database)/documents/lists/$(listId)/members/$(uid())).data.role;
    }
    function isOwner(listId) { return isMember(listId) && myRole(listId) == 'owner'; }

    function inviteOk(listId, code) {
      return exists(/databases/$(database)/documents/invites/$(code))
        && get(/databases/$(database)/documents/invites/$(code)).data.listId == listId
        && get(/databases/$(database)/documents/invites/$(code)).data.active == true;
    }

    match /users/{userId} {
      allow read: if signedIn();
      allow write: if uid() == userId;
      match /lists/{listId} { allow read, write: if uid() == userId; }
    }

    match /lists/{listId} {
      allow get, list: if isMember(listId);
      allow create:     if signedIn() && request.resource.data.ownerId == uid();
      allow update, delete: if isOwner(listId);

      match /members/{memberUid} {
        allow read: if isMember(listId);
        // Le propriétaire crée sa propre adhésion 'owner' ; un invité s'ajoute LUI-MÊME
        // en 'guest' uniquement avec un code d'invitation valide.
        allow create: if uid() == memberUid && (
            request.resource.data.role == 'owner'
            || (request.resource.data.role == 'guest'
                && inviteOk(listId, request.resource.data.inviteCode))
        );
        allow delete: if isOwner(listId) || uid() == memberUid;   // le proprio exclut ; on peut quitter
        allow update: if isOwner(listId);
      }

      match /reminders/{reminderId} {
        // CONTRIBUTION SEULE :
        //  - le propriétaire LIT TOUT (il doit être rappelé) ;
        //  - les autres ne lisent QUE leurs propres contributions.
        allow read:   if isMember(listId) && (isOwner(listId) || resource.data.authorId == uid());
        // AJOUT : tout membre, en se déclarant auteur.
        allow create: if isMember(listId) && request.resource.data.authorId == uid();
        // MODIFIER : le proprio tout ; les autres seulement leurs docs (sans changer authorId).
        allow update: if isMember(listId) && (
            isOwner(listId)
            || (resource.data.authorId == uid()
                && request.resource.data.authorId == resource.data.authorId)
        );
        // SUPPRIMER : le proprio tout ; les autres seulement leurs docs.
        allow delete: if isMember(listId) && (isOwner(listId) || resource.data.authorId == uid());
      }
    }

    match /invites/{code} {
      allow get: if signedIn();                              // l'invité lit le code → listId
      allow create, update, delete:
        if signedIn() && request.resource.data.createdBy == uid();
    }
  }
}
```

**Ce que ça garantit** :
- Un invité **ne peut pas lire** un rappel dont `authorId` ≠ le sien → **il ne voit jamais tes rappels** (ni ceux des autres invités).
- Un invité **ne peut pas modifier/supprimer** un rappel qui n'est pas le sien.
- Un invité peut **ajouter** des rappels et gérer **les siens**.
- Le propriétaire voit et contrôle **tout** sa liste.

> **Variante plus stricte** (option) : pour un « dépôt sans retour » où l'invité ne peut même plus modifier/supprimer ce qu'il a envoyé, retirer les branches `resource.data.authorId == uid()` de `update`/`delete`. Par défaut je garde l'invité capable de corriger/annuler **ses** contributions.

*(Pas de règles Storage : Cloud Storage n'est pas utilisé.)*

---

## 8. Collaboration & rôles (contribution seule)

- **Espaces** : chaque rappel est soit **personnel** (local, privé, invisible de tous — hors de toute liste), soit rattaché à une **liste partagée**. Le partage est **par liste**.
- **Rôles par liste** : `owner` (voit tout, contrôle tout) · `guest` (ajoute des rappels ; **ne voit que ses propres contributions** ; gère les siennes ; **ne voit pas** celles du propriétaire).
- **Modèle mental** : l'invité est un **contributeur** — il « dépose » des rappels dans ta liste ; toi (propriétaire) tu les reçois et tu es rappelé sur ton appareil. Tes propres rappels restent invisibles pour lui.
- **Invitation / adhésion (gratuit, sans Function)** :
  1. Le propriétaire génère un **code aléatoire** → doc `invites/{code}` (listId, role guest, expiration, `active:true`).
  2. Partage via **lien profond** `remindly://join?code=XXXX` (ou QR), par n'importe quel canal.
  3. L'invité ouvre le lien : l'app lit `invites/{code}` (→ listId), crée **sa propre** adhésion `lists/{listId}/members/{uid}` en `guest` + `inviteCode` (autorisé par les règles), puis écrit le miroir `users/{uid}/lists/{listId}`.
  4. Révocation : le propriétaire passe l'invite `active:false` et/ou supprime le membre.
- **UI selon le rôle** :
  - **Invité** : dans une liste partagée, il voit un espace « Mes ajouts à *[nom de la liste]* » (uniquement ses contributions) + un gros bouton **Ajouter**. Aucun rappel du propriétaire n'est affiché.
  - **Propriétaire** : voit tous les rappels de la liste, avec l'auteur (avatar/nom) sur chaque carte contribuée ; contrôle total.

---

## 9. Moteur de synchronisation (`SyncManager` : Firestore ⇄ Room)

- **À la connexion** : écouter `users/{uid}/lists` → pour chaque liste, attacher les listeners temps réel adaptés au rôle :
  - **Propriétaire** : listener sur **tous** les `lists/{listId}/reminders` (il doit tout recevoir et être rappelé).
  - **Invité** : listener sur `lists/{listId}/reminders` **filtré `where('authorId','==', monUid)`** (conforme aux règles ; il ne reçoit que ses contributions).
- **Descendant (cloud → local)** : chaque snapshot **upsert** dans Room (`remoteId`, `listId`, `authorId`, `syncState=SYNCED`) ; puis, **sur l'appareil du propriétaire**, (re)programme alarmes/geofences pour les rappels ACTIVE (y compris ceux contribués par les invités). Suppression distante → suppression Room + annulation alarme/geofence.
- **Montant (local → cloud)** : création/édition/suppression d'un rappel **partagé autorisé** → écriture Firestore. Hors-ligne, la **persistance Firestore** met en file et synchronise au retour du réseau ; `syncState=PENDING_*` pour l'UI.
- **Personnel** : `listId == null` → **Room uniquement**, jamais poussé.
- **Médias** : non synchronisés (Storage payant) → restent locaux au rappel personnel.
- **Conflits** : « dernière écriture gagne » via `updatedAt`.

---

## 10. Moteur de déclencheurs

### 10.1 TEMPS
`AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, …)`. Vérifier `canScheduleExactAlarms()` ; manifeste `USE_EXACT_ALARM` (app de rappels éligible) + repli `setAndAllowWhileIdle`. 1 `PendingIntent` par rappel (`requestCode = id`) → `AlarmReceiver` (goAsync + coroutine → notif ; répétition → prochaine occurrence en `ZonedDateTime`, gère le DST, puis re-programmation). **Reboot** → `RescheduleAlarmsWorker` ré-arme tous les rappels TIME actifs (roll-forward des occurrences manquées).

### 10.2 LIEU précis
À la création (ou réception via sync côté propriétaire) : `GeofenceManager` enregistre **une** geofence `pt_{id}` (rayon choisi, `ENTER|DWELL`, `loiteringDelay≈30 s`, `notificationResponsiveness≈180 s` pour la batterie). **Entrée** → `GeofenceBroadcastReceiver` → `goAsync()` + notif. **Reboot / MY_PACKAGE_REPLACED** → `BootReceiver` réenregistre toutes les geofences PLACE actives (depuis Room). Coût batterie faible (pas de réseau, pas de localisation continue). Limite 100 geofences largement suffisante.

### 10.3 Déclenchement des rappels **contribués** (sans push serveur — 100 % gratuit)
Les rappels contribués par un invité sont projetés dans Room **sur l'appareil du propriétaire**, qui pose alors alarmes/geofences comme pour un rappel local. Quand le propriétaire apprend-il l'ajout ?
- **App vivante (premier plan ou en arrière-plan non tuée)** : le listener Firestore délivre l'ajout **quasi instantanément** → programmation immédiate.
- **App fermée** : synchro **à la prochaine ouverture** + **WorkManager périodique** (contrainte réseau, intervalle min 15 min ; ex. toutes les 1–6 h) qui réveille le process, synchronise et programme les nouveaux rappels.
- **Compromis honnête (gratuit)** : sans push serveur (Functions = payant), un ajout reçu **process mort** n'est programmé qu'au prochain réveil (ouverture ou job périodique). Si l'heure d'un rappel contribué est déjà passée à la synchro, on notifie en retard / on roll-forward. Pour un usage familial c'est acceptable ; le vrai temps réel app-fermée exigerait Blaze (hors périmètre).

---

## 11. Contenu voix / image (local, rappels personnels)

- **Voix** : `MediaRecorder` → AAC `.m4a` dans `filesDir/audio/` ; lecture `MediaPlayer` ; UI tap-to-record + minuteur. Permission `RECORD_AUDIO` à la 1re utilisation.
- **Image** : **PhotoPicker** (`PickVisualMedia`, sans permission) par défaut ; caméra optionnelle (`TakePicture` + `FileProvider`). `AttachmentStore` **copie** dans `filesDir/images/` ; affichage **Coil**.
- **Portée gratuite** : voix/image disponibles sur les **rappels personnels** (locaux). Les **rappels partagés/contribués** sont **texte + déclencheur** (pas de Storage). Dans l'UI de capture, ajouter une pièce jointe force la destination « Personnel » (avec explication brève).
- **Notifications** (`ReminderNotifier`) : image → `BigPictureStyle` ; audio → action **« Lire »** ; texte → `BigTextStyle` ; tap → deep-link vers le détail ; actions **Terminer / Reporter**. Canaux : rappels-temps, rappels-lieu.

---

## 12. Écrans & navigation

Single Activity + Navigation Compose ; feuilles = `ModalBottomSheet`.

1. **HomeListScreen** — liste style Keep (grille/`LazyColumn`), sélecteur d'espace (**Personnel** / listes partagées), chips filtre (Tous / Temps / Lieu / Faits), recherche, épinglés, **FAB étendu**. *Vue invité d'une liste = uniquement ses ajouts.*
2. **QuickCaptureSheet** *(cœur sans friction)* — FAB → `TextField` auto-focus clavier ouvert ; chips **contenu** (Texte · Voix · Image) ; chips **déclencheur** (Aucun · Temps · Lieu) dépliés **en ligne** ; sélecteur **destination** (Personnel / liste partagée). *Une pièce jointe ⇒ destination Personnel.* Fermer = sauvegarder si non vide.
3. **MapPickerScreen** — `osmdroid` MapView (`AndroidView`), marqueur central déplaçable, slider de rayon, recherche Nominatim (option), « Enregistrer comme lieu ».
4. **ReminderDetailScreen** — voir/éditer, lire l'audio, image (Coil), éditer le déclencheur, Terminer/Supprimer. Lecture seule si non autorisé.
5. **SavedPlacesScreen** — gérer les lieux nommés.
6. **Collaboration** :
   - **SignInScreen** (Google Sign-In) — requise seulement pour les listes partagées.
   - **ListsScreen / tiroir** — créer une liste, voir mes listes.
   - **ListMembersScreen** (propriétaire) — membres + rôles, **générer une invitation** (lien/QR), révoquer, exclure.
   - **JoinListScreen** — rejoindre via code / lien profond.
7. **SettingsScreen** — compte (connexion/déconnexion), état permissions + raccourcis, rayons par défaut, thème, conseils batterie, note « médias non partagés (offre gratuite) ».
8. **PermissionOnboarding** — justifications séquentielles.

---

## 13. Création sans friction (flux)

- FAB → feuille ; todo enregistrable en 2 gestes (aucun tap déclencheur).
- Temps : taper → chip **Temps** → date/heure rapide.
- Lieu : taper → chip **Lieu** → mini-carte / lieu enregistré.
- Contribuer : sélecteur **destination** = choisir une liste partagée.
- Voix : icône micro → enregistrement immédiat (texte facultatif ; reste personnel).
- Défauts intelligents (rayon par défaut, dernière destination), sauvegarde optimiste.

---

## 14. Permissions Android (ordre imposé)

1. `POST_NOTIFICATIONS` (API 33+) tôt.
2. `ACCESS_FINE_LOCATION` (1er usage Lieu/carte).
3. `ACCESS_BACKGROUND_LOCATION` (API 29+) **séparément après** le 1er plan, pré-dialogue fort ; API 30+ renvoie aux Réglages. Requise pour geofences app fermée ; dégrader proprement + bannière si refusée.
4. `RECORD_AUDIO` / `CAMERA` (1re utilisation).
5. Alarme exacte : `canScheduleExactAlarms()` sinon `USE_EXACT_ALARM`.
6. `INTERNET` (Firebase). Panneau d'état dans Réglages ; API `ActivityResult`.

---

## 15. Batterie

- **Geofences de points** : gérées par le système, basse consommation.
- **Alarmes temps** : coût négligeable.
- **Firestore** : pas de listener persistant en arrière-plan (batterie + on n'a pas de push) → synchro à l'ouverture + WorkManager périodique. Aucune veille active permanente.

---

## 16. Plan de construction par phases

- **Phase 0 — Scaffold** : Activity unique, Compose+Material3, Hilt, Navigation, canaux de notif, Home vide. *Se lance.*
- **Phase 1 — Notes CRUD (perso)** : Room + repository ; QuickCaptureSheet (texte) + liste + terminer/supprimer.
- **Phase 2 — TEMPS** : AlarmScheduler/Receiver, notifs, reschedule au boot, répétitions + alarme exacte.
- **Phase 3 — Contenu (local)** : voix + image, AttachmentStore, PhotoPicker/caméra, lecture, notifs riches.
- **Phase 4 — LIEU précis** : MapPicker osmdroid, lieux enregistrés, geofence par point, receiver → notif, reconstruction au boot, flux permissions.
- **Phase 5 — Collaboration (Firebase gratuit, contribution seule)** : Auth Google ; Firestore listes/membres/rappels (texte + déclencheur) ; **règles de sécurité** (§7) ; invitation/adhésion par code/lien ; `SyncManager` (listeners scindés propriétaire/invité) ; UI par rôle ; les rappels contribués déclenchent alarmes/geofences chez le propriétaire ; synchro à l'ouverture + WorkManager périodique.
- **Phase 6 — Finition** : Material You + sombre, Réglages, onboarding, recherche/filtre, conseils batterie/OEM, (option) widget.

> **Hors périmètre gratuit** (documenté, non implémenté) : médias sur rappels partagés (nécessite Cloud Storage = Blaze) ; push instantané app-fermée (nécessite Cloud Functions + FCM = Blaze).

---

## 17. Risques & pièges

- **Sécurité = les règles Firestore**, pas l'UI → **tester** (émulateur). Point critique : la lecture « contribution seule » n'est effective que si **l'app de l'invité requête toujours avec `where authorId == monUid`** (les règles ne filtrent pas ; une requête large est refusée — ce qui protège tes rappels, mais l'UI invité doit être codée avec ce filtre).
- **Storage & Functions = payants (Blaze)** → **exclus**. Conséquences assumées : pas de média partagé, pas de push serveur (voir §10.3, §11).
- **Quotas Spark** : 50k lectures/jour, etc. Les listeners temps réel consomment des lectures par document livré → limiter les listeners aux rappels **ACTIVE**, se désabonner quand l'app passe en arrière-plan prolongé, éviter les re-souscriptions inutiles. Suffisant pour petits groupes.
- **Google Sign-In** : SHA-1/256 (debug **et** release) déclarées dans Firebase, sinon échec silencieux. **Ne pas** utiliser Phone Auth (payant).
- **Localisation arrière-plan** souvent refusée + divulgation Play Store → bannière « rappels de lieu limités ».
- **Émulateur** : image **Google APIs** (geofencing + Firebase).
- **Doze / OEM tueurs** : latence geofence 2–6 min ; synchro périodique parfois retardée si l'app est « tuée » par l'OEM → proposer l'exemption d'optimisation batterie.
- **Reboot** : `BootReceiver` reprogramme geofences + alarmes depuis Room.
- **Offline collaboratif** : persistance Firestore ; refléter `PENDING_*` ; reconcilier au retour réseau.
- **JDK** : build via le JDK 17 d'Android Studio (le Java 24 du PATH ne convient pas à Gradle 8.x).
- **Confidentialité** : rappels personnels + médias restent locaux ; seuls les rappels de listes partagées (texte + déclencheur) vont dans le cloud — le communiquer à l'utilisateur.

---

## 18. Vérification / tests bout-en-bout

1. **Build** : Android Studio → **Gradle JDK 17** → `google-services.json` dans `app/` → Sync → `./gradlew assembleDebug`. Lancer sur **émulateur Google APIs** ou appareil réel.
2. **Tests unitaires** (JVM) : occurrence répétée (DST) ; mappers Room⇄domaine⇄Firestore.
3. **Règles Firestore** (émulateur — le plus important) :
   - invité PEUT créer un rappel (authorId = lui) ;
   - invité **NE PEUT PAS lire** un rappel d'un autre auteur ; sa requête non filtrée est **refusée** ; sa requête `where authorId == lui` **réussit** ;
   - invité **NE PEUT PAS** modifier/supprimer un rappel d'autrui ni changer `authorId` ;
   - invité **NE PEUT PAS** se promouvoir `owner` ni s'ajouter sans code valide ;
   - propriétaire lit/modifie/supprime **tout** dans sa liste ;
   - non-membre : aucun accès.
4. **Phase 1** : créer/terminer/supprimer une note perso.
5. **Phase 2** : rappel à +2 min → notif ; `adb reboot` → re-programmation.
6. **Phase 3** : voix + image (perso) → notif BigPicture + action Lire.
7. **Phase 4** : rappel LIEU ; **Extended Controls → Location** ou `adb emu geo fix <lon> <lat>` → notif ; vérifier `geofence_registrations` (Database Inspector) ; tester reboot.
8. **Phase 5** (deux comptes) : le proprio crée une liste + invitation ; l'invité rejoint, **n'y voit aucun rappel du proprio**, et **ajoute** un rappel → il apparaît chez le proprio en temps réel (app ouverte) et **déclenche** l'alarme/geofence sur l'appareil du proprio ; vérifier que l'invité ne peut ni voir ni éditer/supprimer les rappels du proprio ; tester hors-ligne (créer offline → resync) ; tester app fermée (ajout récupéré à l'ouverture / au job périodique).

---

## 19. Fichiers clés à créer

- `app/google-services.json` — config Firebase (fournie par l'utilisateur, plan Spark).
- `firestore.rules` — règles de sécurité (§7). *(Pas de `storage.rules` : Storage non utilisé.)*
- `app/src/main/java/com/remindly/data/db/entity/` — entités Room (§5).
- `app/src/main/java/com/remindly/data/sync/SyncManager.kt` — synchro Firestore⇄Room, listeners par rôle (§9).
- `app/src/main/java/com/remindly/data/remote/` — `AuthRepository`, `FirestoreListRepository`, `InviteRepository`.
- `app/src/main/java/com/remindly/location/GeofenceManager.kt` · `GeofenceBroadcastReceiver.kt` · `BootReceiver.kt`.
- `app/src/main/java/com/remindly/time/AlarmScheduler.kt` · `AlarmReceiver.kt` · `RescheduleAlarmsWorker.kt`.
- `app/src/main/java/com/remindly/notify/ReminderNotifier.kt`.
- `app/src/main/java/com/remindly/media/` — `AudioRecorder`, `AudioPlayer`, `AttachmentStore` (local).
- `app/src/main/java/com/remindly/ui/` — `home/`, `capture/`, `mappicker/`, `detail/`, `places/`, `lists/`, `members/`, `auth/`, `settings/`, `onboarding/`, `navigation/`, `theme/`.
- Racine Gradle : `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts` (+ plugin `com.google.gms.google-services`), `AndroidManifest.xml` (permissions, receivers, FileProvider, deep link `remindly://join`).

---

## 20. Plan détaillé par phase

**Conventions communes à toutes les phases**
- *Definition of Done* d'une phase = le projet **compile et se lance**, la fonctionnalité de la phase passe son **critère de validation**, aucune régression sur les phases précédentes.
- Architecture par couche : `UI (Compose + ViewModel) → Repository → (Room | Firestore)`. Les ViewModels exposent un `StateFlow<UiState>` ; aucune logique métier dans les composables.
- Fils d'exécution : I/O Room/Firebase sur `Dispatchers.IO` (via `suspend`/`Flow`) ; jamais d'I/O sur le thread principal. `BroadcastReceiver` → `goAsync()` + coroutine bornée.
- Erreurs : les repositories renvoient `Result`/`sealed` ; l'UI affiche des `Snackbar`. Les receivers loggent le code d'erreur et sortent proprement.
- Tests : logique pure (occurrences répétées, mappers, allocation) en tests unitaires JVM ; règles Firestore via l'émulateur.
- Chaque phase peut être un jalon commit séparé.

---

### Phase 0 — Scaffold & fondations

**Objectif / livrable** : projet Android qui compile et se lance ; écran d'accueil vide avec FAB ; thème Material 3 ; DI Hilt ; navigation en place.

**Étapes**
1. Créer le projet dans **Android Studio** (« Empty Activity » Compose), Kotlin DSL, package `com.remindly`, **minSdk 26 / targetSdk 35 / compileSdk 35**, JVM target 17.
2. Mettre en place le **version catalog** `gradle/libs.versions.toml` (toutes les versions de la §3). Configurer `build.gradle.kts` (racine) et `app/build.gradle.kts` : plugins `com.android.application`, `org.jetbrains.kotlin.android`, `org.jetbrains.kotlin.plugin.compose`, `com.google.devtools.ksp`, `com.google.dagger.hilt.android`. **NE PAS** appliquer `com.google.gms.google-services` maintenant (le fichier `google-services.json` n'existe qu'en Phase 5 ; l'appliquer sans le fichier casse le build).
3. Activer Compose (`buildFeatures.compose = true`) + **Compose BOM** (material3, ui, tooling, navigation-compose, hilt-navigation-compose, icons-extended).
4. Classe `RemindlyApp : Application` annotée `@HiltAndroidApp` ; déclarée dans le manifeste (`android:name=".RemindlyApp"`). `MainActivity` annotée `@AndroidEntryPoint`, `setContent { RemindlyTheme { RemindlyNavHost() } }`.
5. **Thème** (`ui/theme/`) : `Color.kt`, `Type.kt`, `Shape.kt`, `RemindlyTheme.kt` avec **couleurs dynamiques (Android 12+)** + repli, clair/sombre.
6. **Navigation** (`ui/navigation/`) : `Routes` (objets/sealed) + `RemindlyNavHost` avec la destination `Home`.
7. **Canaux de notification** : `notify/NotificationChannels.kt` créé au démarrage (`RemindlyApp.onCreate`) — canaux `reminders_time`, `reminders_place`.
8. `ui/home/HomeScreen.kt` : `Scaffold` + `TopAppBar` (« Remindly ») + état vide + `ExtendedFloatingActionButton` (sans action).

**Fichiers** : `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `RemindlyApp.kt`, `MainActivity.kt`, `ui/theme/*`, `ui/navigation/{Routes,RemindlyNavHost}.kt`, `ui/home/HomeScreen.kt`, `AndroidManifest.xml`.

**Points techniques / pièges** : version du plugin Compose Compiler = version Kotlin (2.0.21) ; **Gradle JDK 17** dans Studio ; KSP aligné sur Kotlin ; ne pas appliquer google-services (voir étape 2).

**Validation** : `./gradlew assembleDebug` OK ; l'app se lance, affiche Home vide + FAB ; bascule clair/sombre ; aucun crash.

---

### Phase 1 — CRUD notes personnelles (Room)

**Objectif / livrable** : créer / lister / éditer / terminer / supprimer un rappel **texte personnel** (`listId = null`), persisté localement. Capture rapide fonctionnelle.

**Étapes**
1. Dépendances Room + KSP. Définir **toutes** les entités de la §5 dès maintenant (`ReminderEntity`, `ReminderAttachmentEntity`, `SavedPlaceEntity`, `GeofenceRegistrationEntity`, `SharedListEntity`) pour éviter des migrations plus tard ; seule `reminders` est utilisée en Phase 1. `Converters` pour les enums.
2. Enums de domaine : `TriggerType`, `ReminderStatus`, `RepeatRule`, `SyncState`.
3. `RemindlyDatabase` (`@Database`, `exportSchema = true`). `ReminderDao` : `observeActive(space)`, `observeById(id)`, `upsert`, `setStatus`, `setPinned`, `delete` — les lectures renvoient des `Flow`.
4. `di/DatabaseModule.kt` (Hilt) : fournit la DB + les DAOs.
5. Modèle de domaine `Reminder` + **mappers** Entity⇄domaine.
6. `data/repo/ReminderRepository` (interface + impl) : Flows + CRUD `suspend`. Phase 1 = espace personnel uniquement. `di/RepositoryModule.kt`.
7. `ui/home/HomeViewModel` (`@HiltViewModel`) : collecte le Flow → `StateFlow<HomeUiState>`. `HomeScreen` : `LazyColumn`/grille de cartes (clé stable = id), section épinglés, état vide, terminer (checkbox/swipe), supprimer.
8. `ui/capture/QuickCaptureSheet` (`ModalBottomSheet`) + `CaptureViewModel` : `TextField` **auto-focus + clavier ouvert**, sauvegarde dès qu'il y a du texte, **fermer = sauvegarder si non vide**. Phase 1 : texte + destination « Personnel ».
9. `ui/detail/ReminderDetailScreen` + VM : voir/éditer le texte, terminer, supprimer (route avec argument `reminderId`).

**Fichiers** : `data/db/{RemindlyDatabase,Converters}.kt`, `data/db/entity/*.kt`, `data/db/dao/ReminderDao.kt`, `di/{DatabaseModule,RepositoryModule}.kt`, `domain/model/*.kt`, `data/repo/ReminderRepository(Impl).kt`, `ui/home/HomeViewModel.kt`, `ui/capture/{CaptureViewModel,QuickCaptureSheet}.kt`, `ui/detail/ReminderDetail*.kt`, MAJ navigation.

**Points techniques / pièges** : `imePadding()` sur la feuille pour le clavier ; clés stables en `LazyColumn` ; export du schéma Room ; en dév `fallbackToDestructiveMigration()`.

**Validation** : créer une note texte → apparaît ; éditer ; terminer ; supprimer ; **persiste après redémarrage de l'app**.

---

### Phase 2 — Déclencheur TEMPS (AlarmManager)

**Objectif / livrable** : rappel à une date/heure → **notification** ; répétitions ; **survit au reboot** ; alarme exacte gérée.

**Étapes**
1. Manifeste/permissions : `POST_NOTIFICATIONS` (33+), `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`. Demander `POST_NOTIFICATIONS` au runtime.
2. `notify/ReminderNotifier` : construit/affiche la notif (titre = texte, canal temps), actions **Terminer** / **Reporter**. `notify/NotificationActionReceiver` traite les actions.
3. `time/AlarmScheduler` : `schedule(reminder)` via `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, triggerTimeMillis, pendingIntent)` ; `PendingIntent` (`FLAG_IMMUTABLE | UPDATE_CURRENT`, `requestCode = id`) → `AlarmReceiver` ; `cancel(id)`. Vérifier `canScheduleExactAlarms()` ; repli `setAndAllowWhileIdle` ; chemin UI vers les Réglages si non autorisé.
4. `time/AlarmReceiver` : `goAsync()` → charge le rappel → notifie → si répétitif, calcule la **prochaine occurrence** puis re-programme ; met à jour `lastFiredAt` ; one-shot → `COMPLETED`.
5. `time/NextOccurrenceCalculator` (**pur, testé unitairement**) : DAILY / WEEKLY(`repeatDaysMask`) / MONTHLY / CUSTOM(`repeatIntervalMin`), en `ZonedDateTime` (gère le **DST**, pas d'addition de millis).
6. `location/BootReceiver` (partagé, `RECEIVE_BOOT_COMPLETED` + `MY_PACKAGE_REPLACED`) → enqueue `time/RescheduleAlarmsWorker` : recharge tous les rappels TIME `ACTIVE`, **roll-forward** des occurrences manquées, re-programme.
7. **Snooze** : `NotificationActionReceiver` → statut `SNOOZED` + re-programmation `+X min`.
8. UI capture/détail : chip **Temps** → `DatePicker` + `TimePicker` Material3 + options de répétition ; câbler `AlarmScheduler` à la sauvegarde/édition ; **annuler** l'alarme à Terminer/Supprimer.

**Fichiers** : `time/{AlarmScheduler,AlarmReceiver,NextOccurrenceCalculator,RescheduleAlarmsWorker}.kt`, `time/NextOccurrenceCalculatorTest.kt`, `location/BootReceiver.kt`, `notify/{ReminderNotifier,NotificationActionReceiver}.kt`, receivers au manifeste, ajouts UI.

**Points techniques / pièges** : alarme exacte révocable (Android 14) → gérer le refus ; collisions de `requestCode` (id unique) ; DST via `ZonedDateTime` ; Doze OK avec `...AllowWhileIdle`.

**Validation** : rappel à +2 min → notif ; répétition quotidienne ; `adb reboot` → re-programmation ; snooze ; parcours permission alarme exacte.

---

### Phase 3 — Contenu voix / image (local)

**Objectif / livrable** : joindre **voix + image** à un rappel personnel ; lecture/affichage ; **notifications enrichies**.

**Étapes**
1. Permissions : `RECORD_AUDIO` (1re utilisation) ; `CAMERA` (optionnel) ; PhotoPicker sans permission.
2. `media/AttachmentStore` : **copie** le média choisi/capturé dans `filesDir/audio/` ou `filesDir/images/`, renvoie un chemin stable ; **supprime les fichiers** à la suppression du rappel (cascade DB + nettoyage disque).
3. `media/AudioRecorder` (`MediaRecorder` → AAC `.m4a`) ; `media/AudioPlayer` (`MediaPlayer`). UI tap-to-record + minuteur.
4. Image : `ActivityResultContracts.PickVisualMedia` par défaut ; option `TakePicture` + **FileProvider** (`res/xml/file_paths.xml`, autorité `${applicationId}.fileprovider`). Affichage **Coil**.
5. `data/db/dao/AttachmentDao` + relation `ReminderWithAttachments` ; méthodes repo add/remove pièce jointe.
6. UI capture/détail : chips **Voix** / **Image** ; miniatures ; lecture audio ; suppression d'une pièce jointe.
7. `ReminderNotifier` enrichi : `BigPictureStyle` (image), action **« Lire »** (audio via `NotificationActionReceiver` → `AudioPlayer`), `BigTextStyle` (texte).
8. **Règle** : ajouter une pièce jointe **force la destination « Personnel »** (médias non partagés en offre gratuite) — appliqué et expliqué dans l'UI de capture.

**Fichiers** : `media/{AttachmentStore,AudioRecorder,AudioPlayer}.kt`, `data/db/dao/AttachmentDao.kt`, relation domaine, `res/xml/file_paths.xml`, FileProvider au manifeste, ajouts UI, MAJ notifier.

**Points techniques / pièges** : cycle de vie/`release()` de `MediaRecorder`/`MediaPlayer` ; `filesDir` interne → pas de permission stockage ; disponibilité PhotoPicker (module de compat sur anciens appareils) ; focus audio.

**Validation** : enregistrer voix + joindre image → visibles au détail, audio jouable ; notif BigPicture + action Lire ; supprimer le rappel efface les fichiers.

---

### Phase 4 — Déclencheur LIEU précis (Geofencing + osmdroid)

**Objectif / livrable** : poser un **point sur la carte** + rayon ; **geofence par point** ; entrée → notif ; **lieux enregistrés** ; **survit au reboot** ; parcours complet des permissions de localisation.

**Étapes**
1. **Permissions** : `ACCESS_FINE_LOCATION` (premier plan d'abord) puis `ACCESS_BACKGROUND_LOCATION` **séparément après**, avec écran de justification (`ui/onboarding/`) ; API 30+ renvoie aux Réglages. Manifeste.
2. **osmdroid** : dépendance + `userAgentValue` dans `RemindlyApp` ; `ui/mappicker/MapPickerScreen` avec `AndroidView(MapView)`, marqueur central déplaçable, **slider de rayon** (overlay `Circle`), bouton « ma position » (`FusedLocationSource.getCurrentLocation`), recherche **Nominatim** *(optionnelle, OkHttp)*, « Enregistrer comme lieu ».
3. **Lieux enregistrés** : `SavedPlaceDao`, repo, `ui/places/SavedPlacesScreen` (lister/ajouter/éditer/supprimer) ; sélection d'un lieu enregistré dans la capture.
4. `location/GeofenceManager` : `add(reminder)` → `GeofencingClient.addGeofences` (une `Geofence` `pt_{id}`, circulaire, `ENTER|DWELL`, `loiteringDelay≈30 s`, `notificationResponsiveness≈180 s`), **un seul `PendingIntent`** (`FLAG_MUTABLE | UPDATE_CURRENT`) → `GeofenceBroadcastReceiver` ; `remove(requestId)` ; consigne dans `geofence_registrations`.
5. `location/GeofenceBroadcastReceiver` : `GeofencingEvent.fromIntent` ; si erreur → log + return ; `ENTER|DWELL` → `goAsync()` → pour chaque `requestId` → registration → rappel → notif ; one-shot → `COMPLETED` (retirer la geofence) ; répétitif → `lastFiredAt` + cooldown.
6. **Boot** : `BootReceiver` (étendu) vide `geofence_registrations` puis enqueue `location/RescheduleGeofencesWorker` qui **réenregistre** toutes les geofences des rappels PLACE `ACTIVE` (depuis Room).
7. UI capture/détail : chip **Lieu** → aperçu mini-carte + « Choisir sur la carte » (MapPicker) + lieux enregistrés + rayon ; câbler `GeofenceManager` à la sauvegarde ; annuler à Terminer/Supprimer.

**Fichiers** : `location/{GeofenceManager,GeofenceBroadcastReceiver,FusedLocationSource,RescheduleGeofencesWorker}.kt`, `location/BootReceiver.kt` (étendu), `data/db/dao/{SavedPlaceDao,GeofenceRegistrationDao}.kt`, `ui/mappicker/MapPickerScreen.kt`+VM, `ui/places/SavedPlacesScreen.kt`+VM, UI permissions, manifeste.

**Points techniques / pièges** : limite **100 geofences** ; geofences **perdues au reboot** ; `getLastLocation()` **null** après boot → `getCurrentLocation()` ; UX localisation arrière-plan ; batterie via DWELL/responsiveness ; **émulateur image Google APIs** ; `PendingIntent` unique (évite `TOO_MANY_PENDING_INTENTS`) ; gérer « localisation désactivée ».

**Validation** : créer un rappel LIEU ; `adb emu geo fix <lon> <lat>` (ou Extended Controls) → notif ; vérifier `geofence_registrations` (Database Inspector) ; reboot → réenregistrement ; parcours permissions ; dégradation propre si arrière-plan refusé.

---

### Phase 5 — Collaboration Firebase (gratuit, contribution seule)

**Objectif / livrable** : Google Sign-In ; listes partagées ; règles « contribution seule » ; invitation/adhésion ; sync Firestore⇄Room ; UI selon rôle ; les rappels contribués déclenchent alarmes/geofences **sur l'appareil du propriétaire**.

**Étapes**
1. **Setup Firebase** (Spark) : créer le projet, `google-services.json` dans `app/`, **appliquer maintenant** le plugin `com.google.gms.google-services`, ajouter `firebase-bom` + `auth` + `firestore` + `play-services-auth`. Déclarer **SHA-1/256** (debug+release). Déployer les **règles `firestore.rules`** (§7).
2. **Auth** : `data/remote/AuthRepository` (Google Sign-In via Credential Manager + `FirebaseAuth`), `ui/auth/SignInScreen`, `Flow` d'état d'auth ; upsert `users/{uid}` à la connexion.
3. **Repos Firestore** : `FirestoreListRepository` (créer une liste + doc membre `owner` + miroir `users/{uid}/lists` ; observer mes listes ; CRUD des rappels selon le rôle) ; `InviteRepository` (créer un code d'invitation ; lire un code ; **join** = créer le doc membre `guest` + miroir).
4. **Mapping** : doc rappel Firestore ⇄ `ReminderEntity` (`remoteId`, `listId`, `authorId`, `authorName`) ; cache `SharedListEntity`.
5. **`data/sync/SyncManager`** : à la connexion, observer `users/{uid}/lists` → pour chaque liste, attacher le listener adapté au rôle (**propriétaire : tous** les rappels ; **invité : `where('authorId','==', monUid)`**). Upsert dans Room ; **sur l'appareil du propriétaire**, programmer alarmes/geofences pour les rappels `ACTIVE` synchronisés ; gérer les suppressions (annuler les déclencheurs). Montant : écrire les changements locaux des rappels partagés autorisés dans Firestore ; **activer la persistance hors-ligne Firestore** ; `syncState=PENDING_*` hors-ligne.
6. **Synchro périodique** : `data/sync/SharedSyncWorker` (WorkManager périodique, contrainte réseau) + synchro à l'ouverture de l'app (rattrapage app fermée ; **pas de FCM**).
7. **Invitations** : `ui/members/ListMembersScreen` (propriétaire) génère une invitation → **lien profond** `remindly://join?code=` + **QR** + Sharesheet ; `ui/join/JoinListScreen` gère le lien (intent-filter au manifeste) → adhésion ; révoquer / exclure un membre.
8. **UI selon rôle** : sélecteur d'espace (Personnel / listes partagées) ; **invité** = uniquement ses ajouts (« Mes ajouts à … ») ; **propriétaire** = tout, avec puce auteur ; lecture seule (cadenas) sur les items non modifiables.
9. **Capture** : sélecteur de **destination** inclut les listes partagées ; joindre un média **force Personnel** (règle Phase 3).

**Fichiers** : `di/FirebaseModule.kt`, `data/remote/{AuthRepository,FirestoreListRepository,InviteRepository}.kt`, `data/sync/{SyncManager,SharedSyncWorker}.kt`, mappers DTO, `ui/auth/SignInScreen.kt`+VM, `ui/lists/ListsScreen.kt`+VM, `ui/members/ListMembersScreen.kt`+VM, `ui/join/JoinListScreen.kt`+VM, intent-filter + plugin google-services au manifeste, `firestore.rules`, MAJ capture/home.

**Points techniques / pièges** : empreintes SHA ; **les règles ne filtrent pas** → l'UI invité **doit** requêter par `authorId` ; **quota de lectures** des listeners → limiter aux rappels `ACTIVE`, se désabonner en arrière-plan prolongé ; conflits hors-ligne (`updatedAt`) ; gestion du lien profond ; **Credential Manager** (préféré à l'ancien GoogleSignIn) ; ne programmer les déclencheurs **que sur l'appareil censé être notifié** (le propriétaire) ; ne pas appliquer google-services avant d'avoir le JSON (reporté depuis Phase 0).

**Validation** (deux comptes) : le propriétaire crée une liste + invitation ; l'invité rejoint, **ne voit aucun rappel du propriétaire**, **ajoute** un rappel → visible chez le propriétaire en **temps réel** (app ouverte) et **programme le déclencheur** sur son appareil ; l'invité ne peut ni voir ni éditer/supprimer les items du propriétaire ; création **hors-ligne** → resync ; **app fermée** → rattrapage à l'ouverture / au job périodique ; **règles testées** dans l'émulateur (cf. §18.3).

---

### Phase 6 — Finition

**Objectif / livrable** : polissage UX, réglages, onboarding, recherche/filtre, conseils batterie, widget optionnel.

**Étapes**
1. **Material You** : couleur dynamique (12+), clair/sombre complet, typographie/formes, transitions/motion, états vides soignés, retours haptiques.
2. `ui/settings/SettingsScreen` : compte (connexion/déconnexion), **état des permissions** + raccourcis vers les Réglages système, rayon par défaut, thème (système/clair/sombre), **exemption d'optimisation batterie**, note « médias non partagés (offre gratuite) ».
3. `ui/onboarding/` : séquence de justification des permissions au premier lancement + intro des fonctions.
4. **Recherche + filtres** : recherche plein-texte ; chips (Tous / Temps / Lieu / Faits) ; tri (date, épinglés).
5. **Batterie / OEM** : détecter les OEM agressifs, proposer les exemptions, expliquer la latence des geofences.
6. **Optionnel** : widget écran d'accueil (Glance) des rappels du jour ; tuile d'ajout rapide.
7. **QA** : accessibilité (descriptions, cibles tactiles), externalisation des chaînes (fr, éventuellement en), cas limites, revue crash/ANR.

**Fichiers** : `ui/settings/*`, `ui/onboarding/*`, recherche/filtre dans `ui/home/`, `res/values/strings*.xml`, (option) module widget.

**Validation** : parcours de bout en bout de toutes les fonctions ; bascule de thème ; récupération après refus de permission ; recherche/filtre ; onboarding sur installation neuve.
