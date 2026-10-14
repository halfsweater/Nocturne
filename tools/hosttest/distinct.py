"""Pairwise timbre distance between rendered instruments (1/3-octave log spectra + envelope)."""
import itertools, json, os, sys
import numpy as np, soundfile as sf
sys.path.insert(0, os.path.dirname(__file__))
import evaluate as E

def bands(y, t0, dur):
    seg = y[int(t0*48000):int((t0+dur)*48000)].mean(axis=1)
    S = np.abs(np.fft.rfft(seg*np.hanning(len(seg))))**2
    f = np.fft.rfftfreq(len(seg), 1/48000)
    edges = 50 * 2 ** (np.arange(0, 28) / 3)
    b = np.array([S[(f >= lo) & (f < hi)].sum() for lo, hi in zip(edges[:-1], edges[1:])])
    b = 10*np.log10(b + 1e-12)
    return b - b.max()

def main(eval_dir):
    ev, chord_t, low_t = E.phrase()
    feats = {}
    for f in sorted(os.listdir(eval_dir)):
        if f.endswith('.wav'):
            y, _ = sf.read(os.path.join(eval_dir, f), dtype='float32')
            # attack spectrum, sustain spectrum and decay shape
            a = bands(y, chord_t, 0.15); s = bands(y, chord_t + 1.5, 1.0)
            e = E.env_db(y[int(chord_t*48000):int((chord_t+5)*48000)])[::10]
            feats[f[:-4]] = (a, s, e - e.max())
    names = list(feats)
    D = {}
    for x, z in itertools.combinations(names, 2):
        a1, s1, e1 = feats[x]; a2, s2, e2 = feats[z]
        D[(x, z)] = np.mean(np.abs(a1-a2))*0.4 + np.mean(np.abs(s1-s2))*0.4 + np.mean(np.abs(e1-e2))*0.2
    pairs = sorted(D.items(), key=lambda kv: kv[1])
    print('closest pairs (mean dB difference):')
    for (x, z), d in pairs[:8]:
        print(f'  {x:18s} vs {z:18s} {d:5.2f} dB')
    print(f'median pair distance {np.median(list(D.values())):.2f} dB')

main(sys.argv[1])
