# 2009scape Script Playground

Script your character in the 2009scape single-player game, with Scratch-style blocks or JavaScript that runs live in game. Add-on only, game not included.

> **Hobby project.** This is something I built for fun in my spare time. It is provided as is, with
> no support, no warranty and no promise of updates. Issues and pull requests may not get a reply.
> Use it at your own risk, and back up your characters before you try it.

---

## What it is

[2009scape](https://2009scape.org) is a community remake of RuneScape as it was in 2009, and it
has a free single-player version you run on your own computer. This project, **Code Edition**, adds
scripting to that single-player game:

- **A launcher** that starts the game world and the game window for you, backs up your characters
  each time, and opens the Playground.
- **A Code orb** next to the minimap in game (a black orb with a white C). Click it to open the
  Playground beside the game.
- **The Script Playground**, where you write scripts that play your character:
  - **Block scripts** (Basic view): drag blocks together like Scratch. Blocks cover every skill:
    gathering, making things, Hunter, Farming, Agility, Thieving, combat, Magic, Prayer, Slayer,
    Summoning and Construction. Lists in the side panel come from the game itself, with the level
    each thing needs, and "What it needs" checks your levels, items and quests before you run.
  - **JavaScript** (Advanced view): modern JavaScript with functions like `smart_move`,
    `get_nearest_monster`, `attack`, `loot` and `bank_deposit_all`. Any script can be shown as
    blocks or as JavaScript, whichever way it was made.
  - Scripts run **instantly** in the running game, on the game tick (every 0.6 seconds), until you
    press Stop. There is no restart or rebuild.
- **Walk To** that plans whole routes: it opens doors and gates, crosses stiles and toll gates,
  takes stairs, ladders and trapdoors, and uses Agility shortcuts only when your level allows.
- **Tools**: Grab Coordinates (Ctrl+G) to pick a tile in the game, copy and paste blocks between
  scripts, a Cheats list, and Ctrl+V pasting into the game's chat box.
- **Small quality-of-life changes**: new characters skip Tutorial Island and start in Lumbridge
  with the normal starter items, and levelling up no longer opens a box that stops what you are
  doing (you still get the jingle, fireworks and chat message).

The game files themselves (server, game client, cache and Java) are **not** in this repository.
You download the official 2009scape single-player release and install this add-on on top of it.

## Requirements

- **Windows 10 or 11** is the main target. The launcher `.exe` and the bundled Java come from the
  Windows release of the game.
- The **2009scape single-player release for Windows**. Code Edition was built and tested against
  the release of **31 July 2026**. Newer releases will often work, but if the game changes a lot
  the add-on may need rebuilding from `source/`.
- About 500 MB of free disk space and 4 GB of RAM (the game world uses 2 GB, the game window 1 GB).

Linux and macOS may work with your own Java 11 (see below), but they are not tested.

## Setup

1. **Download the game.** Go to the official single-player release,
   **https://gitlab.com/2009scape/singleplayer/windows**, click **Code**, then download the **zip**.
2. **Extract it** to a folder **with no spaces in its path**, for example `C:\Games\2009scape`.
   The game will not start from a path with spaces, and `Program Files` usually does not work.
3. **Run the game once on its own** with `launch.bat`, wait for it to finish its first start
   (30 to 60 seconds), then close it. This is optional, but it confirms the base game works before
   you add anything.
4. **Download this repository.** Click the green **Code** button on this page, then
   **Download ZIP**, and extract it anywhere.
5. **Install Code Edition.** Double-click `install.bat`. When it asks, drag your game folder from
   step 2 into the window and press Enter. It copies the files from `overlay/` into the game folder.

   On Linux or macOS: `./install.sh /path/to/2009scape`

6. **Start it.** In your game folder, double-click **`2009 OSRS Code Edition.exe`** and click
   **Launch Game**.

The installer only adds files. It never changes `server.jar`, `client.jar` or the cache, and
`launch.bat` still starts the plain game without Code Edition.

**To uninstall**, delete what the installer added: `2009 OSRS Code Edition.exe`, the three guide
`.txt` files, the `codeedition` and `scripts` folders, and `game\plugins\CodeWindow`.

## Using it

### Playing

1. Open **2009 OSRS Code Edition.exe** and click **Launch Game**. The game world starts, then the
   game window opens.
2. Log in (or create a character) and click **Click here to play**.
3. Play as normal. You never need the Playground to play.

### Your first block script

1. Click the **Code orb** next to the minimap, or **Launch Script Playground** in the launcher.
2. Click **New**, then **Block script**.
3. From the **Basics** tab, drag in **Walk To** and pick *Lumbridge cows* in the panel on the right.
4. From **Logic**, add **Repeat Forever**. Inside it, put **Attack** from **Combat & Magic** and
   pick *Cow*.
5. Press **Run**. Your character walks to the cows and starts fighting. Press **Stop** at any time.

If a block can't do its job, the script stops, that block turns red and the game chat says why,
for example *"you need Woodcutting level 15 to cut Oak. Yours is 1."*

The `scripts` folder has an example for each kind of skill (`example_chop_oaks_and_bank.blocks`,
`example_fish_and_cook_shrimps.blocks`, `example_gnome_agility_laps.blocks` and more). Open one to
see how it is built.

### Your first JavaScript script

Click **New**, then **JavaScript (advanced)**, and paste:

```js
(async () => {
  await smart_move("lumbridge cows");
  while (true) {
    const cow = get_nearest_monster({ name: "Cow" });
    if (!cow) { await sleep(1200); continue; }
    if (attack(cow) === "door") { await until_idle(); continue; }
    await wait_until(() => !get_targeted_monster(), 60000);
  }
})();
```

Some rules of thumb:

- Start one action at a time, and check `is_busy()` before starting a new one.
- Every loop needs an `await` or a `setInterval`. A loop that never pauses is stopped after one
  tick, with a message in the log.
- Errors show in the log with the script name and line number.
- Scripts can only control your character. They can't reach files, the internet or Java.

The console line under the log runs one line against your running script, for example
`character.hp` or `get_nearest_monster({name: "Cow"})`.

### Guides

Full references are in `overlay/` and get copied into your game folder:

| Guide | What's in it |
| --- | --- |
| `READ ME FIRST.txt` | How to start, the Playground's controls, and the folders |
| `Block guide.txt` | Every block, what it needs, and the example scripts |
| `Scripting guide.txt` | The JavaScript functions: moving, doors, fighting, items, banking, timers and messages |

### Where things are kept

| Folder (in your game folder) | What's there |
| --- | --- |
| `scripts` | Your scripts (`.blocks` and `.js`). The Playground saves here. |
| `backups` | Character backups, made each time you click Launch Game (the newest 20 are kept) |
| `logs` | Logs for the game world and game window, useful when something goes wrong |

## Troubleshooting

- **"Still waiting for the server to start..." forever.** The game folder's path has a space in it.
  Move the folder somewhere like `C:\Games\2009scape`.
- **"Some files are missing"** when you open the `.exe`. Keep it in the game folder, next to the
  `jre`, `game` and `codeedition` folders. Run the installer again if needed.
- **The Playground won't open.** It only opens while the game is running. Launch the game first.
- **The game crashes when switching to HD.** Delete `jagex_runescape_preferences.dat` from your
  Windows temp folder (`%TEMP%`).
- **Your character's stats go down after each login.** The XP rate was set higher than the game
  supports (5.0 for normal characters).
- **Anything else.** Look in the `logs` folder of your game folder.

## Running on Linux or macOS (untested)

Install Java 11, run `./install.sh /path/to/2009scape`, then start the launcher with:

```sh
java -jar /path/to/2009scape/codeedition/launcher.jar
```

## Building from source

Everything in `overlay/` is built from `source/`:

| Source | Builds | Built against |
| --- | --- | --- |
| `source/codebridge/` | `codeedition/codebridge.jar`, the script engine add-on for the game world, and its JavaScript prelude and block definitions | `server.jar`, GraalJS 22.3.5 |
| `source/launcher/` | `codeedition/launcher.jar`, the launcher, Script Playground and block editor | FlatLaf 3.4.1, RSyntaxTextArea 3.4.0 |
| `source/codewindow/` | `game/plugins/CodeWindow/`, the in-game Code orb | `client.jar` (2009scape client plugin API) |
| `source/overrides/` | `codeedition/overrides.jar`, replacements for single game classes, loaded ahead of `server.jar` | `server.jar` |
| `source/catalog/` | `codeedition/catalog.json`, the game catalogue behind the block lists | the server's own data |
| `source/cheats/` | `codeedition/cheats.json` | |
| `source/exe/launcher.c` | `2009 OSRS Code Edition.exe`, which starts the launcher with the game's bundled Java | Windows C compiler |

The `build-*.sh` scripts compile with JDK 11 (`--release 11`). They were written for the original
build machine, so edit the paths at the top of each one to point at your copy of the game and the
libraries.

## How it works (short version)

The launcher starts the game world with `overrides.jar` and `codebridge.jar` added to the
classpath, so `server.jar` is never edited. Codebridge runs scripts in a sandboxed GraalJS engine
and does their work on the game thread, once per tick. The Playground talks to codebridge over a
local port on your own computer only. The CodeWindow plugin uses the game client's plugin system
to draw the Code orb and open the Playground.

## Licence

This project's own code is released under the **MIT License**. See [`LICENSE`](LICENSE).

Exceptions, which keep their own licences:

- `source/overrides/` and `overrides.jar` are modified 2009scape server classes, so they stay under
  the **GNU AGPL v3**, the licence of 2009scape.
- The libraries in `overlay/codeedition/lib/` and inside `launcher.jar`. See
  [`THIRD-PARTY.txt`](THIRD-PARTY.txt) and `third-party-licenses/`.

## Credits and acknowledgements

- **[2009scape](https://gitlab.com/2009scape/2009scape)** and its contributors, for the game server,
  the game client and its plugin system, and the
  [single-player release](https://gitlab.com/2009scape/singleplayer/windows) this add-on is built
  on. 2009scape is licensed under the GNU AGPL v3.
- **Jagex Ltd.** RuneScape, its game content, art and the original game client are the property of
  Jagex. None of their files are included here.
- **[GraalJS](https://github.com/oracle/graaljs)** (Oracle, Universal Permissive License 1.0), the
  JavaScript engine that runs scripts, with **[ICU4J](https://github.com/unicode-org/icu)**
  (Unicode/ICU License).
- **[FlatLaf](https://github.com/JFormDesigner/FlatLaf)** (FormDev Software, Apache 2.0), the
  Playground's look and theme.
- **[RSyntaxTextArea](https://github.com/bobbylight/RSyntaxTextArea)** (Robert Futrell, BSD
  3-Clause), the JavaScript editor.
- **[Scratch](https://scratch.mit.edu)** (MIT Media Lab), the inspiration for the block editor. The
  block editor is our own code and uses no Scratch code.
- **[Adventure Land](https://adventure.land)**, the coding MMORPG, whose scripting style inspired
  the JavaScript function names (`smart_move`, `get_nearest_monster`, `attack`, `loot`,
  `game_log`, `set_message`). No Adventure Land code is used.
- **[The RuneScape Wiki](https://runescape.wiki)**, used as a reference for how the 2009 game
  worked (skills, levels and items).
- Built by **BigDaddy284**, with help from Claude (Anthropic).

## Disclaimer

This is an unofficial fan project for personal, offline use. It is not affiliated with, endorsed
by or connected to Jagex Ltd. or the 2009scape team. RuneScape and Old School RuneScape are
trademarks of Jagex Ltd. Scripts are for the single-player game only. Do not use anything here
against Jagex's live games or the 2009scape online server, where bots and scripts break the rules.
