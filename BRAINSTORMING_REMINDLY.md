# 🚀 Remindly — Dossier de Présentation & Cadre de Brainstorming

> **Document de cadrage pour atelier d'idéation et de brainstorming d'équipe**  
> *Version du projet : Septembre 2026 — Statut : MVP Avancé & Fonctionnel (Android)*  
> *Auteur : Équipe Remindly*

---

## 📋 Table des Matières
1. [🎯 Vision Produit & Problématique Résolue](#1--vision-produit--problématique-résolue)
2. [💡 Le Concept : Le Rappel par Intention Contextuelle](#2--le-concept--le-rappel-par-intention-contextuelle)
3. [✨ Fonctionnalités Actuellement Implémentées](#3--fonctionnalités-actuellement-implémentées)
4. [🧠 Architecture & Intelligence sous le Capot](#4--architecture--intelligence-sous-le-capot)
5. [💎 Positionnement Face au Marché & Valeur Ajoutée](#5--positionnement-face-au-marché--valeur-ajoutée)
6. [👥 Deux Produits en Un : Remindly Personal (B2C) & Remindly Teams (B2B)](#6--deux-produits-en-un--remindly-personal-b2c--remindly-teams-b2b)
7. [🛠️ Fiche Technique, Stack & Conformité Android 16](#7--fiche-technique-stack--conformité-android-16)
8. [🧩 Atelier de Brainstorming : Pistes d'Innovation & Roadmap Priorisée](#8--atelier-de-brainstorming--pistes-dinnovation--roadmap-priorisée)

---

## 1. 🎯 Vision Produit & Problématique Résolue

### Le Constat : La Faillite des Applications de Rappel Traditionnelles
Les outils actuels de gestion des tâches (Google Keep, Apple Rappels, Todoist, Notion, TickTick) reposent sur un paradigme vieux de 25 ans : **l'heure et la date**.

* ❌ **La rigidité temporelle** : Programmer un rappel à 18h30 échoue systématiquement dès qu'une réunion s'éternise, qu'un embouteillage survient ou que vous quittez votre bureau plus tôt. Le rappel sonne au mauvais moment (quand vous conduisez ou êtes occupé) et est ignoré.
* ❌ **L'adresse unique et statique** : Les rappels géolocalisés basiques exigent une adresse exacte (*"Mon Carrefour habituel"*). Or, si vous devez acheter du pain, du carburant ou des médicaments, **vous ignorez à l'avance quelle rue vous emprunterez ni quel commerce sera sur votre chemin**.
* ❌ **La notification invisible** : Un discret son textuel (*"ding"*) ou une notification visuelle est totalement inefficace lorsque vous conduisez, marchez avec un casque ou avez votre smartphone dans une poche ou un sac.

### La Vision Remindly
> **« Vos rappels ne dépendent plus d'une heure. Ils dépendent de votre situation dans le monde réel. »**

Remindly transforme une liste de courses ou de tâches en un **réseau d'opportunités géographiques**. Vous exprimez une intention, et votre smartphone veille en arrière-plan avec une consommation minimale, calcule votre vitesse, votre cap et votre mode de déplacement, et **vous alerte vocalement** au moment exact où une opportunité se présente devant vous.

---

## 2. 💡 Le Concept : Le Rappel par Intention Contextuelle

Remindly introduit la notion de **rappel opportuniste et dynamique** :

```text
       ┌────────────────────────────────────────────────────────┐
       │             INTENTION EXPRIMÉE PAR L'UTILISATEUR       │
       │     "Rappelle-moi d'acheter du sérum physiologique     │
       │           à la première pharmacie sur ma route"        │
       └───────────────────────────┬────────────────────────────┘
                                   │
                                   ▼
       ┌────────────────────────────────────────────────────────┐
       │              MOTEUR CONTEXTUEL REMINDLY                │
       │  • Scan dynamique OSM (OpenStreetMap) + Google Places  │
       │  • Détection du mode de transport (Véhicule / Marche) │
       │  • Calcul de la trajectoire, de la vitesse et du cap   │
       │  • Historique court de distance (tendance Δd / Δt)     │
       │  • Scoring de pertinence en temps réel (0 à 100 pts)   │
       └───────────────────────────┬────────────────────────────┘
                                   │
                    Condition validée à l'approche
                                   │
                                   ▼
       ┌────────────────────────────────────────────────────────┐
       │            DÉCLENCHEMENT VOCAL PRIORITAIRE             │
       │  🗣️ "Rappel à proximité de la Pharmacie Centrale      │
       │      (à 250 mètres) : Acheter sérum physiologique"     │
       │  🎙️ Diffusion de la note vocale enregistrée            │
       └────────────────────────────────────────────────────────┘
```

---

## 3. ✨ Fonctionnalités Actuellement Implémentées

### 📍 1. Les 3 Modes Géographiques
1. **Catégorie Autour de Moi (Grappe Dynamique & Fenêtre Glissante)** :
   * L'utilisateur choisit un type de lieu (Pharmacie, Supérette, Station-service, Boulangerie, Banque, etc.).
   * L'application interroge le maillage local pour armer une grappe ciblée de points d'intérêt (POIs) autour de lui.
   * **Fenêtre Glissante (Rolling Geofence de 900 m)** : Dès que l'utilisateur parcourt plus de 900 mètres, l'application ré-échantillonne automatiquement les commerces situés dans son nouveau périmètre.
2. **Catégorie le long d'un Trajet Habituel (Commute Aller-Retour)** :
   * Enregistrement de l'itinéraire Domicile ➔ Travail (polyline vectorielle réelle).
   * Mode intelligent *"Au retour"* : Remindly reste en veille passive durant la journée de travail et n'arme les commerces que lors du trajet du retour vers le domicile.
3. **Lieu Fixe Délibéré** :
   * Sélection sur carte interactive ou recherche d'adresse avec rayon personnalisé (de 100m à 1000m+).

### 🗣️ 2. Restitution Vocale & Mains-Libres Intégral
* **Synthèse Vocale TTS (Text-to-Speech)** : L'application énonce le nom du commerce détecté, la distance et le texte du rappel sans manipulation de l'écran.
* **Note Vocale Enregistrée** : Possibilité de joindre un mémo vocal joué à l'arrivée.
* **Canal Audio Prioritaire (`USAGE_ALARM`)** : Permet de diffuser l'alerte sur le flux d'alarme du système et d'atténuer temporairement la musique Bluetooth dans l'habitacle.

### 🔄 3. Cycle de Vie Intelligent & Anti-Harcèlement
* **Transition Automatique vers `COMPLETED`** : Dès qu'un rappel à passage unique sonne (lieu fixe ou première pharmacie), il passe au statut terminé et désarme automatiquement toutes les autres zones pour ne pas re-sonner aux magasins suivants.
* **Mode Habitude / Répétitif** : Pour les routines quotidiennes, le rappel reste actif et se réarme automatiquement à la sortie de la zone.
* **Annulation Mutuelle (Mutual Cancellation)** : Si une tâche a une échéance horaire (ex: 18h) et un lieu (ex: bureau de poste), arriver au bureau de poste avant 18h valide la tâche et supprime automatiquement l'alarme horaire.
* **Cooldown Anti-Rebond** : Filtre temporel pour éviter les alertes en double si plusieurs commerces de la même enseigne se jouxtent.

### 👥 4. Collaboration & Espaces Partagés (Cloud Firebase)
* Création de cercles de partage (Famille, Équipe, Collaborateurs).
* Attribution de rappels géolocalisés à d'autres membres : *"Quand mon collègue arrive sur le chantier X, rappelle-lui de récupérer la clé"*.
* Architecture **Offline-First** (Room locale) synchronisée en temps réel avec Firestore.

---

## 4. 🧠 Architecture & Intelligence sous le Capot

### 🔋 Gestion Énergétique à 3 Niveaux & Extinction Automatique
Pour concilier précision métrique en conduite et autonomie de la batterie sur la journée :

| Niveau | Composant Actif | Précision | Consommation Batterie | Déclenchement |
| :--- | :--- | :--- | :---: | :--- |
| **Niveau 1 : Veille Passive** | Hardware Geofencing (GMS) | 100m - 500m | **Frugale (Puces GSM / Wi-Fi)** | 90% du temps (à l'arrêt, au bureau, à domicile) |
| **Niveau 2 : Pulse Conduite** | GPS Haute Précision (15s) | 5m - 15m | **Modérée et ciblée** | Activé uniquement si vitesse $\ge 25\text{ km/h}$ ou `IN_VEHICLE` |
| **Niveau 3 : Suivi Intensif 5s** | Diagnostic live de zone | 3m - 8m | **Temporaire (2 min max)** | Uniquement lors de l'approche immédiate d'un POI ciblé |
| **Extinction Intelligente** | Coupure automatique | - | **0 % GPS** | Extinction immédiate dès que tous les rappels sont complétés ou après 2 min à l'arrêt ($< 5\text{ km/h}$) |

### 🧭 Le Moteur de Pertinence Contextuelle (`ContextRelevanceEngine`)
Pour éviter les fausses alertes à chaque croisement, un algorithme de scoring (0 à 100 points) évalue en direct :
1. **La distance absolue** : Bonus exponentiel si $< 150\text{ m}$.
2. **La tendance de distance ($\Delta d / \Delta t$)** : Analyse de l'historique court pour valider que le véhicule se rapproche réellement du POI (distance décroissante) et n'est pas en train de s'en éloigner.
3. **Le vecteur de déplacement (Cap / Bearing)** : Si le véhicule tourne le dos au POI, une pénalité de score est appliquée.
4. **Le mode de transport (Activity Recognition)** : Adaptation du seuil selon l'activité physique (Voiture, Vélo, Marche, Immobile).
5. **La décision finale** :
   * Score $\ge 70$ : `FULL_ALARM` (Alarme sonore + TTS prioritaire).
   * Score $45 - 69$ : `DISCREET_NOTIF` (Notification visuelle + discret rappel audio).
   * Score $< 45$ : `SUPPRESS` (Filtre silencieux, rejet de l'alerte).

### 🎯 Gestionnaire de Quota de Géofences (`Geofence Allocation Manager`)
Android limite chaque application à **100 géofences matérielles simultanées**. Pour garantir une robustesse absolue sans jamais saturer ce quota :
* Un pool de **80 géofences actives maximum** est partagé dynamiquement entre les rappels en cours.
* Si 1 rappel de catégorie est actif : jusqu'à 60 POIs sont armés.
* Si 2 ou 3 rappels sont actifs : le gestionnaire alloue un sous-quota (ex: 25-35 slots par rappel) en sélectionnant en priorité les POIs **les plus proches et les mieux alignés sur la trajectoire**.

### 🗺️ Découverte POI & Stratégie de Mise en Cache
* **Double Source Cartographique** : OpenStreetMap Overpass (couverture exhaustive des commerces de quartier) et Google Places API (noms officiels et catégorisation).
* **Mise en cache locale SQLite (Room)** : Les POIs découverts par zone sont stockés localement avec une durée de validité (TTL) pour soulager la bande passante, éviter les limites de requêtes Overpass et fonctionner en zone blanche.

---

## 5. 💎 Positionnement Face au Marché & Valeur Ajoutée

Il existe aujourd'hui des applications de rappel basées sur la position (Apple Rappels, Google Keep, Any.do) et quelques applications explorant les rappels par catégorie (GeoToDo, Locado, SpotCue). 

**La véritable valeur ajoutée de Remindly réside dans la cohérence de sa chaîne de décision complète :**

| Critère / Fonctionnalité | **Remindly** | **Apps Catégories (Locado, GeoToDo)** | **Généralistes (Keep, Apple Rappels)** |
| :--- | :---: | :---: | :---: |
| **Rappels par Catégorie de Commerce** | ✅ **Oui (Toute pharmacie, supérette...)** | ✅ Oui | ❌ Non (adresses fixes uniquement) |
| **Fenêtre Glissante en Déplacement** | ✅ **Oui (Scan dynamique adaptatif)** | ⚠️ Rarement dynamique | ❌ Non |
| **Trajet Habite Domicile ➔ Travail** | ✅ **Oui (Polyline vectorielle réelle)** | ❌ Non | ❌ Non |
| **Scoring Vectoriel (Vitesse + Cap + Tendance)** | ✅ **Oui (`ContextEngine`)** | ❌ Non (Distance pure) | ❌ Non |
| **Annonce Vocale Mains-Libres (TTS)** | ✅ **Oui (Nom du POI + texte)** | ⚠️ Partiel | ❌ Notification texte simple |
| **Canal Audio Prioritaire (`USAGE_ALARM`)** | ✅ **Oui** | ❌ Non | ❌ Non |
| **Extinction Automatique Énergétique** | ✅ **Oui (Coupure immédiate à l'arrêt)** | ⚠️ Variable | ⚠️ Standard Android |
| **Architecture Local-First / Données Souveraines** | ✅ **Oui (Zéro profilage de trajets)** | ❌ Souvent Cloud dépendant | ⚠️ Cloud propriétaire |

---

## 6. 👥 Deux Produits en Un : Remindly Personal (B2C) & Remindly Teams (B2B)

Plutôt que d'essayer de plaire à tout le monde dans une interface unique, l'architecture de Remindly permet de décliner deux propositions de valeur nettes :

```text
                          ┌───────────────────────────┐
                          │   MOTEUR CONTEXTUEL CORE  │
                          │ (ContextEngine + Geofence)│
                          └─────────────┬─────────────┘
                                        │
                 ┌──────────────────────┴──────────────────────┐
                 ▼                                             ▼
     [ REMINDLY PERSONAL (B2C) ]                  [ REMINDLY TEAMS (B2B) ]
  • "Acheter du pain en rentrant"             • "Faire signer le bon de livraison"
  • "Pharmacie sur la route"                  • "Vérifier le disjoncteur du chantier"
  • Quick Capture vocale en 2 secondes        • Preuve de passage & Horodatage
  • Gratuit / Abonnement Premium              • Console Web Manager + Flottes de terrain
```

### 1. Remindly Personal (B2C)
* **La promesse** : *"La fin de la charge mentale en voiture ou en déplacement."*
* **Cas d'usage** : Courses, ordonnance médicale, colis à récupérer, pressing.
* **Facteur clé de succès** : **La création en 2 secondes (Quick Capture)**. Si créer un rappel prend plus de 5 secondes, l'utilisateur abandonne.

### 2. Remindly Teams (B2B / Dispatch de Tâches de Terrain)
* **La promesse** : *"La bonne consigne au technicien ou livreur, au moment exact où il franchit la porte du site."*
* **Cas d'usage** : 
  * Chauffeurs-livreurs : consignes de sécurité spécifiques par client.
  * BTP & Maintenance : listes de contrôle et récupération de matériel sur chantier.
  * Soignants itinérants : instructions médicales avant d'entrer chez le patient.
* **Facteur clé de succès** : Tableau de bord web pour le responsable d'équipe et horodatage certifié de la validation sur le terrain.

---

## 7. 🛠️ Fiche Technique, Stack & Conformité Android 16

* **Plateforme & Cible** : Android natif (SDK minimum : 26 / **Target SDK : 36 - Android 16**).
* **Langage & UI** : 100% Kotlin, Jetpack Compose, Material 3, Dark Mode réactif.
* **Architecture** : Clean Architecture, MVVM, StateFlow, Coroutines, Dagger Hilt.
* **Stockage Local** : SQLite Room Database (chiffrement & persistence hors-ligne).
* **Synchronisation Cloud** : Firebase Cloud Firestore (optionnel, pour espaces partagés).
* **Localisation & Capteurs** : Google Play Services Location (Geofencing API, FusedLocationProviderClient), Google Activity Recognition Transition API.
* **Moteurs Cartographiques** : OpenStreetMap Overpass API + Google Places SDK / Google Directions API.
* **Audio** : Android `TextToSpeech`, Android `MediaPlayer`, `AudioAttributes.USAGE_ALARM`.
* **Confidentialité (Local-First)** : Aucun historique de localisation ni trajet n'est stocké ou reconstitué sur un serveur distant. Les calculs contextuels s'exécutent entièrement en local sur le terminal.

---

## 8. 🧩 Atelier de Brainstorming : Pistes d'Innovation & Roadmap Priorisée

Pour canaliser l'énergie de l'équipe et éviter le piège de la dispersion ("vouloir tout développer à la fois"), la feuille de route est ordonnée par ordre d'impact réel :

```text
  [ PHASE 1 : FIABILITÉ NOYAU ] ➔ [ PHASE 2 : QUICK CAPTURE ] ➔ [ PHASE 3 : EXPANSION B2B ]
  • Quota 100 Géofences           • Création vocale en 2 sec    • Console Web Dispatcher
  • Cache SQLite POIs             • Saisie express en 2 clics   • Preuve de présence / Horodatage
  • Calibrage réel (logs JSON)    • Widget écran d'accueil      • Rôles Gestionnaire / Technicien
```

---

### 🛡️ Phase 1 : Fiabilité Absolue du Noyau (Priorité Immédiate)
* **Geofence Budget Manager** : Empêcher tout débordement des 100 géofences avec répartition dynamique et priorisation spatiale.
* **Cache Local Room pour les POIs** : Réduire la dépendance réseau envers Overpass et accélérer l'affichage instantané.
* **Calibrage fin via les logs réels** : Ajustement des seuils de scoring (70 / 45) à partir des tests en conditions réelles de conduite et de marche.

> ❓ **Question pour l'équipe** : *Quels scénarios de terrain (tunnel, métro, parking sous-sol, double commerce) doivent faire l'objet de notre prochain protocole de test ?*

---

### ⚡ Phase 2 : Quick Capture — La création en 2 secondes (Expérience Utilisateur)
* **Saisie vocale instantanée** : Appui long ou bouton rapide ➔ énonciation de l'intention (*"Rappelle-moi le pain"*).
* **Catégorisation assistée** : Détection automatique des mots-clés courants ("pain" ➔ Boulangerie, "aspirine" ➔ Pharmacie, "lait" ➔ Supérette).
* **Widget d'accès direct sur l'écran d'accueil**.

> ❓ **Question pour l'équipe** : *Comment concevoir l'écran de création pour qu'un utilisateur n'ait besoin de toucher l'écran qu'une seule fois ?*

---

### 💼 Phase 3 : Déclinaison B2B (Remindly Teams)
* **Interface Web pour Gestionnaire** : Envoi de consignes géolocalisées aux smartphones des agents de terrain.
* **Accusé de passage certifié** : Validation automatique ou par bouton lorsque l'agent réalise l'action sur site.

> ❓ **Question pour l'équipe** : *Quel secteur professionnel (artisans, logistique du dernier kilomètre, techniciens fibre/électricité) constitue notre meilleur point d'entrée pour un pilote commercial ?*

---

### ❄️ Fonctionnalités Volontairement Gelées (À différer après la V1)
Pour garantir la livraison d'un produit irréprochable, les pistes suivantes sont **mises en réserve pour la V2** :
* Intégrations lourdes tierces (Salesforce, HubSpot, Slack).
* Modèles LLM génératifs lourds on-device ou lecture automatisée de la boîte mail.
* Applications natives pour montres connectées et Apple CarPlay.

---

## 📝 Fiche d'Atelier : Déroulé Suggéré de la Réunion (1h30)

| Phase | Durée | Objectif |
| :--- | :---: | :--- |
| **1. Démo Live & Présentation** | 15 min | Démonstration du parcours utilisateur sur l'APK (création d'un rappel, déclenchement GPS, synthèse vocale). |
| **2. Brainstorming Silencieux (Post-its)** | 20 min | Chaque membre note ses idées sur les 3 phases prioritaires (Fiabilité, Quick Capture, B2B Teams). |
| **3. Regroupement & Pitchs Éclair** | 25 min | Regroupement thématique au tableau et élimination des fausses bonnes idées (scope creep). |
| **4. Matrice Valeur / Faisabilité** | 20 min | Positionnement des fonctionnalités sur une matrice (Impact utilisateur vs Effort technique). |
| **5. Décision & Roadmap du Sprint** | 10 min | Sélection des 2 chantiers prioritaires pour le prochain cycle de développement. |

---

*Document de cadrage stratégique Remindly. Tous droits réservés.*
