# ATW LevelHead 0.2.2 Weave Release

Release date: June 18, 2026

## Target

- Weave Loader v0.2.6
- Lunar Client / Minecraft 1.8.9
- Java 8-compatible bytecode

## Install

Copy `ATWLevelHead-0.2.2.jar` into your Weave mods folder:

```text
%USERPROFILE%\.weave\mods
```

Then restart Lunar/Minecraft. Weave mods are loaded only at game startup.

## Required API Key

This public release does not include a Hypixel API key. BedWars star/FKDR
lookups require each user to provide their own Hypixel developer API key.

Recommended in-game setup:

```text
/atwlh api add <hypixel-api-key>
/atwlh api test
```

Alternative startup options:

```text
ATW_LEVELHEAD_HYPIXEL_API_KEY=<hypixel-api-key>
-Datw.levelhead.hypixelApiKey=<hypixel-api-key>
```

Without an API key, normal network LevelHead/Sk1er lookups can still work, but
BedWars FKDR/star lookups will report the missing key.

## Included Fixes

- BedWars nicked players now use a full-width placeholder tab prefix:
  `--✫ | FKDR --`.
- The placeholder remains internally marked as nicked, so BedWars cache rules,
  threat estimates, and nicked rendering behavior stay intact.
- Keeps the previous contextual BedWars/non-BedWars LevelHead behavior from
  `0.2.1`.

## Release Safety

- `atw-levelhead-local.properties` is excluded from the release jar.
- No bundled Hypixel API key is present in this artifact.
