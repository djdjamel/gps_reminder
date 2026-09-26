J’ai relu le dossier complet, en prenant aussi en compte ce que nous avons déjà discuté sur le **ContextRelevanceEngine** et l’**Activity Recognition**. J’ai également vérifié plusieurs points techniques et le paysage actuel des applications.

Mon avis franc est assez clair :

> **Remindly n’est plus, à mes yeux, une simple idée d’application de rappels. C’est un vrai projet produit avec une architecture différenciante.**
>
> Mais le projet est actuellement **plus avancé technologiquement qu’il ne l’est commercialement**, et certaines affirmations du dossier sont trop absolues. Les principaux risques ne sont plus vraiment « arriver à coder le concept » ; ils sont désormais **fiabilité, simplicité, permissions Android, dépendance aux données cartographiques et positionnement**.

Le dossier affirme d'ailleurs être au stade de « MVP Avancé & Fonctionnel (Android) » en septembre 2026. 

---

# 1. Le concept fondamental est réellement bon

Le meilleur élément du projet est la transformation du rappel en **intention contextuelle**.

Le dossier résume très bien l'idée :

> « Vos rappels ne dépendent plus d'une heure. Ils dépendent de votre situation dans le monde réel. » 

C'est beaucoup plus intéressant que :

> « rappel GPS »

Parce que le problème n'est pas réellement le GPS.

Le problème est :

> **Comment savoir que c'est maintenant le bon moment pour me rappeler quelque chose ?**

Et Remindly possède plusieurs signaux pour répondre à cette question :

**position + POI + distance + direction + vitesse + activité + trajet + heure.**

C'est une vraie architecture de **context awareness**.

---

# 2. Le plus gros mérite de ton projet : les fonctionnalités commencent à former un système cohérent

Pris séparément :

* géofence ;
* catégorie de commerce ;
* TTS ;
* Activity Recognition ;
* vitesse ;
* bearing ;
* trajet habituel ;
* cooldown ;

ne sont pas révolutionnaires.

Mais ensemble, ils peuvent produire quelque chose de très différent.

Le dossier présente justement un moteur qui combine le POI, le mode de déplacement, la trajectoire, la vitesse et un scoring 0–100. 

C'est là que je trouve le projet intéressant.

### Exemple

Rappel :

> « Acheter du pain »

Remindly détecte :

```text
POI = boulangerie
distance = 170 m
bearing = favorable
distance = décroissante
activité = IN_VEHICLE
vitesse = 30 km/h
POI situé sur le trajet
```

Le système peut conclure :

> **Très probablement pertinent maintenant.**

C'est beaucoup plus intelligent que :

```text
distance < 300 m
    → ALERTE
```

---

# 3. Mais il y a une différence énorme entre « moteur intelligent » et « moteur fiable »

C'est probablement **le point le plus important de mon analyse**.

Le dossier contient un beau scoring :

* distance ;
* vecteur ;
* activité ;
* seuil 70 → alarme ;
* 45–69 → notification ;
* <45 → suppression. 

C'est une bonne architecture.

Mais les valeurs `70` et `45` ne sont pas encore une intelligence en soi.

Elles sont des **hypothèses**.

Le véritable moteur devra être calibré avec des situations réelles :

> « Est-ce que l'utilisateur voulait effectivement cette alerte ? »

Il faut donc enregistrer anonymement/localement des événements de diagnostic du genre :

```text
POI détecté
distance
bearing
vitesse
activité
distance trend
trajet
score
→ déclenché ?
→ utilisateur a terminé ?
→ utilisateur a ignoré ?
→ utilisateur a annulé ?
```

Ton système de logs prévu dans la première version est donc particulièrement précieux. Le document prévoit déjà un journal d'événements avec export JSON. 

**Je transformerais presque ce système de logs en outil central de développement du moteur contextuel.**

---

# 4. Activity Recognition a maintenant une vraie place architecturale

Après lecture de ce dossier, je confirme encore plus fortement ce qu'on disait précédemment.

Activity Recognition n'est pas une « feature cool ».

C'est un **capteur de contexte**.

Le dossier l'utilise déjà comme donnée du moteur : voiture, vélo, marche, immobile. 

Et l'API Android est justement conçue pour signaler les transitions d'activité sans que chaque application doive interroger en permanence les capteurs elle-même ; Google présente explicitement cette approche comme plus favorable à la consommation énergétique. ([Développeurs Android][1])

Le point important est :

> **Activity Recognition ne doit jamais décider seule.**

Elle doit modifier l'interprétation des autres signaux.

Par exemple :

```text
300 m + WALKING
```

n'a pas la même signification que :

```text
300 m + IN_VEHICLE + 80 km/h
```

Et :

```text
IN_VEHICLE → STATIONARY → WALKING
```

peut être un événement contextuel beaucoup plus intéressant encore :

> l'utilisateur vient probablement de se garer.

Je conserverais donc absolument cette brique.

---

# 5. Mais je changerais la philosophie du Context Engine

Actuellement, ton dossier donne l'impression d'un système de règles avec score.

Je pousserais vers :

### **Context Engine = état + signaux + score + historique court**

Par exemple :

```text
              REMINDER
                  │
                  ▼
         ┌──────────────────┐
         │ CONTEXT ENGINE   │
         └──────────────────┘
                  │
     ┌────────────┼─────────────┐
     ▼            ▼             ▼
 LOCATION      ACTIVITY       TIME
     │            │             │
     ▼            ▼             ▼
 POI          Walking        Schedule
 Distance     Driving        Deadline
 Bearing      Cycling
 Route        Stationary
     │            │
     └────────────┼─────────────┘
                  ▼
          CONTEXT STATE
                  │
                  ▼
          RELEVANCE SCORE
                  │
        ┌─────────┴─────────┐
        ▼                   ▼
     WAIT                 ALERT
```

Et j'ajouterais une chose :

### **l'historique très court**

Par exemple :

```text
420m
350m
280m
210m
```

Ce n'est pas la même chose que :

```text
420m
450m
520m
```

Dans le premier cas, tu t'approches réellement du POI.

Cette simple information pourrait améliorer énormément la pertinence sans avoir besoin d'une API de routage.

---

# 6. Là où je suis beaucoup moins convaincu : « 0 % batterie »

C'est la première affirmation que je supprimerais du dossier.

Le document annonce :

> « 0 % » en veille passive

et décrit une architecture avec geofencing matériel, GPS 15 s puis 5 s. 

La philosophie technique est bonne.

**La formulation est mauvaise.**

Le geofencing est justement conçu pour permettre un suivi de proximité relativement économe, mais une consommation de batterie strictement nulle n'existe pas. Et ton niveau 2/3 réactive réellement la localisation haute précision.

Je parlerais de :

> **« consommation minimale en veille et activation adaptative de la localisation haute précision »**

plutôt que :

> « 0 % ».

Cela paraît moins spectaculaire, mais c'est beaucoup plus crédible.

---

# 7. J'ai trouvé un problème technique concret avec tes 60 POIs

Le document indique :

> jusqu'à **60 POIs** armés simultanément. 

Or Android limite actuellement à **100 géofences actives par application et par utilisateur**. Google documente même un code d'erreur spécifique lorsque ce seuil est dépassé. ([Google for Developers][2])

Donc :

```text
60 POI
+
60 POI pour une autre catégorie
```

ne peut évidemment pas fonctionner tel quel.

Et même :

```text
3 rappels × 60 POI = 180
```

pose immédiatement problème.

### C'est un point architectural important.

Il te faut un :

> **Geofence Allocation Manager**

qui considère les 100 geofences comme une ressource limitée.

Par exemple :

```text
100 slots maximum

Priority:
1. POIs imminents
2. POIs sur trajet
3. rappel à forte priorité
4. POIs éloignés
```

Puis remplacement dynamique.

**Je considère cela comme une évolution importante à faire avant une vraie montée en charge.**

---

# 8. Le plus gros problème de données : Overpass

Ton dossier utilise :

> OSM Overpass + Google Places

pour récupérer les POIs. 

Pour un prototype, c'est une excellente idée.

Pour un produit commercial à grande échelle, je serais beaucoup plus prudent avec :

> **les instances publiques Overpass comme backend de production.**

La documentation Overpass avertit explicitement que les serveurs publics sont destinés aux petits projets, qu'ils peuvent être surchargés, et recommande une instance propre ou un fournisseur commercial pour une application qui devient un véritable service. ([OpenStreetMap][3])

Le problème est particulièrement important avec ton principe de :

> nouvelle zone → nouveau scan → nouveaux POIs → nouvelle zone.

À quelques milliers d'utilisateurs, ça devient une architecture de requêtes assez agressive.

### Je ferais donc :

```text
                     POI ENGINE
                         │
             ┌───────────┴───────────┐
             ▼                       ▼
        Local cache             Network
                                  │
                         ┌────────┴────────┐
                         ▼                 ▼
                     Google          OSM backend
                                       contrôlé
```

Et à terme, plutôt :

> **ta propre base/index POI alimenté par OSM**

plutôt que :

> smartphone → Overpass public.

---

# 9. Il y a également une contradiction importante dans la promesse « Privacy-First »

Le dossier dit :

> « traitement 100% local »
>
> « Aucune donnée de localisation en direct n'est transmise ». 

Mais l'architecture utilise Google Places, notamment Nearby Search, dont la requête contient précisément une zone de recherche centrée sur une latitude/longitude. ([Google for Developers][4])

Google indique également que les requêtes Maps Platform sont journalisées. ([Google for Developers][5])

Donc, **si le téléphone envoie directement sa position pour rechercher les POIs**, il est impossible de dire littéralement :

> « aucune donnée de localisation n'est transmise ».

Et Firestore introduit aussi une couche cloud pour la synchronisation et la collaboration. 

### Je remplacerais la promesse par quelque chose comme :

> **Local-first : les rappels et le moteur de contexte fonctionnent sur l'appareil ; les services réseau ne sont sollicités que lorsque nécessaire pour les données cartographiques ou la synchronisation.**

C'est beaucoup plus défendable.

---

# 10. Ta vraie promesse « Privacy » pourrait néanmoins devenir un avantage

Et là, je pense que tu as quelque chose de commercialement intéressant.

Il faut simplement être précis.

Tu pourrais dire :

> **« Votre historique de déplacement n'est pas enregistré comme un historique cloud permanent par Remindly. »**

C'est très différent de :

> « aucune localisation ne quitte jamais le téléphone ».

Cette distinction peut devenir importante, notamment face à des systèmes qui accumulent énormément de données comportementales.

---

# 11. Le dossier surestime aujourd'hui la « singularité » de Remindly

C'est un point que je veux vraiment souligner parce que le document contient :

> **« Pourquoi Remindly est Unique sur le Marché »**. 

Je ne conserverais pas cette formulation telle quelle.

J'ai vérifié le marché actuel et il existe déjà des applications qui proposent des rappels pour :

> « any grocery store »

ou :

> « any pharmacy ».

Par exemple, GeoToDo revendique explicitement ce comportement, et GeoNudge propose également des déclencheurs par catégorie. ([GeoToDo][6])

Locado utilise lui aussi le principe du rappel lorsqu'on passe devant n'importe quel commerce pertinent. ([Google Play][7])

Il existe même des applications Android récentes de rappel géographique telles que SpotCue et ToDo Reminder Nearby. ([Google Play][8])

Et les grands généralistes proposent déjà des rappels géographiques classiques : Apple Reminders permet de déclencher à l'arrivée ou au départ d'un lieu, et Any.do propose aussi des rappels basés sur la localisation. ([Support Apple][9])

### Donc :

❌ **« personne ne fait cela »**

n'est plus défendable.

Mais :

✅ **« Remindly combine plusieurs mécanismes dans un moteur contextuel orienté déplacement »**

est beaucoup plus intéressant.

---

# 12. C'est la combinaison qui peut devenir différenciante

Je considère comme beaucoup plus prometteur cet ensemble :

```text
ANY CATEGORY
      +
MULTIPLE POIs
      +
ROUTE AWARENESS
      +
ACTIVITY RECOGNITION
      +
SPEED
      +
BEARING
      +
DISTANCE TREND
      +
VOICE
      +
PRIORITY AUDIO
      +
ONE-TIME / HABIT / TIME+LOCATION
```

Le concurrent peut avoir :

> « any grocery store ».

Mais cela ne signifie pas qu'il possède nécessairement toute cette chaîne de décision.

**C'est cette combinaison qu'il faut vendre.**

---

# 13. Je trouve la partie « collaboration » beaucoup plus intéressante qu'elle n'en a l'air

Cette fonctionnalité :

> « Quand mon collègue arrive sur le chantier X, rappelle-lui de récupérer la clé » 

change énormément la nature du produit.

Tu passes de :

> **Personal Reminder**

à :

> **Contextual Task Dispatch**

Et là, le marché B2B commence à devenir crédible.

Imagine :

### Technicien

> « Quand tu arrives chez le client → vérifie le compteur. »

### Livreur

> « À l'arrivée → fais signer le bon. »

### Commercial

> « À l'arrivée chez ce client → montrer le nouveau catalogue. »

### Équipe de maintenance

> « À l'entrée du site → vérification sécurité. »

Le potentiel est supérieur au simple :

> « N'oublie pas le lait. »

---

# 14. En revanche, je ne partirais pas immédiatement dans les intégrations Salesforce / HubSpot / Slack / IA / Wearables

Le document propose énormément de pistes :

* LLM ;
* agenda ;
* mails ;
* CRM ;
* dispatch ;
* Salesforce ;
* HubSpot ;
* Android Auto ;
* montres ;
* Waze ;
* API SDK ;
* sponsoring. 

C'est excellent pour un brainstorming.

Mais **très dangereux pour la roadmap**.

Le projet pourrait facilement devenir :

> « on développe tout sauf ce qui fait que Remindly fonctionne parfaitement. »

---

# 15. Le produit doit résoudre UNE situation extraordinairement bien

Pour moi, le test ultime est celui-ci :

Je parle à Remindly :

> 🎙️ « Quand je rentrerai du travail, rappelle-moi d'acheter des couches. »

Et Remindly comprend :

```text
ACTION = acheter des couches
CONTEXTE = retour travail → domicile
CATÉGORIE = supermarché
ACTIVITÉ ATTENDUE = véhicule
```

Puis :

🚗 4 km avant

→ rien.

🚗 1 km avant

→ surveillance renforcée.

🚗 400 m

→ bon POI.

🚗 35 km/h

→ bonne opportunité.

🔊

> **« Rappel : acheter les couches, à proximité de Family Shop. »**

Ça fonctionne.

### Et surtout :

**l'utilisateur ne réfléchit à rien.**

C'est ça le produit.

---

# 16. Le scénario « 2 secondes » est donc plus important que le LLM

Le dossier parle de Quick Capture et de création vocale. 

Je mettrais cette fonctionnalité très haut dans la roadmap.

Parce que toute la sophistication du moteur est inutile si la création du rappel est pénible.

Idéalement :

> 🎙️ « Rappelle-moi d'acheter du lait dans un supermarché. »

→ terminé.

Le LLM pourra plus tard comprendre :

> « En rentrant ce soir, rappelle-moi d'acheter du poisson. »

Mais **je ne ferais pas dépendre la V1 d'un LLM**.

---

# 17. La partie audio est excellente, mais la formulation technique doit être prudente

Le projet utilise `AudioAttributes.USAGE_ALARM`. Android documente bien `USAGE_ALARM` comme l'usage destiné aux alarmes. ([Développeurs Android][10])

Mais je ne présenterais pas cela comme une garantie universelle de :

> « bypass silencieux + bypass DND ».

Le comportement audio dépend aussi de la configuration système et des politiques audio.

Donc :

> **« canal audio prioritaire de type alarme »**

est défendable.

> **« contourne toujours le mode silencieux et Ne pas déranger »**

est beaucoup trop absolu.

---

# 18. Le projet a aussi une dette Android qu'il faut prendre au sérieux

Pour les géofences, Android demande une localisation précise et, pour les applications ciblant Android 10+, une permission de localisation en arrière-plan. Google souligne également que l'accès en arrière-plan est soumis à des exigences de justification et à la politique Google Play. ([Développeurs Android][11])

Même chose pour Activity Recognition : à partir d'Android 10, l'autorisation `ACTIVITY_RECOGNITION` est une permission runtime. ([Développeurs Android][12])

Ce n'est donc pas seulement :

> « techniquement ça marche ».

Il faut obtenir :

> **permission → compréhension utilisateur → maintien de permission → compatibilité constructeur → comportement background → conformité Play Store.**

Et c'est critique pour Remindly puisque son fonctionnement principal dépend précisément de l'arrière-plan.

---

# 19. Un détail important : ton dossier parle encore d'Android 15

La fiche indique :

> Android 8 → Android 15 / SDK 35. 

Or, au **23 septembre 2026**, Android 16 correspond à l'API 36, et depuis le **31 août 2026**, les nouvelles applications et mises à jour soumises à Google Play doivent cibler Android 16/API 36 ou supérieur. ([Android Open Source Project][13])

Donc, pour une sortie commerciale maintenant :

> **target SDK 36 doit être une priorité immédiate.**

Ce n'est pas un problème conceptuel, mais il faut mettre le dossier à jour.

---

# 20. Le point B2C/B2B : je ne choisirais pas encore, je séparerais les produits

Je vois en réalité **deux produits différents**.

### Remindly Personal

```text
Courses
Pharmacie
Pain
Colis
Rendez-vous
Trajet maison/travail
```

Simple, privé, vocal, rapide.

### Remindly Teams

```text
Chef
   ↓
Technicien
   ↓
Zone
   ↓
Tâche
   ↓
Arrivée
   ↓
Alerte
   ↓
Acquittement
```

avec :

* tableau de bord ;
* preuve de présence ;
* distribution ;
* historique ;
* éventuellement reporting.

La logique sous-jacente est la même.

Mais **l'UX et le modèle économique sont complètement différents**.

---

# 21. Et je pense que le B2B possède une valeur stratégique particulière

Parce que ton moteur contextuel peut devenir une technologie réutilisable.

Le document évoque déjà une licence du `ContextRelevanceEngine` sous forme de SDK. 

Cette idée m'intéresse.

Mais **pas maintenant**.

Première étape :

> prouver que le moteur fonctionne.

Deuxième :

> prouver qu'il fonctionne de manière fiable sur plusieurs téléphones.

Troisième :

> prouver qu'il réduit réellement les rappels manqués/faux positifs.

Seulement ensuite :

> SDK.

---

# 22. Ce que je ferais maintenant : une roadmap très différente du brainstorming actuel

### Phase 1 — Fiabilité

Je mettrais presque tout le développement autour de :

**« Est-ce que Remindly sonne exactement quand il faut ? »**

Tests réels :

* voiture ;
* marche ;
* vélo ;
* autoroute ;
* centre-ville ;
* plusieurs commerces rapprochés ;
* GPS imprécis ;
* faible réseau ;
* téléphone verrouillé ;
* musique Bluetooth ;
* économie d'énergie ;
* plusieurs rappels actifs.

---

### Phase 2 — Context Engine

Améliorer :

```text
Activity
+ speed
+ bearing
+ distance trend
+ POI
+ route
+ dwell
+ history
```

et faire du moteur un composant indépendant :

```text
ContextRelevanceEngine
```

---

### Phase 3 — Quick Capture

> **Une phrase → rappel exploitable.**

Pas besoin d'IA spectaculaire.

Il faut surtout une compréhension fiable des intentions simples.

---

### Phase 4 — Distribution

Une fois le moteur fiable :

> B2C

puis :

> B2B.

---

# 23. Ce que je repousserais franchement

Pour moi, ces éléments peuvent attendre :

**LLM proactif**, **lecture des mails**, **Salesforce**, **HubSpot**, **Slack**, **Waze**, **montres**, **sponsoring local**, **SDK tiers**, **CarPlay**.

Ce sont de très bonnes idées de V2/V3.

Mais aujourd'hui, ils dispersent l'équipe.

---

# 24. Mon évaluation globale

Voilà comment je vois le projet **aujourd'hui**, pas le projet imaginaire après dix ans de développement :

| Dimension                | Mon avis                                   |
| ------------------------ | ------------------------------------------ |
| Concept                  | **Très fort**                              |
| Problème utilisateur     | **Réel mais pas universel**                |
| Innovation               | **Bonne dans la combinaison**              |
| Architecture             | **Ambitieuse et cohérente**                |
| Activity Recognition     | **Très pertinente**                        |
| Context Engine           | **Cœur technologique potentiel**           |
| UX potentielle           | **Très forte si simplifiée**               |
| Fiabilité                | **Le principal défi**                      |
| Scalabilité actuelle     | **À revoir**                               |
| Privacy claim actuel     | **À corriger**                             |
| Différenciation actuelle | **Réelle mais pas "unique"**               |
| B2C                      | **Potentiel mais probablement niche**      |
| B2B                      | **Potentiel beaucoup plus important**      |
| Potentiel global         | **Élevé si le moteur devient très fiable** |

---

# Mon avis franc, au fond

**Je continuerais le projet.**

Mais je changerais légèrement sa philosophie.

Aujourd'hui le projet semble vouloir démontrer :

> « Regardez tout ce que Remindly sait faire. »

Je voudrais qu'il démontre :

> **« Remindly sait exactement quand un rappel devient pertinent. »**

C'est une différence fondamentale.

La technologie existe déjà pour obtenir les briques individuellement : géofencing, activité, localisation, POI, audio. L'intérêt du projet est de **les orchestrer correctement**. Le dossier possède déjà cette direction avec son moteur de scoring contextuel. 

Et surtout, je pense que tu tiens quelque chose de plus intéressant qu'une application de rappels :

> ### **un moteur de contexte mobile auquel on a attaché une application de rappels comme premier produit.**

C'est cette perspective qui, à mon avis, donne au projet son véritable potentiel.

Le plus gros danger serait maintenant de passer les prochains mois à ajouter des fonctionnalités. **Le meilleur investissement serait de rendre le moteur tellement fiable que l'utilisateur finisse par faire confiance à Remindly instinctivement.**

Il y a toutefois un point que je considérerais comme **prioritaire avant toute commercialisation : faire un audit technique réel du `GeofenceManager + ContextRelevanceEngine + Activity Recognition + POI discovery`**, parce que le dossier décrit l'architecture idéale, mais certains choix — notamment les 60 POIs, le rolling scan, Overpass, les 15/5 secondes GPS et le scoring — doivent être confrontés au comportement réel d'Android sur plusieurs appareils. C'est probablement l'étape qui apportera le plus de valeur au projet maintenant.

[1]: https://developer.android.com/codelabs/activity-recognition-transition?utm_source=chatgpt.com "Activity Recognition Transition API Codelab  |  Android Developers"
[2]: https://developers.google.com/android/reference/com/google/android/gms/location/GeofenceStatusCodes?authuser=19&utm_source=chatgpt.com "GeofenceStatusCodes  |  Google Play services  |  Google for Developers"
[3]: https://wiki.openstreetmap.org/wiki/Overpass_API?utm_source=chatgpt.com "Overpass API - OpenStreetMap Wiki"
[4]: https://developers.google.com/maps/documentation/places/web-service/nearby-search?utm_source=chatgpt.com "Nearby Search (New)  |  Places API (New)  |  Google for Developers"
[5]: https://developers.google.com/maps/security/compliance/security-compliance?utm_source=chatgpt.com "Security and compliance overview for Google Maps Platform  |  Google for Developers"
[6]: https://geotodo.app/?utm_source=chatgpt.com "GeoToDo — AI-Powered Location & Time Reminders"
[7]: https://play.google.com/store/apps/details?hl=en&id=com.locado.app&utm_source=chatgpt.com "Locado: Smart Location Tasks - Apps on Google Play"
[8]: https://play.google.com/store/apps/details?id=com.smtech.shopcue&utm_source=chatgpt.com "SpotCue: Store Reminder List - Apps on Google Play"
[9]: https://support.apple.com/en-gb/109064?utm_source=chatgpt.com "Use Reminders on your iPhone, iPad or iPod touch with iOS 12 – Apple Support (UK)"
[10]: https://developer.android.com/reference/android/media/AudioAttributes?utm_source=chatgpt.com "AudioAttributes  |  API reference  |  Android Developers"
[11]: https://developer.android.com/develop/sensors-and-location/location/background?utm_source=chatgpt.com "Access location in the background  |  Sensors and location  |  Android Developers"
[12]: https://developer.android.com/about/versions/10/privacy/changes?utm_source=chatgpt.com "Privacy changes in Android 10  |  Android Developers"
[13]: https://source.android.com/docs/setup/reference/build-numbers?utm_source=chatgpt.com "Codenames, tags, and build numbers  |  Android Open Source Project"
