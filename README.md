# Skiff Go

A native Android (Kotlin, Jetpack Compose) editor and companion for the **M-VAVE FM-1** running the
[Skiff](https://github.com/illuminus5/skiff) firmware. It talks to the FM-1 directly over USB-MIDI (Android
`MidiManager`) with Skiff's editor protocol: no WebView, no browser.

Sister app of [Sloop Go](https://github.com/jahlib/sloop-fm1-go) (the SLOOP firmware). The two firmwares share
Felucca's framing and commands 1–32 but differ from command 33 on, and some numbers mean other things on each: Skiff
Go checks INFO and refuses any firmware but Skiff (2.0 or later for the piano roll's per-note ties).

## Status

Phase 1, the skeleton, and phase 2, the sound:

- connect to the FM-1 (the first likely port that answers INFO), read INFO with its tagged capabilities, refuse
  SLOOP / Felucca;
- one request in flight (`Wire`), replies matched to their request as the Skiff Editor does (`replyMatches`), knob
  SETs coalesced per control; WATCH 3 for the device's pushes (CHANGED, RELOAD, TRACK_CHANGED), TRANSPORT as the
  keep-alive (WATCH re-armed after a silence near Skiff's 3 s timeout), DUMP and the strips read again every 5 s;
- PLAY / STOP, RESTART, REC from the header;
- **Sound**: every parameter of the selected track and the globals on the device's pages (the Skiff Editor's layout:
  ENV, LFO, MOD matrix, EDIT with the drum lanes' level, pan, tune and decay, VOICE, FX with DRIVE / INSERT / DUCK / delay /
  reverb / chorus, SCALE and CHORD, ARP, PATTERN), wide ids (P_COUNT 143), values as the device shows them, the engine
  and preset lists in the device's order, light tracks refused (PHYS and GRAIN on T5 and T6), INIT;
- **Mixer**: six strips, LEVEL and MUTE by TRACK_MIX, PAN and the REVERB send by SET (selected) or TRACK_PARAM (others);
- **Sound files**: Save to / Open a "felucca-patch" or "felucca-library" file of the Skiff Editor (values found by label, so
  a file from another firmware maps), played onto the track as the Skiff Editor's audition does.

- **Sequencer** (SEQ): the piano roll on Skiff 2.0's note model (per-note ties, so notes overlap and a chord's notes end
  apart, as the device's NOTES page does; the firmware's own unfold / edit / fold, ported from `ui.c` and `ui_input.c`):
  tap to put a note of the chosen length, drag it to move it, drag its right end to stretch it, hold it to take it out;
  four notes a step at most, no pitch twice, a single voice (MONO, LEGATO, UNISON) takes over. The drum grid for DRUM and
  DRUM-X (hold a cell for its accent). The picked step's sheet: velocity, accent, slide, chance, ratchet / FLAM, trig
  condition, nudge. LEN, DIV, SWING, GATE and DIR, undo and redo, STEP_CHANGED pushes. The playhead is computed (below).

Next: parameter locks and automation through MOTION, the song (pool, lists, chains, scenes, PERFORM), MENU settings,
FM6, samples, backup. The playhead is not in Skiff's protocol: it is computed from the start of play (exact when the
app pressed PLAY, to the poll's resolution when the FM-1 did), the tempo, DIV, LEN and DIR (FWD, RVRS, PING; not RAND,
swing or the song's jumps), with an option to make the phone the USB MIDI clock master still to come.
