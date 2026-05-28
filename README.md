# ATW's LevelHead

ATW's LevelHead is a Weave mod for Lunar Client Minecraft 1.8.9. It adds
clean Hypixel LevelHead tags, BedWars tab stats, lobby chat lookups, and a
BedWars threat overview without wasting Hypixel API requests in pregame
waiting rooms.

The mod targets the older Weave Loader v0.2.6 environment used by ATW Client.
It is intentionally built for Minecraft 1.8.9 compatibility rather than the
newer Weave ecosystem.

## Features

- Hypixel network level tags above players.
- BedWars stars and FKDR in tab once the game actually starts.
- BedWars waiting-room protection so obfuscated pregame names are not spammed
  into the Hypixel API just to become `NICKED`.
- Automatic stats lookup for players who talk in a BedWars waiting room.
- `/atwlh stats <player>` and `/atwlh recent` chat stat lookups.
- Team threat overview at BedWars game start, ranked by the top three teams.
- Nicked-player threat estimates so suspicious hidden players still count.
- Threat scores on a readable 0-10 scale using FKDR and BedWars level.
- Hypixel API key management from in-game commands.
- Local-only chat output with Minecraft colors and no server messages.
- Disk cache for recent stats to reduce repeated API calls.
- Command packet compatibility for Lunar auto-text hotkeys.

## How It Works

In BedWars mode, ATW's LevelHead waits for Hypixel's real game-start messages
before loading tab stats. This avoids looking up the fake or obfuscated names
shown in the waiting room.

When the game starts, the mod batches the players from tab/world state, fetches
BedWars stats, fills the tab list with stars/FKDR, and prints a local overview
of the most dangerous teams.

Before the game starts, the mod only performs Hypixel stats lookups for players
who actually type in chat. That gives useful lobby scouting without burning API
requests on every waiting-room tab entry.

## Commands

The short command alias is `/atwlh`. The long alias is `/atwlevelhead`.

```text
/atwlh status
/atwlh reload
/atwlh clearcache
/atwlh background <on|off|toggle>
/atwlh mode <level|bedwars>
/atwlh stats <player/prefix>
/atwlh recent
/atwlh players
/atwlh api add <hypixel-api-key>
/atwlh api test
/atwlh api status
/atwlh api clear
```

Useful notes:

- `/atwlh stats <player>` checks a player by exact name or tab/recent-chat
  prefix.
- `/atwlh recent` shows stats for players who talked in chat recently.
- `/atwlh api add <key>` saves the Hypixel key to the runtime config and
  refreshes the BedWars provider immediately.
- `/atwlh api test` validates the saved key against Hypixel.
- `/atwlh status` includes mode, Hypixel context, queue state, cache size, and
  provider status.

## Threat Score

Each BedWars player gets a threat score from 0 to 10. The score is based mainly
on FKDR, with BedWars level increasing the threat of experienced players.

Nicked or unavailable players receive a static estimated threat score so they
are visible in lookups without being treated as guaranteed top-tier players.

Team threat is the average threat score of the known members on that team. The
game-start overview ranks the top three teams and shows each team's average
threat, average FKDR, and highest-threat player.

## Configuration

Runtime settings are saved to:

```text
%USERPROFILE%\.weave\atw-levelhead.json
```

Current keys:

- `backgroundEnabled`: enables the translucent background behind tags.
- `displayMode`: `level` or `bedwars`.
- `hypixelApiKey`: saved Hypixel API key for BedWars stats.

The disk cache is saved to:

```text
%USERPROFILE%\.weave\atw-levelhead-cache.json
```

Use `/atwlh clearcache` to clear in-memory and disk caches.

## Requirements

- Lunar Client Minecraft 1.8.9.
- Weave Loader v0.2.6.
- Java 8-compatible bytecode.
- A Hypixel API key for BedWars stats.

By default, the Gradle build expects the old Weave Loader jar at:

```text
../../java/agents/WeaveLoader.jar
```

That path matches the ATW Client workspace layout. If you build this repo
standalone, pass a custom loader path:

```powershell
.\gradlew.bat build -PweaveLoaderJar=C:\path\to\WeaveLoader.jar
```

You can also set the `WEAVE_LOADER_JAR` environment variable.

## Build

From the repository root:

```powershell
.\gradlew.bat build
```

The built jar is written to:

```text
build/libs/ATWLevelHead-0.1.0.jar
```

## Install

Copy the jar into Weave's mods folder:

```powershell
Copy-Item .\build\libs\ATWLevelHead-0.1.0.jar $env:USERPROFILE\.weave\mods\ATWLevelHead-0.1.0.jar -Force
```

Restart Lunar Client after installing. Weave loads mods at game startup.

## Privacy And API Keys

Do not commit private Hypixel API keys. Use:

```text
/atwlh api add <hypixel-api-key>
```

The mod stores the key locally in `%USERPROFILE%\.weave\atw-levelhead.json`.

## Compatibility Notes

Manual chat commands and Lunar auto-text hotkeys can travel through different
Minecraft send paths. This mod keeps a packet-send bridge so Weave commands are
handled locally instead of being sent to the server as unknown commands.

This project is built specifically for Lunar Client 1.8.9 and Weave Loader
v0.2.6. Upgrading Minecraft, mappings, Weave, or Gradle plugins should be done
only with full in-game testing.
