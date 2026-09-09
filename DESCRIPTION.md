# 📍 Remindly — L'Assistant Contextuel de Rappels Géolocalisés & Vocaux

> **« Vos rappels ne dépendent plus de l'heure. Ils dépendent de votre situation. »**  
> *Ne programmez plus d'horaires rigides : dites simplement ce que vous voulez accomplir, et laissez vos lieux vous le rappeler à voix haute au moment opportun.*

---

## 📖 Sommaire
1. [🎯 Vision & Proposition de Valeur Unique](#-1-vision--proposition-de-valeur-unique)
2. [✨ Fonctionnalités Clés : Le Rappel par Intention](#-2-fonctionnalités-clés--le-rappel-par-intention)
3. [🧠 Sous le Capot : Une Technologie Invisible et Économe](#-3-sous-le-capot--une-technologie-invisible-et-économe)
4. [💎 Comparatif sur le Marché : Pourquoi Remindly est Unique](#-4-comparatif-sur-le-marché-pourquoi-remindly-est-unique)
5. [🌟 Scénarios Réels : Où Remindly Fait la Différence](#-5-scénarios-réels--où-remindly-fait-la-différence)
6. [💼 Potentiel Professionnel & Mobilité (B2B / Métiers Itinérants)](#-6-potentiel-professionnel--mobilité-b2b--métiers-itinérants)
7. [🛠️ Fiche Technique & Confidentialité](#-7-fiche-technique--confidentialité)

---

## 🎯 1. Vision & Proposition de Valeur Unique

### Le Problème des Rappels Traditionnels
Les applications classiques (Google Keep, Apple Rappels, Todoist) reposent sur un modèle dépassé :
- ⏰ **La rigidité temporelle** : Programmer un rappel à 18h30 échoue dès que votre journée est décalée, que vous êtes bloqué dans les transports ou que vous partez plus tôt.
- 📍 **L'adresse unique et statique** : Les rappels GPS classiques exigent de désigner une adresse exacte (ex: *Mon supermarché habituel*). Mais si vous devez acheter du pain, une ordonnance ou du carburant, vous ne savez pas quelle rue vous emprunterez ni quel commerce sera sur votre route.
- 🔕 **Les notifications ignorées** : Un discret *"ding"* textuel est invisible en voiture, inaudible dans une poche ou étouffé par le mode silencieux.

### La Solution Remindly : Le Lieu comme une Condition
Remindly transforme le rappel en **intention contextuelle** :
- *"Rappelle-moi de prendre du lait dès que je passe devant **n'importe quelle supérette**."*
- *"Rappelle-moi ce dossier dès que j'arrive à proximité du **bureau de Karim**."*
- *"Rappelle-moi cette ordonnance à la **première pharmacie sur mon trajet de retour**."*

À l'approche du lieu, **l'application vous parle** : elle annonce le nom exact du commerce détecté (*"Rappel à proximité de Family Shop"*) et diffuse votre propre note vocale avec la puissance d'une alarme prioritaire.

---

## ✨ 2. Fonctionnalités Clés : Le Rappel par Intention

### 📍 1. Les 3 Modes Géographiques Contextuels
1. **Catégorie Autour de Moi (Dynamique)** :
   - Découverte automatique de 20 à 30 commerces de la catégorie choisie (Pharmacies, Supérettes, Boulangeries, Stations, etc.).
   - **Fenêtre Glissante (Rolling Geofence)** : Lorsque vous roulez et parcourez plus de 2,5 km, l'application actualise automatiquement les commerces situés devant vous.
2. **Catégorie sur Trajet Habituel (Commute Aller-Retour)** :
   - Analyse de votre axe Domicile ➔ Travail le long de la polyline routière réelle.
   - Mode intelligent *"Au retour"* : veille pendant votre journée de travail et armement automatique des commerces sur le chemin du retour vers la maison.
3. **Lieu Fixe Haute Précision** :
   - Définition d'un point géographique précis avec rayon personnalisable (de 150m pour les piétons à 450m+ pour les automobilistes).

---

### 🔄 2. Persistance Multi-Commerces & Cooldown Anti-Rebond
- **Le rappel ne s'éteint pas tout seul** : Si vous passez devant une première supérette sans pouvoir vous arrêter, le rappel reste actif pour les supérettes suivantes tout au long de votre trajet.
- **Filtre Anti-Rebond Intelligent (Cooldown réglable de 5s à 90s, défaut 15s)** : Empêche les alarmes intempestives lorsque plusieurs commerces sont côte à côte, tout en ré-alertant immédiatement dès que vous changez de quartier.
- **File d'Attente Séquentielle** : Si deux rappels distincts (ex: Pharmacie + Supérette) se déclenchent au même carrefour, ils sont énoncés l'un après l'autre de manière claire avec une pause de respiration.

---

### 🗣️ 3. Synthèse Vocale TTS & Alarmes Audio Prioritaires
- **🗣️ Annonce Vocale TTS des Lieux** : Prononce le nom du commerce ou lit le texte du rappel sans que vous ayez à regarder l'écran.
- **🎙️ Lecture de Notes Vocales Personnelles** : Joue directement votre propre voix enregistrée lors de la création du rappel.
- **🚨 Canal Audio Prioritaire (`USAGE_ALARM`)** : Permet au rappel d'être parfaitement audible en voiture ou en mobilité, y compris si le téléphone est en mode silencieux / Ne pas déranger.
- **🎛️ Personnalisation Complète** : Volume dédié avec test en direct, répétition (1x, 2x, 3x, 5x ou boucle), et vibrations synchronisées.

---

### ⚡ 4. Quick Capture en 2 Secondes
- Interface de saisie ultra-rapide pour capturer une idée en sortant d'une réunion ou en montant dans sa voiture.
- Support des pièces jointes multimédias (photos, notes vocales) avec fonctionnement **100% hors-ligne (Offline-First)**.

---

## 🧠 3. Sous le Capot : Une Technologie Invisible et Économe

### 🔋 Consommation Batterie Ultra-Optimisée (Hardware Geofencing)
Contrairement aux applications qui maintiennent un tracking GPS actif en continu (drainage rapide de la batterie), Remindly s'appuie sur le **Hardware Geofencing de Google Play Services**. La veille géographique est déléguée aux puces réseau à très basse consommation (cellulaire / Wi-Fi) ; le GPS haute précision n'est sollicité qu'à l'entrée immédiate dans le périmètre du lieu.

### 🚗 Gestion Intelligente des Trajets Routiers
En mode trajet habituel, la recherche de points d'intérêt ne se fait pas à vol d'oiseau, mais est échantillonnée le long de la **polyline routière réelle** pour garantir que les commerces proposés sont directement accessibles sur votre voie de circulation.

---

## 💎 4. Comparatif sur le Marché : Pourquoi Remindly est Unique

| Critère / Fonctionnalité | **Remindly** | **Google Keep / Tasks** | **Apple Rappels** | **Todoist / TickTick** |
| :--- | :---: | :---: | :---: | :---: |
| **Condition par Catégorie de Commerce** | ✅ **Oui (Supérette, Pharmacie, etc.)** | ❌ Non | ❌ Non | ❌ Non |
| **Fenêtre Glissante Dynamique en Déplacement** | ✅ **Oui (Mise à jour en continu)** | ❌ Non | ❌ Non | ❌ Non |
| **Persistance Multi-Commerces** | ✅ **Oui (Reste actif pour le magasin suivant)** | ❌ Non | ❌ Non | ❌ Non |
| **Annonce Vocale TTS du Commerce Détecté** | ✅ **Oui (Mains libres complet)** | ❌ Non | ❌ Non | ❌ Non |
| **Diffusion de la Note Vocale à l'Arrivée** | ✅ **Oui (Sa propre voix)** | ❌ Non | ❌ Non | ❌ Non |
| **Alarme Prioritaire (Canal Réveil)** | ✅ **Oui (USAGE_ALARM)** | ❌ Non (Simple notif) | ❌ Non | ❌ Non |
| **Gestion Trajet Retour Travail ➔ Domicile** | ✅ **Oui (Étape intermédiaire)** | ❌ Non | ❌ Non | ❌ Non |
| **Fonctionnement Hors-Ligne Total** | ✅ **Oui (Base locale Room)** | ⚠️ Partiel | ⚠️ Partiel | ⚠️ Partiel |

---

## 🌟 5. Scénarios Réels : Où Remindly Fait la Différence

### 🚗 Scénario 1 : Le retour du travail et les courses du quotidien
> **Situation** : Vous devez acheter des couches et du café en rentrant du travail. Vous quittez votre bureau à 17h15 au lieu de 18h00 et empruntez un itinéraire bis pour éviter les bouchons.  
> **Résultat avec Remindly** : Dès que votre voiture passe à proximité d'une supérette sur cet itinéraire bis, la musique se met en pause et l'application annonce : *"Rappel : Acheter des couches et du café, à proximité de Family Shop"*. Vous vous arrêtez sans aucun détour.

---

### 💊 Scénario 2 : L'ordonnance médicale urgente dans une ville inconnue
> **Situation** : Vous êtes en déplacement professionnel dans une agglomération que vous ne connaissez pas. Vous avez une ordonnance urgente à déposer.  
> **Résultat avec Remindly** : Vous créez un rappel *"Pharmacie"*. En roulant vers votre hôtel, la fenêtre glissante scanne votre environnement. Dès que vous approchez d'une officine, vous êtes prévenu vocalement du nom de la pharmacie.

---

### 🏃 Scénario 3 : Le piéton distrait avec écouteurs
> **Situation** : Vous marchez en ville en écoutant un podcast. Vous deviez poster un courrier important.  
> **Résultat avec Remindly** : À l'approche de la boîte aux lettres, l'alerte vocale coupe momentanément votre podcast et vous diffuse votre note vocale directement dans les oreilles.

---

## 💼 6. Potentiel Professionnel & Mobilité (B2B / Métiers Itinérants)

Remindly ne s'adresse pas uniquement au grand public : c'est un **assistant contextuel à haute valeur ajoutée pour les professionnels mobiles** :

- 🚚 **Chauffeurs-Livreurs** : *"Quand j'arrive dans la zone du client X ➔ Rappelle-moi de faire signer le bon de retour."*
- 👷 **Artisans & BTP** : *"Dès que je passe près d'un magasin de bricolage / quincaillerie ➔ Acheter des chevilles de 8."*
- 🏥 **Infirmiers Libéraux & Visiteurs Médicaux** : *"Quand j'arrive chez le patient Y ➔ Rappel de vérifier le renouvellement d'ordonnance."*
- 🏢 **Commerciaux de Terrain** : *"Dès que je suis à proximité du siège du client Z ➔ Relancer sur le devis en attente."*

---

## 🛠️ 7. Fiche Technique & Confidentialité

- **Plateforme** : Android (SDK 26 à 35+ / Android 8.0 à 15).
- **Interface & Expérience** : 100% Kotlin, Jetpack Compose, Material Design 3, Thème sombre dynamique.
- **Architecture** : Clean Architecture + MVVM, Coroutines & Flow, Dagger Hilt.
- **Stockage & Persistance** : Room Database (SQLite locale sécurisée), Jetpack DataStore Preferences.
- **Services Géographiques** : Google Play Services Location (Hardware Geofencing API), Google Places API / Overpass OSM.
- **Moteur Audio & Voix** : Android native `TextToSpeech`, `MediaPlayer`, `AudioAttributes.USAGE_ALARM`.
- **Confidentialité** : Approche *Privacy-First* et *Offline-First* — Vos données de position restent sur votre appareil et ne sont jamais revendues.

---

*Remindly — Ne pensez plus à vos rappels, laissez vos lieux vous les rappeler.*
