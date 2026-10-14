# Building Nocturne's sound packs

`build_packs.py` turns openly licensed multisample libraries into the compact SF3 packs in
`app/src/main/assets/soundpacks/`. It needs Python 3.10+ with `numpy scipy soundfile py7zr`.

## Sources

Download into one directory (`--sources`), keeping these names:

| Directory | Download |
|---|---|
| `SalamanderGrandPianoV3_44.1khz16bit/` | https://freepats.zenvoid.org/Piano/SalamanderGrandPiano/SalamanderGrandPianoV3+20161209_44khz16bit.tar.xz |
| `YDP-GrandPiano-SF2-20160804/` | https://freepats.zenvoid.org/Piano/YDP-GrandPiano/YDP-GrandPiano-SF2-20160804.tar.bz2 |
| `UprightPianoKW-SF2-20220221/` | https://freepats.zenvoid.org/Piano/UprightPianoKW/UprightPianoKW-SF2-20220221.7z |
| `vcsl/` | `git clone https://github.com/sgossner/VCSL vcsl` (only the Steinway B `NoSus`, Kawai `Sustains`, French harpsichord `Sustains` and Pipe Organ folders are used) |
| `splendid/` | `git clone https://github.com/sfzinstruments/SplendidGrandPiano splendid` |
| `gs-ep/` | `git clone https://github.com/sfzinstruments/GregSullivan.E-Pianos gs-ep` |
| `fm-piano1/` | `git clone https://github.com/freepats/fm-piano1` |
| `GeneralUser-GS/` | `git clone https://github.com/mrbumpy409/GeneralUser-GS` |

```bash
python3 build_packs.py --sources ~/nocturne-sources --out ../../app/src/main/assets/soundpacks
```

## What the builder does

1. **Onset trim** to ~1 ms of pre-roll, so every key speaks immediately.
2. **Pitch mapping check** — each sample's pitch is measured and compared with its mapped root
   (this caught VCSL's Kawai grand, French harpsichord and organ files, which are named one
   octave low).
3. **Loudness normalisation** per note (keeping recorded layer dynamics) or per sample for
   libraries delivered pre-normalised; velocity response is restored with SF2 modulators.
4. **Tail looping** — long decays keep their natural attack and early decay; the tail is
   flattened by the measured decay rate and crossfade-looped at a correlation-matched point,
   and the SF2 volume envelope continues the measured decay. Full-length sustain at a fraction
   of the memory.
5. **SF3 encoding** (Ogg Vorbis, quality 0.45) with 2,560 guard samples after each loop end so
   the codec's final-block edge never touches the loop.

On the device, `Sf3Transcoder` decodes a pack to SF2 once and heals each loop seam with a 3 ms
crossfade to remove residual coding noise at the wrap.

Instrument voicings (EQ, filter, reverb, layering, output gain) live in
`app/src/main/assets/instruments.json`. Output gains are calibrated with
`tools/hosttest/evaluate.py --calibrate -24`.
