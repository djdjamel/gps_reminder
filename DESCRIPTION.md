# 📍 Remindly — Rappels GPS Géolocalisés Intelligents & Alarmes Vocales Contextuelles

> **La première application de rappels qui sait *où* vous êtes, *où vous allez*, et vous prévient à voix haute au moment opportun — même en mode silencieux.**

---

## 📖 Sommaire
1. [🎯 Vision & Problématique Résolue](#-1-vision--problématique-résolue)
2. [✨ Fonctionnalités Majeures](#-2-fonctionnalités-majeures)
3. [🧠 Comment ça fonctionne sous le capot (Architecture & Technologie)](#-3-comment-ça-fonctionne-sous-le-capot)
4. [💎 Valeur Ajoutée & Comparatif sur le Marché](#-4-valeur-ajoutée--comparatif-sur-le-marché)
5. [🌟 Scénarios Réels où l'Application Brille ("Where It Shines")](#-5-scénarios-réels-où-lapplication-brille)
6. [🛠️ Fiche Technique](#-6-fiche-technique)

---

## 🎯 1. Vision & Problématique Résolue

### Le Problème des Applications Actuelles (Google Keep, Apple Rappels, Todoist, etc.)
La quasi-totalité des gestionnaires de tâches et de rappels souffrent de **deux défauts majeurs** dans la vie quotidienne :

1. **La rigidité temporelle (Heure fixe)** : Programmer un rappel à 18h30 pour *"Acheter du pain"* ou *"Prendre des médicaments"* échoue dès lors que vous terminez plus tôt, que vous êtes coincé dans les bouchons ou que votre planning change.
2. **La rigidité géographique (Un seul point fixe)** : Les rares applications gérant la géolocalisation exigent de pointer **une adresse unique et statique**. Si vous avez besoin d'une supérette ou d'une pharmacie, vous ne savez pas forcément à l'avance par quelle rue vous passerez ni quel magasin sera sur votre trajet.
3. **Le manque d'impact des notifications** : Un simple *"ding"* textuel discret est invisible en voiture, inaudible dans la poche ou complètement bloqué par le mode "Ne pas déranger".

### La Solution Remindly
**Remindly révolutionne le rappel contextuel.** Vous ne définissez plus un horaire contraignant, mais une **intention contextuelle** :
- *"Rappelle-moi d'acheter du lait dès que je passe devant **n'importe quelle supérette**."*
- *"Rappelle-moi cette ordonnance à la **première pharmacie** sur mon trajet de retour."*
- À l'approche du lieu, l'application prend le relais : elle vous **annonce à voix haute le nom du magasin** et **joue votre note vocale** avec la puissance d'une alarme réveil, sans que vous ayez à toucher votre téléphone.

---

## ✨ 2. Fonctionnalités Majeures

### 📍 A. Les 3 Modes Géographiques Intelligents

| Mode | Description | Idéal pour... |
| :--- | :--- | :--- |
| **1. Lieu Fixe sur Carte** | Définition d'un point géographique précis avec rayon configurable (de 150m à 1000m). | Mécanicien précis, maison d'un ami, bureau, dépôt. |
| **2. Catégorie Autour de Moi** | Détection automatique de **20 à 30 commerces réels** de la catégorie choisie (Pharmacies, Supérettes, Boulangeries, Stations-service, etc.) avec **Fenêtre Glissante (Rolling Geofence)** qui s'actualise au fur et à mesure de vos déplacements. | Courses quotidiennes, besoins imprévus en ville. |
| **3. Catégorie sur Trajet Habituel (Commute)** | Analyse de votre itinéraire Domicile ➔ Travail. Gestion intelligente du trajet aller, étape intermédiaire à destination, et réarmement automatique des commerces sur le chemin du retour. | Le trajet quotidien travail-maison. |

---

### 🗣️ B. Synthèse Vocale (TTS) & Alarmes Audio Prioritaires

- **🗣️ Annonce Vocale du Nom du Lieu (TTS)** :
  - La synthèse vocale native Android annonce à voix haute le lieu exact détecté :  
    > *"Rappel à proximité de **Family Shop**"* ou *"Rappel : Acheter des piles, à proximité de **Pharmacie Centrale**"*.
- **🎙️ Lecture de Notes Vocales Personnelles** :
  - Enregistrez directement votre voix lors de la création du rappel. L'application diffuse votre propre voix à l'arrivée.
- **🚨 Canal `USAGE_ALARM` Prioritaire** :
  - Utilise le canal système des réveils/alarmes d'Android pour contourner les modes *Silencieux*, *Vibreur* et *Ne pas déranger*.
- **🎛️ Personnalisation Audio Poussée** :
  - Réglage indépendant du volume sonore (avec bouton de test instantané).
  - Répétition configurable (1x, 2x, 3x, 5x ou boucle continue).
  - Vibreur haute intensité synchronisé.

---

### 🔄 C. Déclenchements Multi-Commerces Persistants & Cooldown Anti-Rebond

- **Persistance Multi-Commerces** :
  - Si vous passez devant une première supérette sans vous arrêter, l'application **ne désactive pas le rappel**. Seule la géofence du magasin franchi est consommée ; tous les autres magasins équivalents restent armés pour la suite de votre route.
- **Délai Anti-Rebond (Cooldown)** :
  - Réglable de **5s à 90s** (défaut : **15s**). Empêche le téléphone de sonner de manière anarchique si 3 magasins sont alignés dans la même rue, tout en garantissant une réactivité immédiate dès que vous changez de pâté de maisons.
- **Désarmement intuitif** :
  - Un clic sur *"Terminer"* dans la notification ou dans l'application désarme instantanément toute la grappe de géofences associées.

---

### 🚀 D. Capture Rapide & Multimédia

- **Feuille de capture instantanée (Quick Capture)** : Enregistrement d'un rappel en 2 secondes chrono avec reconnaissance vocale, microphone audio ou texte.
- **Pièces jointes riches** : Enregistrements audio haute fidélité, photos et images attachées avec prévisualisation plein écran.
- **Mode Hors-Ligne Total (Offline-First)** : Base de données locale Room ultrarapide, fonctionnement GPS sans connexion réseau requise pour les déclenchements.
- **Synchronisation Cloud & Partage** : Synchronisation sécurisée Firebase / Firestore, authentification et listes partagées en temps réel.
- **Outils de Diagnostic & Logs** : Journal d'événements intégré avec export JSON pour analyser vos trajets et les déclenchements GPS.

---

## 🧠 3. Comment ça fonctionne sous le capot

```
   ┌────────────────────────────────────────────────────────┐
   │                  UTILISATEUR                           │
   │  "Rappelle-moi d'acheter du café dans une supérette"   │
   └───────────────────────────┬────────────────────────────┘
                               │
                               ▼
   ┌────────────────────────────────────────────────────────┐
   │            GEOFENCE MANAGER & POI DISCOVERY            │
   │  1. Récupération GPS de départ                         │
   │  2. Scan Google Places / Overpass : 27 supérettes      │
   │  3. Armement Grappe de Géofences (Rayon: 450m)         │
   │  4. Armement Fenêtre Glissante (Zone tampon: 2.5 km)   │
   └───────────────────────────┬────────────────────────────┘
                               │
            ┌──────────────────┴──────────────────┐
            ▼                                     ▼
┌───────────────────────────────┐   ┌───────────────────────────────┐
│       EN DÉPLACEMENT          │   │      APPROCHE D'UN COMMERCE   │
│  Sortie de la zone de 2.5 km  │   │  Entrée dans le rayon de 450m │
│  (Rolling Exit Transition)    │   │  (Geofence ENTER / DWELL)     │
└───────────────┬───────────────┘   └───────────────┬───────────────┘
                │                                   │
                ▼                                   ▼
┌───────────────────────────────┐   ┌───────────────────────────────┐
│   ACTUALISATION DYNAMIQUE     │   │      ALERTE INTELLIGENTE      │
│  - Libération ancienne grappe │   │  1. Vérification Cooldown 15s │
│  - Scan 20 nouveaux POIs      │   │  2. TTS : "À proximité de..." │
│  - Réarmement zone 2.5 km     │   │  3. AudioAlarmService (ALARM) │
│                               │   │  4. Maintien des autres POIs  │
└───────────────────────────────┘   └───────────────────────────────┘
```

### 1. Zéro Drainage de Batterie (Native Google Play Services)
Contrairement aux applications qui font tourner un service GPS en continu et vident la batterie en quelques heures, Remindly utilise l'API **Hardware Geofencing de Google Play Services**. Le modem cellulaire et le Wi-Fi gèrent la veille géographique ; les puces GPS haute précision ne sont réveillées qu'à l'entrée immédiate dans le périmètre.

### 2. Algorithme de Fenêtre Glissante (Rolling Exit Geofence)
Lors des trajets longue distance, l'application place une **géofence invisible de sortie de 2,5 km** autour de votre position. Lorsque vous roulez et franchissez ce seuil, l'application se réveille une fraction de seconde, désarme l'ancienne zone et télécharge les commerces situés devant vous.

### 3. Gestion Audio de Niveau Système (`AudioAlarmService`)
L'application crée un service de premier plan (*Foreground Service*) éphémère dédié à l'audio, demandant le focus audio exclusif `AUDIOFOCUS_GAIN_TRANSIENT`. La musique de votre autoradio ou de vos écouteurs se met automatiquement en pause, l'annonce vocale retentit avec clarté, puis la musique reprend son cours normal.

---

## 💎 4. Valeur Ajoutée & Comparatif sur le Marché

| Critère / Fonctionnalité | **Remindly** | **Google Keep / Tasks** | **Apple Rappels** | **Todoist / TickTick** |
| :--- | :---: | :---: | :---: | :---: |
| **Rappels par Catégorie de Commerce** | ✅ **Oui (Supérettes, Pharmacies, etc.)** | ❌ Non | ❌ Non | ❌ Non |
| **Fenêtre Glissante Dynamique en Voiture** | ✅ **Oui (Recalcul automatique)** | ❌ Non | ❌ Non | ❌ Non |
| **Multi-Commerces Persistants** | ✅ **Oui (Reste armé pour le magasin suivant)** | ❌ Non | ❌ Non | ❌ Non |
| **Annonce Vocale TTS du Lieu Détecté** | ✅ **Oui ("À proximité de...")** | ❌ Non | ❌ Non | ❌ Non |
| **Alarme Prioritaire (Bypass Ne pas déranger)** | ✅ **Oui (USAGE_ALARM)** | ❌ Non (Simple notif) | ❌ Non | ❌ Non |
| **Enregistrement Vocal Diffusé à l'Arrivée** | ✅ **Oui** | ❌ Non | ❌ Non | ❌ Non |
| **Trajet Habituel (Aller-Retour travail)** | ✅ **Oui (Étape intermédiaire)** | ❌ Non | ❌ Non | ❌ Non |
| **Consommation Batterie Optimisée** | ✅ **Excellente (Geofencing Hardware)** | ⚠️ Moyenne | ⚠️ Moyenne | ⚠️ Moyenne |

---

## 🌟 5. Scénarios Réels où l'Application Brille

### 🚗 Scénario 1 : Le trajet du travail et les courses imprévues
> **Situation** : Votre conjoint(e) vous demande d'acheter des couches et de l'eau en rentrant du travail. Vous quittez votre bureau à 17h15 au lieu de 18h00 et vous prenez un itinéraire bis pour éviter les embouteillages.  
> **Comportement classique** : Un rappel à 18h sonne alors que vous êtes déjà rentré à la maison.  
> **Avec Remindly** : Dès que votre voiture passe à moins de 450m d'une supérette sur votre itinéraire bis, votre autoradio se met en pause et une voix claire annonce : *"Rappel : Acheter des couches et de l'eau, à proximité de Family Shop"*. Vous vous garez et faites vos courses sans aucun oubli.

---

### 💊 Scénario 2 : L'ordonnance médicale urgente dans une ville inconnue
> **Situation** : Vous êtes en déplacement professionnel ou en vacances dans une autre ville. Vous avez un médicament urgent à récupérer.  
> **Comportement classique** : Vous devez chercher manuellement sur Google Maps, mémoriser l'adresse et planifier un trajet.  
> **Avec Remindly** : Vous créez un rappel vocal *"Pharmacie"*. Pendant que vous roulez vers votre hôtel, l'application surveille l'environnement via la fenêtre glissante. Dès que vous approchez d'une officine ouverte, l'application vous alerte avec le nom de la pharmacie.

---

### 🛠️ Scénario 3 : Le bricoleur ou l'artisan en tournée
> **Situation** : Un artisan a besoin de vis spécifiques chez un fournisseur de matériaux dès qu'il passera à proximité d'une quincaillerie lors de ses multiples interventions de la journée.  
> **Avec Remindly** : Il enregistre une note vocale de 5 secondes. Devant la première quincaillerie, le rappel sonne. S'il n'a pas le temps de s'arrêter car il est en retard chez un client, le rappel **reste actif** et ré-alertera automatiquement à la prochaine quincaillerie croisée plus tard dans l'après-midi.

---

### 🏃 Scénario 4 : Le piéton distrait avec écouteurs
> **Situation** : Vous marchez en ville en écoutant un podcast ou de la musique. Vous deviez déposer un courrier à La Poste.  
> **Comportement classique** : Vous passez devant la boîte aux lettres sans la voir car votre téléphone est dans votre poche en mode silencieux.  
> **Avec Remindly** : L'alerte vocale coupe momentanément votre musique et diffuse votre propre rappel audio directement dans vos écouteurs, vous évitant un demi-tour de 20 minutes.

---

## 🛠️ 6. Fiche Technique

- **Plateforme** : Android (SDK 26 à 35+ / Android 8.0 à Android 15)
- **Langage & UI** : Kotlin 100%, Jetpack Compose, Material 3, Dark Mode natif.
- **Architecture** : MVVM + Clean Architecture, Coroutines & Flow, Dagger Hilt.
- **Persistance locale** : Room Database (SQLite), Jetpack DataStore Preferences.
- **Services Géographiques** : Google Play Services Location (Geofencing API), Google Places API / Overpass OSM.
- **Moteur Audio & Voix** : Android native `TextToSpeech`, `MediaPlayer`, `AudioAttributes.USAGE_ALARM`.
- **Synchronisation Cloud** : Firebase Authentication, Cloud Firestore.
- **Sécurité & Confidentialité** : Traitement local offline-first, pas de revente de données de localisation.

---

*Remindly — Ne pensez plus à vos rappels, laissez vos lieux vous les rappeler.*
