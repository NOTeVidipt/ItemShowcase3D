# Changelog

## 1.1.0
- Added `/ishowcase text on|off` to toggle item-name and enchantment labels.
- Labels show enchantment names and levels; enchanted books use their stored enchantments.
- Added `/ishowcase particles on|off` and item-aware particle effects for enchanted, gold, emerald/slime, diamond/prismarine, and Nether-themed items.
- Expanded command suggestions for new commands, setting options, toggles, and materials.
- Supports `AIR` as the glass material to create an open showcase.
- Keeps Vault and the management GUI out of this release.
- Retains showcase persistence, ownership checks, item returns, scaling, styling, and block restoration.

## Notes
- Particle effects are disabled by default; enable them with `/ishowcase particles on`.
- `/ishowcase text off` hides the complete label, including enchantments, without disabling item enchantment glint.
- Legacy showcases from versions that did not save original block states cannot recover those historical states.
