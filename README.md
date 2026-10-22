# ItemShowcase3D

**A Paper plugin for rotating 3D item showcases with a glass case and wooden pedestal.**

- **Release:** 1.0.0
- **Target tested by the author:** Paper 1.21.11
- **Java:** 21 or newer (the server log supplied by the author used Java 25)
- **Build system:** Maven
- **Main command:** `/ishowcase`

## Features

- Create a named showcase using the item held in your main hand.
- Creating a showcase consumes one item from the held stack.
- Display the item in a glass-and-pedestal showcase.
- Rotate the displayed item when animation is enabled.
- Remove a showcase and return its displayed item to its creator.
- Queue item returns for offline creators and deliver them when they rejoin.
- Drop return leftovers if the creator's inventory is full.
- Protect showcase blocks from non-OP breaking and prevent explosion damage to showcase blocks.
- Configure pedestal/glass materials, item scale, rotation speed, animation, and per-player showcase limits.

## Installation

1. Stop your Paper server.
2. Back up your server and world.
3. Put `ItemShowcase3D-1.0.0.jar` in the server's `plugins/` directory.
4. Start the server and check the console for `ItemShowcase3D enabled`.
5. Run `/ishowcase help` in-game.

Do not install both the release JAR and another copy of this plugin under a different filename.

## Commands

| Command | Description |
|---|---|
| `/ishowcase help` | Show command help |
| `/ishowcase create <name>` | Create a showcase using the item in your main hand |
| `/ishowcase remove <name>` | Remove a showcase and return its item |
| `/ishowcase list` | List showcases |
| `/ishowcase reload` | Reload plugin configuration |

The plugin declares the `showcase` alias for `/ishowcase`.

## Permissions

| Permission | Default | Description |
|---|---|---|
| `itemshowcase3d.admin` | Operators | Manage showcases |

Players need this permission to use management commands. Showcase breaking protection is based on operator status in the current implementation.

## Configuration

The default `plugins/ItemShowcase3D/config.yml`:

```yaml
pedestal-material: SPRUCE_PLANKS
glass-material: GLASS
glass-height: 2
item-scale: 0.8
rotation-degrees-per-second: 12
animate: true
max-showcases-per-player: 20
messages:
  prefix: "&8[&bShowcase&8] "
```

Increase `rotation-degrees-per-second` for faster rotation (for example, `45`). Keep `animate: true` to enable animation. Fully restart the server after changing settings unless you use the plugin's reload command and have confirmed that it reloads the setting you changed.

## Build from source

Requirements: JDK 21+ and Maven.

1. Extract the source folder from the `source/` directory in this release bundle.
2. Open the project folder containing `pom.xml`.
3. Run:

```bash
mvn clean package
```

The output JAR will be in `target/ItemShowcase3D-1.0.0.jar`.

## Compatibility and support

This release was built for Paper 1.21.11 and the author reports testing the main features on a Paper 1.21.11 server. Other server versions and forks are not guaranteed. Please report the server version, Java version, steps to reproduce, and relevant console errors when opening an issue.

Always test updates on a backup server before installing them on a production world.

## License

This project is distributed under the MIT License; see `LICENSE`.
