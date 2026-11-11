# Lumen TV

Lecteur Xtream Codes pour Android TV / Google TV, pensé pour la télécommande : interface façon Netflix, démarrage instantané, lecteur robuste.

## Fonctionnalités

- **TV en direct** avec guide des programmes (en cours, à suivre), zapping haut/bas, saisie du numéro de chaîne, liste des chaînes dans le lecteur
- **Films et séries** : affiches, fiches détaillées, saisons et épisodes, bande-annonce YouTube
- **Continuer à regarder** : reprise exacte, épisode suivant automatique
- **Profils** (jusqu'à 8) avec profil enfant, code PIN et code parental
- **Recherche globale** sur chaînes, films et séries avec clavier à l'écran
- **Ma liste** : appui long sur OK pour ajouter un film, une série ou une chaîne
- **Lecteur Media3** : reconnexion automatique, pistes audio et sous-titres, décodage logiciel AC3 / E-AC3 / DTS, format d'image

## Installation sur Google TV

1. Sur la TV : *Paramètres → Système → À propos → Build Android TV OS*, appuyez 7 fois pour activer le mode développeur.
2. Installez l'application **Downloader** (AFTVnews) depuis le Play Store.
3. *Paramètres → Applications → Sécurité et restrictions → Sources inconnues* : autorisez Downloader.
4. Dans Downloader, saisissez l'adresse de la dernière version :
   `https://github.com/<compte>/<dépôt>/releases/latest/download/lumen-tv.apk`

Chaque modification poussée sur `main` recompile l'APK automatiquement (onglet *Actions*). Les mises à jour s'installent par-dessus la version précédente sans perdre vos données.

## Forja TV

Le dépôt compile aussi **Forja TV** (module `forja/`) : le catalogue de [forja.ma](https://forja.ma) (SNRT : séries, films, théâtre, documentaires marocains, gratuits) dans une interface native façon Lumen TV.

- **Accueil** : Continuer à regarder, À la une, Les plus regardés, puis chaque rubrique ; onglets Séries, Films, Théâtre, Documentaires, Divertissement (une étagère par genre) et Ma liste
- **Fiche** : résumé, distribution, saisons et épisodes, reprise là où on s'était arrêté, titres similaires
- **Recherche** avec clavier à l'écran, **Tout voir** par rubrique
- **Appui long sur OK** sur une affiche : ajouter ou retirer de Ma liste
- **Lecture** dans le lecteur officiel de Forja, en plein écran et publicités comprises : OK pause, ← / → 15 s, ↑ / ↓ pointeur pour cliquer dans le lecteur, Retour pour revenir ; l'épisode suivant s'enchaîne

Installation avec Downloader, comme Lumen TV :
`https://github.com/hflowat-maker/Lumen-TV/releases/latest/download/forja-tv.apk`

## Compilation locale

```
./gradlew assembleRelease
```

Les APK se trouvent dans `app/build/outputs/apk/release/` (Lumen TV) et `forja/build/outputs/apk/release/` (Forja TV).
