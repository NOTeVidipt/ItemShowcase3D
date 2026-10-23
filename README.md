# ItemShowcase3D v1.1.0

Paper plugin for Minecraft 1.21.11. No Vault or economy dependency. The management GUI is intentionally not included.

## Features
- Persistent 3D ItemDisplay showcases with a floating label showing the item name and enchantment levels. Enchanted books display stored enchantments too.
- Automatic item-aware particles: enchanted items use enchantment particles; gold, emerald/slime, diamond/prismarine, and Nether-themed items use different effects.
- Configurable global scale, per-showcase scale, rotation, optional bobbing, and optional particles.
- Per-showcase pedestal/glass material changes.
- Player ownership: players can create showcases and remove/edit their own; `itemshowcase3d.admin` overrides ownership.
- Per-player showcase limit.
- Saved item return queue for offline owners.
- Saves original block data for newly created showcases and restores it on removal.
- Protects showcase entities and case blocks from damage/explosions.
- Compatible with the existing v1.0.0 `showcases.yml` format; old showcases without saved original block data cannot recover historical block states that v1.0.0 never stored.

## Commands
- `/ishowcase create <name>`
- `/ishowcase remove <name>`
- `/ishowcase list`
- `/ishowcase scale <name> <0.2-2.5>`
- `/ishowcase style <name> <pedestal-material> <glass-material>`
- `/ishowcase animate <name> <on|off>`
- `/ishowcase text <on|off>` — toggle item-name and enchantment text (admin)
- `/ishowcase particles <on|off>` — toggle particle effects (admin)
- `/ishowcase reload`
- `/ishowcase help`

Players need `itemshowcase3d.create` to create and `itemshowcase3d.remove-own` to remove their own. Both default to true. Admin permission `itemshowcase3d.admin` defaults to OP.

## Build
Install JDK 21 and Maven, open this directory in a terminal, and run:

```bash
mvn clean package
```

The jar will be in `target/ItemShowcase3D-1.1.0.jar`.

## Configuration
See `src/main/resources/config.yml`. Item scale can be changed globally in config or per showcase with `/ishowcase scale`. Particles are disabled by default to avoid unnecessary server load.


## In-game settings (no manual config editing)
Admins can view and change common defaults in-game. Changes are saved automatically to `config.yml`.

- `/ishowcase settings` — view current defaults
- `/ishowcase setting scale 1.2` — default size for newly created showcases
- `/ishowcase setting rotation 45` — rotation speed (0–360 degrees/second)
- `/ishowcase setting names on` — show item names and enchantments above displays
- `/ishowcase setting particles on` — enable particles
- `/ishowcase setting float off` — toggle floating animation
- `/ishowcase setting limit 20` — per-player showcase limit (1–500)
- `/ishowcase setting height 2` — case height (1–5 blocks)
- `/ishowcase setting pedestal SPRUCE_PLANKS` — default pedestal block
- `/ishowcase setting glass GLASS` — default case block

These settings are saved automatically. `/ishowcase text on|off` toggles the name and enchantment label; `/ishowcase particles on|off` toggles the per-item particle effects. Enchantment names and levels are generated from the actual item metadata, including stored enchantments on enchanted books. The GUI remains excluded and Vault is not required.


### Hide the glass case

Set `glass-material: AIR` in `config.yml`, or run `/ishowcase setting glass AIR` in-game as an admin. New showcases will have no glass blocks. To restore glass, run `/ishowcase setting glass GLASS`. The setting controls the default for newly created showcases; use `/ishowcase style <name> <pedestal-material> AIR` to remove glass from an existing showcase.
