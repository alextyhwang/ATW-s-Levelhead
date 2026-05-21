# ATW LevelHead

Standalone Weave mod for the ATW launcher stack. It targets Lunar Client
Minecraft 1.8.9 with the bundled old Weave Loader v0.2.6.

The mod can show either Hypixel network level or BedWars star/FKDR above player
heads. BedWars mode uses Hypixel API stats with Seraph-style star and FKDR
colors.

## Requirements

- Minecraft/Lunar Client 1.8.9.
- Weave Loader v0.2.6 from this repo:

```text
../../java/agents/WeaveLoader.jar
```

- Java 8-compatible bytecode. The Gradle build uses `options.release = 8`.
- Gson and Minecraft classes from the client runtime.
- A Hypixel developer API key is currently compiled into the BedWars provider.

Do not upgrade Weave or move this to newer Weave APIs unless you are also
testing the full old Lunar/Weave stack. The command and hook behavior is very
version-specific.

## Config

Runtime settings are saved to:

```text
%USERPROFILE%\.weave\atw-levelhead.json
```

Current keys:

- `backgroundEnabled`: controls the translucent tag background.
- `displayMode`: `level` or `bedwars`.

Disk cache is saved to:

```text
%USERPROFILE%\.weave\atw-levelhead-cache.json
```

Cache behavior:

- `level`: Hypixel network levels are cached for 6 hours.
- `bedwars`: BedWars star/FKDR entries are cached for 10 minutes.

Use `/atwlh clearcache` to clear both in-memory and disk caches.

## Build

From this folder:

```powershell
.\gradlew.bat build
```

The jar is written to:

```text
build/libs/ATWLevelHead-0.1.0.jar
```

## Install

Copy the built jar to Weave's mods folder:

```powershell
Copy-Item .\build\libs\ATWLevelHead-0.1.0.jar $env:USERPROFILE\.weave\mods\ATWLevelHead-0.1.0.jar -Force
```

Then restart Lunar 1.8.9 with Weave enabled.

For clean testing, disable the old `LevelHeadImproved.jar` Java agent if it is
enabled in the launcher. This mod is loaded by Weave from `.weave\mods`, not as
a launcher Java agent.

## Commands

- `/atwlevelhead status`
- `/atwlevelhead reload`
- `/atwlevelhead clearcache`
- `/atwlevelhead background <on|off|toggle>`
- `/atwlevelhead mode <level|bedwars>`
- `/atwlh status`

## Notes

Manual chat commands and Lunar auto-text hotkeys can use different send paths.
This mod keeps a packet-send fallback so Weave commands such as `/togglechams`,
`/history`, and `/atwlh` are handled locally instead of being sent to the
server as unknown commands.
