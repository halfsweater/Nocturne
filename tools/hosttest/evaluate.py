#!/usr/bin/env python3
"""Render every catalog instrument through the real engine and measure it.

Checks: note-on latency, loudness balance, brightness (spectral centroid),
envelope continuity of long sustained notes (detects bad tail loops), clicks,
polyphony / voice release, and instrument switching while notes are held.

  python3 evaluate.py --packs /path/with/sf3 --cache /tmp/cache --out /tmp/eval [--only id ...] [--png]
"""
import argparse
import json
import os
import sys

import numpy as np
import soundfile as sf

sys.path.insert(0, os.path.dirname(__file__))
import nocturne_host as N  # noqa: E402

RATE = 48000
ROOT = os.path.join(os.path.dirname(__file__), '..', '..')


def load_catalog():
    return json.load(open(os.path.join(ROOT, 'app/src/main/assets/instruments.json')))


def ensure_packs(catalog, packs_dir, cache, extra):
    paths = {}
    for p in catalog['packs']:
        src = os.path.join(packs_dir, os.path.basename(p['asset'])) if 'asset' in p else extra.get(p['id'])
        if not src or not os.path.exists(src):
            continue
        dst = os.path.join(cache, p['id'] + '.sf2')
        if not os.path.exists(dst) or os.path.getmtime(dst) < os.path.getmtime(src):
            N.transcode(src, dst)
        paths[p['id']] = dst
    return paths


def spec_for(inst, pack_paths):
    files, layers = [], []
    for l in inst['layers']:
        f = pack_paths[l['pack']]
        if f not in files:
            files.append(f)
        layers.append({'file': files.index(f), 'bank': l.get('bank', 0), 'program': l['program'],
                       'transpose': l.get('transpose', 0), 'gainDb': l.get('gainDb', 0.0),
                       'velocityScale': l.get('velocityScale', 1.0), 'velocityOffset': l.get('velocityOffset', 0)})
    return files, layers, inst.get('voicing', {})


def phrase():
    ev = [(0.0, 'on', 60, 100), (1.0, 'off', 60, 0)]
    t = 1.3
    for i, k in enumerate([60, 62, 64, 65, 67, 69, 71, 72, 71, 69, 67, 65, 64, 62, 60]):  # fast run
        ev += [(t, 'on', k, 70 + (i * 7) % 40), (t + 0.1, 'off', k, 0)]
        t += 0.09
    t += 0.3
    for i in range(8):                                                                  # repeated notes
        ev += [(t, 'on', 64, 90), (t + 0.05, 'off', 64, 0)]
        t += 0.075
    t += 0.4
    chord_t = t
    ev += [(t - 0.01, 'sus', 1, 0)]
    for k, v in [(36, 105), (48, 95), (55, 90), (60, 92), (64, 88), (67, 85)]:          # pedal chord
        ev += [(t, 'on', k, v), (t + 0.3, 'off', k, 0)]
    t += 5.0
    ev += [(t, 'sus', 0, 0)]
    t += 1.0
    ev += [(t, 'on', 21, 110), (t + 9.0, 'off', 21, 0)]                                 # A0 held 9 s
    ev += [(t, 'on', 96, 100), (t + 4.0, 'off', 96, 0)]                                  # C7 held 4 s
    return sorted(ev, key=lambda e: e[0]), chord_t, t


def env_db(y, win=0.02):
    m = y.mean(axis=1)
    w = int(win * RATE)
    n = len(m) // w
    return 20 * np.log10(np.sqrt(np.mean(m[:n * w].reshape(n, w) ** 2, axis=1)) + 1e-9)


def clicks(y, t0, t1, events=()):
    """Count impulsive discontinuities: second-difference spikes far above the local level.

    Blocks near note events (attacks are legitimately impulsive) and blocks below
    -60 dBFS (inaudible) are ignored.
    """
    seg = y[int(t0 * RATE):int(t1 * RATE)].mean(axis=1)
    d2 = np.abs(np.diff(seg, 2))
    if len(d2) < 4800:
        return 0
    w = 480
    n = len(d2) // w
    blocks = d2[:n * w].reshape(n, w)
    amp = np.abs(seg[:n * w]).reshape(n, w).max(axis=1)
    level = np.median(blocks, axis=1) + 1e-7
    peaks = blocks.max(axis=1)
    times = t0 + (np.arange(n) * w) / RATE
    near = np.zeros(n, dtype=bool)
    for et in events:
        near |= np.abs(times - et) < 0.06
    # A click is an isolated anomaly; periodic waveform features (reed pulses, tine
    # asymmetry) show up in every neighbouring block and are not counted.
    neigh = np.array([max(peaks[max(0, i - 3):i].max(initial=0), peaks[i + 1:i + 4].max(initial=0)) for i in range(n)])
    return int(np.sum((peaks > 40 * level) & (peaks > 4 * neigh) & (amp > 1e-3) & ~near))


def env_bumps(y, t0, t1):
    """Largest frame-to-frame rise (dB) inside a decaying, untouched region."""
    e = env_db(y[int(t0 * RATE):int(t1 * RATE)])
    e = e[e > -75]
    if len(e) < 3:
        return 0.0
    d = np.diff(e)
    return float(d.max())


def centroid(y, t0, dur=0.5):
    seg = y[int(t0 * RATE):int((t0 + dur) * RATE)].mean(axis=1)
    S = np.abs(np.fft.rfft(seg * np.hanning(len(seg))))
    f = np.fft.rfftfreq(len(seg), 1 / RATE)
    return float((S * f).sum() / (S.sum() + 1e-9))


def save_png(path, y, title):
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    fig, ax = plt.subplots(2, 1, figsize=(12, 6), sharex=True)
    m = y.mean(axis=1)
    ax[0].specgram(m, NFFT=2048, Fs=RATE, noverlap=1536, cmap='magma', vmin=-150)
    ax[0].set_ylim(0, 12000)
    ax[0].set_title(title)
    e = env_db(y)
    ax[1].plot(np.arange(len(e)) * 0.02, e)
    ax[1].set_ylim(-90, 0)
    ax[1].grid(alpha=0.3)
    fig.tight_layout()
    fig.savefig(path, dpi=70)
    plt.close(fig)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--packs', required=True)
    ap.add_argument('--cache', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--musescore')
    ap.add_argument('--only', nargs='*')
    ap.add_argument('--png', action='store_true')
    ap.add_argument('--calibrate', type=float, help='target phrase RMS (dBFS); rewrites outputGainDb')
    a = ap.parse_args()
    os.makedirs(a.cache, exist_ok=True)
    os.makedirs(a.out, exist_ok=True)
    cat = load_catalog()
    packs = ensure_packs(cat, a.packs, a.cache, {'musescore': a.musescore} if a.musescore else {})
    ev, chord_t, low_t = phrase()
    rows = []
    for inst in cat['instruments']:
        if a.only and inst['id'] not in a.only:
            continue
        if any(l['pack'] not in packs for l in inst['layers']):
            print(f'{inst["id"]:20s} skipped (pack not available)')
            continue
        e = N.Engine(RATE)
        files, layers, voicing = spec_for(inst, packs)
        e.load(files, layers, voicing)
        e.params()
        y = e.play(ev, tail=3.0)
        voices_end = e.voices()
        e.close()
        sf.write(os.path.join(a.out, inst['id'] + '.wav'), y, RATE, subtype='PCM_16')
        a0 = np.abs(y.mean(axis=1))
        onset = int(np.argmax(a0 > 0.0005))
        rms = 20 * np.log10(np.sqrt(np.mean(y[:int(chord_t * RATE)] ** 2)) + 1e-9)
        row = dict(id=inst['id'], latency_ms=onset / RATE * 1000, peak_db=20 * np.log10(np.abs(y).max() + 1e-9),
                   rms_db=rms, centroid=centroid(y, 0.02), bump_chord=env_bumps(y, chord_t + 1.0, chord_t + 5.0),
                   bump_low=env_bumps(y, low_t + 1.0, low_t + 8.9), clicks=clicks(y, chord_t + 0.5, low_t + 8.9, [x[0] for x in ev]),
                   voices_end=voices_end)
        rows.append(row)
        print(f"{row['id']:20s} lat {row['latency_ms']:5.2f}ms peak {row['peak_db']:6.1f} rms {row['rms_db']:6.1f} "
              f"centroid {row['centroid']:6.0f}Hz bumps {row['bump_chord']:4.1f}/{row['bump_low']:4.1f}dB "
              f"clicks {row['clicks']} voices@end {voices_end}", flush=True)
        if a.png:
            save_png(os.path.join(a.out, inst['id'] + '.png'), y, inst['name'])
    json.dump(rows, open(os.path.join(a.out, 'summary.json'), 'w'), indent=1, default=float)
    if a.calibrate is not None:
        path = os.path.join(ROOT, 'app/src/main/assets/instruments.json')
        text = open(path).read()
        import re
        for row in rows:
            inst = next(i for i in cat['instruments'] if i['id'] == row['id'])
            old = inst.get('voicing', {}).get('outputGainDb', 0.0)
            new = round((old + a.calibrate - row['rms_db']) * 2) / 2
            block = re.search(r'"id": "%s".*?"voicing": \{(.*?)\}' % row['id'], text, re.S)
            body = block.group(1)
            if '"outputGainDb"' in body:
                nb = re.sub(r'"outputGainDb": -?[0-9.]+', '"outputGainDb": %s' % new, body)
            else:
                nb = body.rstrip() + ', "outputGainDb": %s ' % new
            text = text[:block.start(1)] + nb + text[block.end(1):]
            print(f"  {row['id']:20s} outputGainDb {old} -> {new}")
        open(path, 'w').write(text)
        json.loads(text)


if __name__ == '__main__':
    main()
