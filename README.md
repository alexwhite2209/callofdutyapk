# CoD4 Winlator

Fork of [Winlator](https://github.com/brunodev85/winlator-app) (GPL-3.0) tuned for Call of Duty 4.

- On first run the app scans the phone (GPU, RAM, CPU, screen) and picks settings for smooth play.
- Finds the game in Downloads (`Downloads/CoD4` with `iw3sp.exe`) or extracts `Call_of_Duty_4_Modern_Warfare.zip`.
- Creates "Call of Duty 4" / "Multiplayer" shortcuts with touch controls.
- Short "Game settings" screen in plain language replaces the emulator options (advanced options are still reachable).

The game files are not included.

## Build

`./build_apks.sh` produces `CoD4-Winlator.apks` (install with SAI). Requires access to `dl.google.com`.

Uninstall the original Winlator first: the package name stays `com.winlator` because the bundled rootfs is tied to it.

Original Winlator README: [README_WINLATOR.md](README_WINLATOR.md).
