# Seed Miner – Meteor-Client-Addon (Minecraft 1.21.11)

Seed Miner berechnet aus dem Welt-Seed, wo Erze liegen, und lässt sie von Baritone automatisch abbauen.
Den Seed übernimmt das Addon aus [SeedMapper](https://github.com/xpple/SeedMapper) oder du gibst ihn selbst ein.
Alle Parameter lassen sich in der Meteor-GUI einstellen.

Eine ausführliche Schritt-für-Schritt-Anleitung findest du in [GUIDE.txt](GUIDE.txt).

> Gedacht für den eigenen (self-hosted) Server oder Singleplayer. Auf öffentlichen Servern ist das Cheating und führt zum Bann.

## Wie es funktioniert

1. **Seed**: Das Addon fragt SeedMapper nach dem Seed der aktuellen Welt, genau wie SeedMapper es für seine eigenen
   Befehle macht. Dabei gilt SeedMappers Reihenfolge (`SeedResolutionOrder`): zuerst `/sm:config Seed set <seed>`,
   dann die pro Server gespeicherten Seeds (`/sm:config SavedSeeds add <seed>`), dann SeedMappers Online-Datenbank.
   Alternativ trägst du den Seed in den Moduleinstellungen ein. Auf dem eigenen Server bekommst du ihn mit `/seed`.
2. **Vorhersage**: Das Addon nutzt die cubiomes-Bibliothek, die in SeedMapper steckt (gleiche Erz-Logik wie `/sm:highlight block`).
   Damit berechnet es in einem Hintergrund-Thread für alle Chunks um dich herum die Erz-Positionen.
3. **Prüfung**: In geladenen Chunks werden vorhergesagte Positionen verworfen, die in Wirklichkeit Luft sind (Höhlen, schon abgebaut).
4. **Abbau**: Baritone läuft bzw. gräbt zum nächstgelegenen Erz (Höhenunterschiede zählen stärker) und baut es ab.
   Danach geht es zum nächsten Erz. Während du dich bewegst, werden neue Chunks gescannt.

## Installation

In den `mods`-Ordner legen (alles für **1.21.11**):

| Mod | Pflicht | Hinweis |
|---|---|---|
| [Fabric Loader](https://fabricmc.net/use/) ≥ 0.18.2 | ja | |
| [Fabric API](https://modrinth.com/mod/fabric-api) | ja | wird von SeedMapper gebraucht |
| [Meteor Client](https://meteorclient.com/) 1.21.11 | ja | |
| [SeedMapper](https://modrinth.com/mod/seedmapper/versions) **2.21.x** | ja | liefert die Erz-Vorhersage (cubiomes) |
| [Baritone (Meteor-Build)](https://maven.meteordev.org/#/snapshots/meteordevelopment/baritone) 1.21.11 | für Abbau | ohne Baritone werden Erze nur markiert |
| Seed Miner (`build/libs/seed-miner-*.jar`) | ja | dieses Addon |

**Du brauchst Java 23 oder neuer**, weil SeedMapper das für 1.21.11 verlangt. Stell im Launcher für die Instanz
eine Java-23+-Laufzeit ein (z. B. [Temurin](https://adoptium.net/temurin/releases/)).

## Benutzung

1. Meteor-GUI öffnen (Rechte Umschalttaste) → Kategorie **Seed Miner** → Modul **seed-miner**.
2. Seed-Quelle und gewünschte Erze einstellen, dann das Modul aktivieren.
3. Optional: Im HUD-Editor von Meteor das Element **seed-miner** hinzufügen (Status, Anzahl Erze, abgebaut/übersprungen).

Auf der Modulseite siehst du außerdem den Status und den verwendeten Seed. Dazu gibt es die Buttons *Rescan*, *Skip target* und *Reset stats*.

**Stoppen:** Das Modul deaktivieren (am besten eine Taste dafür belegen). Baritones eigenes `#stop` hält das Modul nicht
dauerhaft an, weil es Baritone einfach das nächste Erz gibt.

### Einstellungen

| Gruppe | Einstellung | Beschreibung |
|---|---|---|
| Seed | `seed-source` | `Auto` (manueller Seed, sonst SeedMapper), `Manual`, `SeedMapper` |
| | `manual-seed` | Seed als Zahl (oder Text wie im Welt-erstellen-Menü); leer lassen, um den Seed aus SeedMapper zu nehmen |
| | `large-biomes` | Einschalten, wenn die Welt mit „Große Biome“ erstellt wurde |
| Targets | `blocks` | Erze: Kohle, Kupfer, Eisen, Gold, Redstone, Lapis, Diamant, Smaragd, Roheisen-/Rohkupferblock, Nethergold, Quarz, Antiker Schrott |
| | `ore-veins` | Große Kupfer-/Eisenadern mit einbeziehen (langsamer) |
| | `chunk-radius` | Scan-Radius in Chunks |
| | `min-y` / `max-y` | Höhenbereich |
| | `follow-player` | Neue Chunks scannen, während du dich bewegst |
| | `verify` | `Off`, `SkipAir` (Standard), `Exact` (nur Blöcke behalten, die wirklich das Erz sind; funktioniert nicht mit Anti-Xray) |
| Mining | `auto-mine` | Mit Baritone abbauen oder nur markieren |
| | `max-distance` | Maximale horizontale Entfernung zum Erz |
| | `vertical-cost` | Wie stark Höhenunterschiede bei der Wahl des nächsten Erzes zählen |
| | `target-timeout` | Erz nach N Sekunden überspringen |
| | `path-retries` | Neue Versuche, wenn Baritone keinen Weg findet |
| | `configure-baritone` | Setzt `allowBreak=true` und dazu `allow-place` / `allow-parkour`; wird danach zurückgesetzt |
| | `disable-when-done` | Abschalten, wenn keine Erze mehr übrig sind |
| Safety | `min-health` | Abschalten bei wenig Leben |
| | `stop-when-full` | Abschalten, wenn das Inventar voll ist |
| | `require-pickaxe` | Abschalten, wenn keine Spitzhacke in der Hotbar ist |
| | `pause-near-players` / `player-range` | Pausieren, solange andere Spieler in der Nähe sind |
| Anti-Detection | `anti-detection` | Hauptschalter; die Optionen darunter erscheinen nur, wenn er an ist (Gruppe ist eingeklappt, Klick auf die Überschrift klappt sie auf) |
| | `max-ores-per-minute` | Höchstens so viele Erze in 60 Sekunden, dann wird gewartet (0 = kein Limit) |
| | `random-pause` / `pause-min` / `pause-max` | Zufällige Pause nach jedem Erz (Sekunden) |
| | `stone-detours` / `detour-chance` / `detour-distance` | Nach einem Erz mit X % Chance einen zufälligen Steinblock in der Nähe abbauen |
| Render | `render`, `render-limit`, `shape-mode`, Farben, `tracer` | Markierung der vorhergesagten Erze |

### Befehl

```
.seedminer                 Status
.seedminer seed <seed>     manuellen Seed setzen
.seedminer clear-seed      manuellen Seed löschen
.seedminer rescan          alle Vorhersagen neu berechnen
.seedminer skip            aktuelles Erz überspringen
```

## Grenzen

- Die Vorhersage berücksichtigt nicht, ob ein Erz an Luft grenzt. Deshalb sind aus der Entfernung viele Positionen falsch
  (vor allem Diamanten neben Höhlen sowie Kohle, Kupfer und Smaragd). Für geladene Chunks korrigiert `verify` das.
  Falsche Ziele merkt das Modul, sobald Baritone dort ankommt, und überspringt sie.
- Läuft der Server mit einer anderen Version als 1.21.11 (z. B. über ViaFabricPlus), stimmt die Vorhersage nicht.
- Baritone baut über Pathing ab: Es gräbt sich zum Erz und bricht es dabei als Teil des Weges ab.

## Bauen

```bash
./gradlew build
```

Die Jar landet in `build/libs/`. Falls nötig, lädt Gradle JDK 23 automatisch herunter.
`./gradlew runClient` startet einen Dev-Client mit Meteor, Baritone, SeedMapper und Fabric API.
