# Notices

Skiff Go is a native Android editor for the M-VAVE FM-1 running the **Skiff** firmware. It is licensed under the
GNU General Public License v3.0 only (see `LICENSE`).

- **Skiff** (https://github.com/illuminus5/skiff, GPL-3.0-only) by illuminus5, a fork of **Felucca** by Hügelton
  Instruments. The editor protocol (`web/EDITOR_PROTOCOL.md`, `firmware/src/editor*.c`) is implemented from it; the
  app icon is Skiff's `docs/logo/skiff-icon.svg` converted to a vector drawable; the track colours are THEME SKIFF's
  (`tools/gen_ui_palettes.py`).
- **Sloop Go** (https://github.com/jahlib/sloop-fm1-go, GPL-3.0-only): the USB-MIDI layer (`midi/MidiEngine.kt`),
  the frame codec and the request link are taken from it.
- **JetBrains Mono** (`fonts/`, `res/font/jetbrains_mono.ttf`) under the SIL Open Font License 1.1 (`fonts/OFL.txt`).

Skiff Go is not affiliated with M-VAVE, Hügelton Instruments or the Skiff project.
