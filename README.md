# Seed Miner – Meteor Client addon (Minecraft 1.21.11)

Seed Miner predicts where ores are from the world seed and lets Baritone mine them automatically.
The seed is taken from [SeedMapper](https://github.com/xpple/SeedMapper), or you enter it yourself.
Everything can be configured in the Meteor GUI.

A detailed step-by-step guide is in [GUIDE.txt](GUIDE.txt).

> Meant for your own (self-hosted) server or singleplayer. On public servers this is cheating and will get you banned.

## How it works

1. **Seed**: The addon asks SeedMapper for the seed of the current world, the same way SeedMapper does for its own
   commands. SeedMapper's order (`SeedResolutionOrder`) applies: first `/sm:config Seed set <seed>`, then the seeds
   saved per server (`/sm:config SavedSeeds add <seed>`), then SeedMapper's online database.
   You can also enter the seed in the module settings. On your own server you get it with `/seed`.
2. **Prediction**: The addon uses the cubiomes library bundled with SeedMapper (same ore logic as `/sm:highlight block`)
   to compute the ore positions of all chunks around you on a background thread.
3. **Verification**: In loaded chunks, predicted positions that are actually air (caves, already mined) are dropped.
4. **Mining**: Baritone walks or digs to the nearest ore (height differences weigh more) and mines it, then moves on
   to the next one. New chunks are scanned while you move.

## Installation

Put these into your `mods` folder (all for **1.21.11**):

| Mod | Required | Notes |
|---|---|---|
| [Fabric Loader](https://fabricmc.net/use/) ≥ 0.18.2 | yes | |
| [Fabric API](https://modrinth.com/mod/fabric-api) | yes | needed by SeedMapper |
| [Meteor Client](https://meteorclient.com/) 1.21.11 | yes | |
| [SeedMapper](https://modrinth.com/mod/seedmapper/versions) **2.21.x** | yes | provides the ore prediction (cubiomes) |
| [Baritone (Meteor build)](https://maven.meteordev.org/#/snapshots/meteordevelopment/baritone) 1.21.11 | for mining | without Baritone, ores are only highlighted |
| Seed Miner (`seed-miner-<version>.jar`) | yes | this addon, from the latest [release](../../releases/latest) |

**Java 23 or newer is required**, because SeedMapper needs it on 1.21.11. Set a Java 23+ runtime for the instance in
your launcher (e.g. [Temurin](https://adoptium.net/temurin/releases/)).

Every build has its own version (e.g. `0.2.0+build.12`). Remove the old Seed Miner jar from `mods` before adding a new one.

## Usage

1. Open the Meteor GUI (Right Shift) → category **Seed Miner** → module **seed-miner**.
2. Set the seed source and the ores you want, then enable the module.
3. Optional: add the **seed-miner** element in Meteor's HUD editor (status, number of ores, mined/skipped).

The module page also shows the status and the seed in use, plus the buttons *Rescan*, *Skip target* and *Reset stats*.

**Stopping:** disable the module (bind a key to it). Baritone's own `#stop` does not stop the module for good,
because the module simply gives Baritone the next ore.

### Settings

| Group | Setting | Description |
|---|---|---|
| Seed | `seed-source` | `Auto` (manual seed, otherwise SeedMapper), `Manual`, `SeedMapper` |
| | `manual-seed` | Seed as a number (or text, like in the world creation screen); leave empty to use the seed from SeedMapper |
| | `large-biomes` | Enable if the world was created with the Large Biomes preset |
| Targets | `blocks` | Ores: coal, copper, iron, gold, redstone, lapis, diamond, emerald, raw iron/copper blocks, nether gold, quartz, ancient debris |
| | `ore-veins` | Include large copper/iron ore veins (slower) |
| | `chunk-radius` | Scan radius in chunks |
| | `min-y` / `max-y` | Height range |
| | `follow-player` | Keep scanning new chunks while you move |
| | `verify` | `Off`, `SkipAir` (default), `Exact` (only keep blocks that really are the ore; does not work with anti-xray) |
| Mining | `auto-mine` | Mine with Baritone, or only highlight |
| | `max-distance` | Maximum horizontal distance to an ore |
| | `vertical-cost` | How much height differences count when picking the next ore |
| | `target-timeout` | Skip an ore after N seconds |
| | `path-retries` | Retries when Baritone can't find a path |
| | `configure-baritone` | Sets `allowBreak=true` plus `allow-place` / `allow-parkour`; restored afterwards |
| | `disable-when-done` | Disable when no ores are left |
| Safety | `min-health` | Disable at low health |
| | `stop-when-full` | Disable when the inventory is full |
| | `require-pickaxe` | Disable when there is no pickaxe in the hotbar |
| | `pause-near-players` / `player-range` | Pause while other players are nearby |
| Anti-Detection | `anti-detection` | Master switch; the options below only show while it is on (the group starts collapsed, click its title to expand it) |
| | `max-ores-per-minute` | Mine at most this many ores within 60 seconds, then wait (0 = no limit) |
| | `random-pause` / `pause-min` / `pause-max` | Random pause after each ore (seconds) |
| | `stone-detours` / `detour-chance` / `detour-distance` | After an ore, mine a random nearby stone block with X % chance |
| Render | `render`, `render-limit`, `shape-mode`, colors, `tracer` | Highlighting of the predicted ores |

### Command

```
.seedminer                 status
.seedminer seed <seed>     set the manual seed
.seedminer clear-seed      clear the manual seed
.seedminer rescan          recompute all predictions
.seedminer skip            skip the current ore
```

## Limitations

- The prediction doesn't know whether an ore touches air, so many positions predicted from far away are wrong
  (especially diamonds next to caves, and coal, copper and emerald). In loaded chunks `verify` corrects this.
  Wrong targets are noticed once Baritone gets there and are skipped. They count as *invalid*, not as mined.
- If the server runs a different version than 1.21.11 (e.g. via ViaFabricPlus), the prediction won't match.
- Baritone mines by pathing: it digs its way to the ore and breaks it as part of the path.

## Building

```bash
./gradlew build
```

The jar ends up in `build/libs/`. Gradle downloads JDK 23 automatically if needed.
`./gradlew runClient` starts a dev client with Meteor, Baritone, SeedMapper and Fabric API.
