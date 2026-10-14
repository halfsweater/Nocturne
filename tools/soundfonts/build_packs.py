#!/usr/bin/env python3
"""Build Nocturne's bundled sound packs (SF3) from openly licensed sample libraries.

Usage:  python3 build_packs.py --sources /path/to/sources --out ../../app/src/main/assets/soundpacks

See tools/soundfonts/README.md for the list of sources, licences and the
exact download locations. Every pack is a SoundFont 3 file (Ogg Vorbis
compressed samples); the app transcodes a pack to SF2 the first time one of
its instruments is played.
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import re
import sys
from concurrent.futures import ProcessPoolExecutor
from dataclasses import dataclass, field

import numpy as np

sys.path.insert(0, os.path.dirname(__file__))
import samplekit as K  # noqa: E402
import sf2lib as L  # noqa: E402

G = L.G
SRC = ''


# --------------------------------------------------------------------------- model

@dataclass
class Rec:
    """One recorded note: a file (or an in-memory array) at a root key."""
    root: int
    path: str | None = None
    array: np.ndarray | None = None
    rate: int = K.TARGET_RATE
    tune: int = 0               # cents to add when playing


@dataclass
class Layer:
    vel: tuple[int, int]
    recs: list[Rec]


@dataclass
class Voicing:
    kind: str = 'decay'          # 'decay' (piano-like), 'sustain' (organ), 'pluck' (no loop)
    keep: tuple = ((21, 5.0), (48, 3.6), (72, 2.4), (96, 1.5), (108, 1.1))
    loop: tuple = ((21, 1.0), (60, 0.6), (96, 0.3), (108, 0.25))
    release: tuple = ((21, 0.9), (60, 0.55), (84, 0.38), (88, 0.38), (89, 2.8), (108, 2.8))
    preserve_dynamics: bool = True
    vel_to_att: int = 600        # centibels at velocity 0 (SF2 default is 960)
    vel_to_filter: int = -1200   # cents at velocity 0 (SF2 default is -2400)
    key_shift: int = 0           # >0: play each key from a lower sample (brighter), <0 darker
    attack_rms_db: float = -19.0
    sustain_loop: tuple = (1.2, 0.9, 0.3)   # start, length, crossfade (seconds) for 'sustain'
    pluck_floor_db: float = -62.0


def interp(table, note):
    xs = [a for a, _ in table]
    ys = [b for _, b in table]
    return float(np.interp(note, xs, ys))


# --------------------------------------------------------------------------- processing

def _prepare(job):
    rec, v = job
    x = K.load(rec.path) if rec.path else K.conform(rec.array, rec.rate)
    x = K.trim_onset(x, K.TARGET_RATE)
    if 45 <= rec.root <= 90:
        measured = K.estimate_midi(x, K.TARGET_RATE)
        expected = rec.root - rec.tune / 100.0
        dev = (measured - expected + 6) % 12 - 6      # octave errors of the estimator are tolerated
        if abs(dev) > 0.45:
            print(f'    pitch warning: {rec.path or "array"} root {rec.root} measured {measured:.2f}', flush=True)
    rms = K.attack_rms(x, K.TARGET_RATE)
    if v.kind == 'decay':
        p = K.loop_tail(x, K.TARGET_RATE, interp(v.keep, rec.root), interp(v.loop, rec.root))
    elif v.kind == 'sustain':
        st, ln, xf = v.sustain_loop
        st = min(st, max(0.3, len(x) / K.TARGET_RATE - ln - 0.3))
        data, loop = K.find_sustain_loop(x, K.TARGET_RATE, st, ln, xf)
        p = K.Prepared(data, loop, None, None)
    else:
        p = K.Prepared(K.fade_tail(x[:int(8 * K.TARGET_RATE)], K.TARGET_RATE, v.pluck_floor_db), None, None, None)
    return p, rms


@dataclass
class SampleSet:
    layers: list[Layer]
    prepared: list[list]                      # [layer][rec] -> (Prepared, gain)
    samples: dict = field(default_factory=dict)  # (layer, rec) -> (Sample, Sample|None)


def prepare_set(name: str, layers: list[Layer], v: Voicing, pool) -> SampleSet:
    jobs = [(r, v) for lay in layers for r in lay.recs]
    results = list(pool.map(_prepare, jobs, chunksize=1))
    it = iter(results)
    prepared = [[next(it) for _ in lay.recs] for lay in layers]

    target = 10 ** (v.attack_rms_db / 20)
    gains: dict[tuple[int, int], float] = {}
    if v.preserve_dynamics:
        by_root: dict[int, list] = {}
        for li, lay in enumerate(layers):
            for ri, rec in enumerate(lay.recs):
                by_root.setdefault(rec.root, []).append((li, ri))
        for root, idx in by_root.items():
            loud = max(prepared[li][ri][1] for li, ri in idx)
            peak = max(np.abs(prepared[li][ri][0].data).max() for li, ri in idx)
            g = min(target / loud, 0.97 / max(peak, 1e-6))
            for li, ri in idx:
                gains[(li, ri)] = g
    else:
        for li, lay in enumerate(layers):
            for ri, _ in enumerate(lay.recs):
                p, rms = prepared[li][ri]
                gains[(li, ri)] = min(target / rms, 0.97 / max(np.abs(p.data).max(), 1e-6))

    ss = SampleSet(layers, [[(prepared[li][ri][0], gains[(li, ri)]) for ri in range(len(lay.recs))]
                            for li, lay in enumerate(layers)])
    for li, lay in enumerate(layers):
        for ri, rec in enumerate(lay.recs):
            p, g = ss.prepared[li][ri]
            data = K.to_int16(p.data * g)
            base = f'{name[:9]}{rec.root}v{li}'
            if data.shape[1] == 1:
                ss.samples[(li, ri)] = (L.Sample(base, data[:, 0].copy(), K.TARGET_RATE, rec.root, 0, p.loop), None)
            else:
                sl = L.Sample(base + 'L', data[:, 0].copy(), K.TARGET_RATE, rec.root, 0, p.loop, L.LEFT)
                sr = L.Sample(base + 'R', data[:, 1].copy(), K.TARGET_RATE, rec.root, 0, p.loop, L.RIGHT)
                sl.link, sr.link = sr, sl
                ss.samples[(li, ri)] = (sl, sr)
    return ss


def make_instrument(name: str, ss: SampleSet, v: Voicing, layer_ids=None, vel_ranges=None) -> L.Instrument:
    layer_ids = list(range(len(ss.layers))) if layer_ids is None else layer_ids
    inst = L.Instrument(name)
    inst.zones.append(L.Zone(mods=[
        L.Modulator(0x0502, G['initialAttenuation'], v.vel_to_att, 0, 0),
        L.Modulator(0x0102, G['initialFilterFc'], v.vel_to_filter, 0x0C02, 0),
    ]))
    for n, li in enumerate(layer_ids):
        lay = ss.layers[li]
        vel = vel_ranges[n] if vel_ranges else lay.vel
        roots = [r.root for r in lay.recs]
        order = list(np.argsort(roots))
        for k, ri in enumerate(order):
            rec = lay.recs[ri]
            prev_root = lay.recs[order[k - 1]].root if k > 0 else None
            next_root = lay.recs[order[k + 1]].root if k + 1 < len(order) else None
            lo = 0 if prev_root is None else (prev_root + rec.root) // 2 + 1 + v.key_shift
            hi = 127 if next_root is None else (rec.root + next_root) // 2 + v.key_shift
            lo, hi = max(0, lo), min(127, hi)
            if lo > hi:
                continue
            p, _ = ss.prepared[li][ri]
            mid = (max(lo, 21) + min(hi, 108)) // 2
            gens = {G['keyRange']: (lo, hi), G['velRange']: tuple(vel)}
            if rec.tune:
                gens[G['fineTune']] = rec.tune
            if p.loop:
                gens[G['sampleModes']] = 1
            if p.hold_s is not None:
                gens[G['holdVolEnv']] = K.timecents(p.hold_s)
                gens[G['decayVolEnv']] = K.timecents(min(p.decay_s, 90.0))
                gens[G['sustainVolEnv']] = 1000
            gens[G['releaseVolEnv']] = K.timecents(interp(v.release, mid))
            sl, sr = ss.samples[(li, ri)]
            if sr is None:
                inst.zones.append(L.Zone(dict(gens), [], sl))
            else:
                inst.zones.append(L.Zone({**gens, G['pan']: -500}, [], sl))
                inst.zones.append(L.Zone({**gens, G['pan']: 500}, [], sr))
    return inst


def build_instrument(name: str, layers: list[Layer], v: Voicing, pool) -> L.Instrument:
    return make_instrument(name, prepare_set(name, layers, v, pool), v)


def preset(name, program, inst, **gens):
    z = L.Zone({G[k]: val for k, val in gens.items()}, [], inst)
    return L.Preset(name, program, 0, [z])


def import_preset(sfont: L.SoundFont, bank: int, program: int, name: str, new_program: int) -> L.Preset:
    p = L.find_preset(sfont, bank, program)
    return L.Preset(name, new_program, 0, p.zones)


# --------------------------------------------------------------------------- sfz

def parse_sfz(path):
    text = open(path, encoding='utf-8', errors='replace').read()
    defines = dict(re.findall(r'#define\s+(\$\w+)\s+(\S+)', text))
    text = re.sub(r'#define[^\n]*', '', text)
    for k, val in sorted(defines.items(), key=lambda kv: -len(kv[0])):
        text = text.replace(k, val)
    text = re.sub(r'//[^\n]*', '', text)
    regions, scope = [], {'control': {}, 'global': {}, 'master': {}, 'group': {}}
    current = None
    for tok in re.split(r'(<\w+>)', text):
        m = re.fullmatch(r'<(\w+)>', tok)
        if m:
            current = m.group(1)
            if current == 'region':
                regions.append({})
            elif current in scope:
                scope[current] = {}
                if current == 'global':
                    scope['master'], scope['group'] = {}, {}
                elif current == 'master':
                    scope['group'] = {}
            continue
        if current is None:
            continue
        ops = dict((a, b.strip()) for a, b in re.findall(r'(\w+)=(.*?)(?=\s+\w+=|\s*$)', tok, flags=re.M))
        if current == 'region':
            merged = {**scope['control'], **scope['global'], **scope['master'], **scope['group'], **ops}
            regions[-1] = merged
        elif current in scope:
            scope[current].update(ops)
    base = os.path.dirname(path)
    out = []
    for r in regions:
        if 'sample' not in r:
            continue
        sample = os.path.join(base, r.get('default_path', ''), r['sample'].replace('\\', '/'))
        key = r.get('key')
        root = int(r.get('pitch_keycenter', key if key is not None else 60))
        out.append(dict(path=sample, root=root, lovel=int(r.get('lovel', 1)), hivel=int(r.get('hivel', 127)),
                        tune=int(float(r.get('tune', 0)))))
    return out


def layers_from_sfz(path, merge_vel=None):
    regions = parse_sfz(path)
    by_vel: dict[tuple[int, int], list[Rec]] = {}
    for r in regions:
        by_vel.setdefault((r['lovel'], r['hivel']), []).append(Rec(r['root'], r['path'], tune=r['tune']))
    layers = []
    for (lo, hi), recs in sorted(by_vel.items()):
        uniq = {}
        for rec in recs:
            uniq.setdefault(rec.root, rec)
        layers.append(Layer((0 if lo <= 1 else lo, hi), list(uniq.values())))
    return layers


# --------------------------------------------------------------------------- sources

def S(*p):
    return os.path.join(SRC, *p)


def salamander_layers(layer_ids, vel_ranges):
    d = S('SalamanderGrandPianoV3_44.1khz16bit', '44.1khz16bit')
    layers = []
    for lid, vr in zip(layer_ids, vel_ranges):
        recs = []
        for f in sorted(glob.glob(os.path.join(d, f'*v{lid}.wav'))):
            m = re.fullmatch(r'([A-G]#?\d)v\d+\.wav', os.path.basename(f))
            recs.append(Rec(K.note_number(m.group(1)), f))
        layers.append(Layer(vr, recs))
    return layers


def vcsl_steinway_layers():
    d = S('vcsl', 'Chordophones', 'Zithers', 'Grand Piano, Steinway B', 'NoSus')
    out = []
    for vl, vr in ((2, (0, 52)), (3, (53, 92)), (4, (93, 127))):
        recs = []
        for f in sorted(glob.glob(os.path.join(d, f'*_vl{vl}_rr1.wav'))):
            m = re.search(r'Close_([A-G]#?\d)_vl', f)
            recs.append(Rec(K.note_number(m.group(1)), f))
        out.append(Layer(vr, recs))
    return out


def vcsl_kawai_layers():
    d = S('vcsl', 'Chordophones', 'Zithers', 'Grand Piano, Kawai', 'Sustains')
    out = []
    for vl, vr in ((1, (0, 44)), (2, (45, 74)), (3, (75, 100)), (4, (101, 127))):
        recs = []
        for f in sorted(glob.glob(os.path.join(d, f'*_v{vl}_rr1_*.wav'))):
            m = re.search(r'sus_([A-G]#?-?\d)_v', f)
            # VCSL names this set one octave low (verified by pitch measurement).
            recs.append(Rec(K.note_number(m.group(1)) + 12, f))
        out.append(Layer(vr, recs))
    return out


def thin(recs, spacing):
    out = []
    for rec in sorted(recs, key=lambda r: r.root):
        if not out or rec.root - out[-1].root >= spacing:
            out.append(rec)
    return out


def splendid_layers():
    out = []
    for dyn, vr in (('PP', (0, 40)), ('MP', (41, 70)), ('MF', (71, 98)), ('FF', (99, 127))):
        txt = S('splendid', 'Data', f'{dyn}.txt')
        recs = []
        for line in open(txt):
            m = re.search(r'pitch_keycenter=(\d+).*sample=(.+?)\.\$EXT', line)
            if m:
                recs.append(Rec(int(m.group(1)), S('splendid', 'Samples', m.group(2) + '.flac')))
        out.append(Layer(vr, thin(recs, 3)))
    return out


def sf2_layers(sfont_path, bank, program, keep_layers=None):
    """Turn an existing SF2 preset into Rec layers (mono or stereo-joined samples)."""
    sfont = L.read(sfont_path)
    p = L.find_preset(sfont, bank, program)
    by_vel: dict[tuple[int, int], list[Rec]] = {}
    for pz in p.zones:
        if pz.target is None:
            continue
        pvr = pz.gens.get(G['velRange'], (0, 127))
        seen = set()
        for z in pz.target.zones:
            s = z.target
            if s is None or id(s) in seen or s.stype == L.RIGHT:
                continue
            seen.add(id(s))
            ivr = z.gens.get(G['velRange'], (0, 127))
            vr = (max(pvr[0], ivr[0]), min(pvr[1], ivr[1]))
            recs = by_vel.setdefault(vr, [])
            arr = s.data.astype(np.float32) / 32768
            if s.stype == L.LEFT and s.link is not None:
                seen.add(id(s.link))
                n = min(len(arr), len(s.link.data))
                arr = np.stack([arr[:n], s.link.data[:n].astype(np.float32) / 32768], axis=1)
            root = z.gens.get(G['overridingRootKey'], s.root)
            recs.append(Rec(root, array=arr, rate=s.rate, tune=s.correction))
    layers = [Layer(vr, recs) for vr, recs in sorted(by_vel.items())]
    if keep_layers is not None:
        layers = [layers[i] for i in keep_layers]
    return layers


def respan(layers, ranges):
    for lay, vr in zip(layers, ranges):
        lay.vel = vr
    return layers


# --------------------------------------------------------------------------- packs

def write_pack(name, presets, out_dir, info, quality):
    sfont = L.SoundFont(name=name, info=info, presets=presets)
    path = os.path.join(out_dir, f'{name}.sf3')
    n = L.write(sfont, path, compress=True, quality=quality, log=None)
    print(f'  wrote {path}: {n} samples, {os.path.getsize(path) / 1e6:.1f} MB', flush=True)


def main():
    global SRC
    ap = argparse.ArgumentParser()
    ap.add_argument('--sources', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--only', nargs='*')
    ap.add_argument('--quality', type=float, default=0.45)
    a = ap.parse_args()
    SRC = os.path.abspath(a.sources)
    os.makedirs(a.out, exist_ok=True)
    want = set(a.only or [])

    def wanted(n):
        return not want or n in want

    with ProcessPoolExecutor(max_workers=os.cpu_count()) as pool:
        if wanted('yamaha'):
            print('yamaha (Salamander Grand, Yamaha C5)', flush=True)
            v = Voicing()
            ss = prepare_set('YamahaC5', salamander_layers((3, 7, 11, 15), ((0, 44), (45, 72), (73, 98), (99, 127))),
                             v, pool)
            grand = make_instrument('Yamaha C5', ss, v)
            bright = make_instrument('Yamaha C5 bright', ss, Voicing(key_shift=3, vel_to_att=640, vel_to_filter=-600))
            soft = make_instrument('Yamaha C5 soft', ss,
                                   Voicing(vel_to_att=380, vel_to_filter=-1500,
                                           release=((21, 1.1), (60, 0.7), (88, 0.5), (89, 3.0), (108, 3.0))),
                                   layer_ids=[0, 1], vel_ranges=[(0, 84), (85, 127)])
            write_pack('yamaha', [preset('Yamaha C5 Grand', 0, grand), preset('Bright Grand', 1, bright),
                                  preset('Soft Grand', 2, soft)], a.out,
                       {'ICOP': 'Salamander Grand Piano V3 by Alexander Holm, CC-BY 3.0',
                        'ICMT': 'Derived by Nocturne: trimmed, normalised, tail-looped.'}, a.quality)

        if wanted('steinway'):
            print('steinway (VCSL Steinway B)', flush=True)
            inst = build_instrument('Steinway B', vcsl_steinway_layers(),
                                    Voicing(preserve_dynamics=False, vel_to_att=760), pool)
            write_pack('steinway', [preset('Steinway B Concert', 0, inst)], a.out,
                       {'ICOP': 'Versilian Community Sample Library (VCSL), CC0 1.0'}, a.quality)

        if wanted('kawai'):
            print('kawai (VCSL Kawai grand)', flush=True)
            inst = build_instrument('Kawai Grand', vcsl_kawai_layers(),
                                    Voicing(preserve_dynamics=True, vel_to_att=560), pool)
            write_pack('kawai', [preset('Kawai Grand', 0, inst)], a.out,
                       {'ICOP': 'Versilian Community Sample Library (VCSL), CC0 1.0'}, a.quality)

        if wanted('splendid'):
            print('splendid (Splendid Grand, Steinway D)', flush=True)
            layers = splendid_layers()
            inst = build_instrument('Steinway D', layers, Voicing(preserve_dynamics=True, vel_to_att=640), pool)
            write_pack('splendid', [preset('Splendid Grand', 0, inst)], a.out,
                       {'ICOP': 'Splendid Grand Piano (AKAI), public domain'}, a.quality)

        if wanted('ydp'):
            print('ydp (Yamaha Disklavier Pro grand)', flush=True)
            path = S('YDP-GrandPiano-SF2-20160804', 'YDP-GrandPiano-20160804.sf2')
            layers = respan(sf2_layers(path, 0, 0, keep_layers=[0, 2, 4]), ((0, 70), (71, 100), (101, 127)))
            warm = build_instrument('YDP warm', layers, Voicing(key_shift=-2, vel_to_att=560), pool)
            write_pack('ydp', [preset('Warm Grand', 0, warm)], a.out,
                       {'ICOP': 'YDP Grand Piano (FreePats), CC-BY 3.0; samples from Zenph Studios for OLPC'},
                       a.quality)

        if wanted('upright'):
            print('upright (Upright Piano KW)', flush=True)
            path = S('UprightPianoKW-SF2-20220221', 'UprightPianoKW-20220221.sf2')
            layers = sf2_layers(path, 0, 0)
            inst = build_instrument('Kawai Upright', layers,
                                    Voicing(vel_to_att=640, release=((21, 0.7), (60, 0.45), (84, 0.3), (88, 0.3),
                                                                     (89, 2.0), (108, 2.0))), pool)
            write_pack('upright', [preset('Upright Piano', 0, inst)], a.out,
                       {'ICOP': 'Upright Piano KW (FreePats), CC0 1.0'}, a.quality)

        if wanted('keys'):
            print('keys (electric pianos, organ, harpsichord, pads)', flush=True)
            gu = L.read(S('GeneralUser-GS', 'GeneralUser-GS.sf2'))
            ep_release = ((21, 0.5), (60, 0.35), (108, 0.25))
            fm = build_instrument('FM Piano', layers_from_sfz(S('fm-piano1', 'FM-Piano1 20190916.sfz')),
                                  Voicing(keep=((21, 4.5), (60, 3.0), (96, 1.6), (108, 1.2)), release=ep_release,
                                          vel_to_att=700, vel_to_filter=-1800), pool)
            wurli = build_instrument('Wurlitzer 200', layers_from_sfz(S('gs-ep', 'Wurlitzer EP200', 'Wurlitzer EP200.sfz')),
                                     Voicing(keep=((21, 3.5), (60, 2.6), (96, 1.5), (108, 1.2)), release=ep_release,
                                             preserve_dynamics=True, vel_to_att=520, vel_to_filter=-600), pool)
            cp80 = build_instrument('Yamaha CP80', layers_from_sfz(S('gs-ep', 'CP80', 'CP80.sfz')),
                                    Voicing(keep=((21, 4.0), (60, 2.8), (96, 1.5), (108, 1.1)),
                                            release=((21, 0.6), (60, 0.4), (88, 0.3), (89, 1.6), (108, 1.6)),
                                            vel_to_att=560, vel_to_filter=-900), pool)
            organ_dir = S('vcsl', 'Aerophones', 'Edge-blown Aerophones', 'Pipe Organ')
            # VCSL names the organ and French harpsichord one octave low (verified by pitch measurement).
            man = [Rec(K.note_number(re.search(r'_([A-G]#?\d)\.wav', f).group(1)) + 12, f)
                   for f in sorted(glob.glob(os.path.join(organ_dir, 'Loud', '*.wav')))]
            organ = build_instrument('Pipe Organ', [Layer((0, 127), man)],
                                     Voicing(kind='sustain', preserve_dynamics=False, vel_to_att=0, vel_to_filter=0,
                                             release=((21, 0.9), (108, 0.6)), attack_rms_db=-21), pool)
            harp_dir = S('vcsl', 'Chordophones', 'Zithers', 'Harpsichord, French', 'Sustains')
            harp = [Rec(K.note_number(re.search(r'_([A-G]#?\d)_rr1', f).group(1)) + 12, f)
                    for f in sorted(glob.glob(os.path.join(harp_dir, '*_rr1_*.wav')))]
            harpsichord = build_instrument('Harpsichord', [Layer((0, 127), harp)],
                                           Voicing(kind='pluck', preserve_dynamics=False, vel_to_att=180,
                                                   vel_to_filter=0, release=((21, 0.22), (108, 0.12))), pool)
            presets = [
                preset('FM Electric Piano', 0, fm),
                import_preset(gu, 0, 4, 'Tine Electric Piano', 1),
                preset('Wurlitzer 200', 2, wurli),
                preset('CP80 Stage Piano', 3, cp80),
                preset('Pipe Organ', 4, organ),
                preset('Harpsichord', 5, harpsichord),
                import_preset(gu, 0, 89, 'Warm Pad', 6),
                import_preset(gu, 0, 49, 'Slow Strings', 7),
            ]
            write_pack('keys', presets, a.out,
                       {'ICOP': 'FreePats FM Piano 1 (CC0); GeneralUser GS 2.0 by S. Christian Collins; '
                                'Greg Sullivan E-Pianos (CC-BY 3.0); VCSL (CC0)'}, a.quality)


if __name__ == '__main__':
    main()
