"""Sample preparation helpers for Nocturne's sound packs.

The goal is to turn long, raw multisample recordings into compact SoundFont
material without audible compromises:

* the onset is trimmed to ~1 ms of pre-roll so key presses speak instantly;
* every sample is loudness-normalised (optionally per note, keeping the
  recorded dynamic difference between velocity layers);
* long decaying notes keep their full natural attack and early decay, then the
  tail is flattened and crossfade-looped while the SoundFont volume envelope
  continues the measured decay rate.  This reproduces long piano sustain with
  a fraction of the sample memory.
"""
from __future__ import annotations

import math
import re
from dataclasses import dataclass

import numpy as np
import soundfile as sf
from scipy.signal import resample_poly

TARGET_RATE = 44100
LOOP_GUARD = 2560   # samples kept after a loop end (> one Vorbis long block)
NOTE_NAMES = {'c': 0, 'd': 2, 'e': 4, 'f': 5, 'g': 7, 'a': 9, 'b': 11}


def note_number(name: str) -> int:
    """'C4' -> 60, 'D#1' -> 27, 'Bb0' -> 22, 'db4' -> 61."""
    m = re.fullmatch(r'([A-Ga-g])([#b]?)(-?\d)', name)
    if not m:
        raise ValueError(name)
    n = NOTE_NAMES[m.group(1).lower()] + (1 if m.group(2) == '#' else -1 if m.group(2) == 'b' else 0)
    return n + 12 * (int(m.group(3)) + 1)


def load(path, target_rate=TARGET_RATE) -> np.ndarray:
    """Load audio as float32 [frames, channels] at target_rate."""
    x, rate = sf.read(path, dtype='float32', always_2d=True)
    return conform(x, rate, target_rate)


def conform(x: np.ndarray, rate: int, target_rate=TARGET_RATE) -> np.ndarray:
    if x.ndim == 1:
        x = x[:, None]
    if rate != target_rate:
        g = math.gcd(rate, target_rate)
        x = resample_poly(x, target_rate // g, rate // g, axis=0).astype(np.float32)
    return x


def envelope_db(x: np.ndarray, rate: int, win_s=0.02) -> np.ndarray:
    mono = x.mean(axis=1) if x.ndim > 1 else x
    w = max(1, int(win_s * rate))
    n = len(mono) // w
    if n == 0:
        return np.array([-120.0])
    r = np.sqrt(np.mean(mono[:n * w].reshape(n, w) ** 2, axis=1) + 1e-12)
    return 20 * np.log10(r)


def trim_onset(x: np.ndarray, rate: int, rel_db=-42.0, preroll_ms=1.0) -> np.ndarray:
    a = np.abs(x).max(axis=1)
    peak = a.max()
    if peak <= 0:
        return x
    idx = int(np.argmax(a > peak * 10 ** (rel_db / 20)))
    start = max(0, idx - int(preroll_ms * rate / 1000))
    y = x[start:].copy()
    fade = min(len(y), int(0.0005 * rate) + 1)
    y[:fade] *= np.linspace(0, 1, fade, dtype=np.float32)[:, None]
    return y


def attack_rms(x: np.ndarray, rate: int, win_s=0.4) -> float:
    seg = x[:int(win_s * rate)]
    return float(np.sqrt(np.mean(seg.mean(axis=1) ** 2) + 1e-12))


@dataclass
class Prepared:
    data: np.ndarray            # float32 [frames, channels]
    loop: tuple[int, int] | None
    hold_s: float | None        # envelope hold before the synthetic decay starts
    decay_s: float | None       # time for the envelope to fall by 100 dB


def fade_tail(x: np.ndarray, rate: int, floor_db=-66.0, fade_s=0.08) -> np.ndarray:
    env = envelope_db(x, rate)
    ref = env.max()
    above = np.nonzero(env > ref + floor_db)[0]
    end_win = (above[-1] + 1) if len(above) else len(env)
    end = min(len(x), int(end_win * 0.02 * rate) + int(fade_s * rate))
    y = x[:end].copy()
    f = min(len(y), int(fade_s * rate))
    y[-f:] *= np.linspace(1, 0, f, dtype=np.float32)[:, None] ** 2
    return y


def loop_tail(x: np.ndarray, rate: int, keep_s: float, loop_s: float, floor_db=-66.0,
              min_rate_db=1.0, max_rate_db=80.0) -> Prepared:
    """Keep keep_s seconds, crossfade-loop the end and report the decay to continue with."""
    env = envelope_db(x, rate)
    ref = env.max()
    above = np.nonzero(env > ref + floor_db)[0]
    natural_end_s = ((above[-1] + 1) if len(above) else len(env)) * 0.02
    if natural_end_s <= keep_s * 1.15:
        return Prepared(fade_tail(x, rate, floor_db), None, None, None)

    xf_s = loop_s * 0.35
    t_a = max(0.05, keep_s - loop_s * 1.3 - xf_s)   # flatten everything the loop search may touch
    # Decay rate measured just before and inside the loop region (dB / s).
    fit0, fit1 = max(0.25 * t_a, t_a - 1.5), keep_s
    i0, i1 = int(fit0 / 0.02), int(fit1 / 0.02)
    tt = np.arange(i0, i1) * 0.02
    ee = env[i0:i1]
    slope = np.polyfit(tt, ee, 1)[0] if len(tt) > 3 else -10.0
    r = float(np.clip(-slope, min_rate_db, max_rate_db))

    n_keep = int(keep_s * rate)
    y = x[:n_keep + 64].astype(np.float32).copy()
    a_idx = int(t_a * rate)
    t = (np.arange(len(y) - a_idx) / rate).astype(np.float32)
    y[a_idx:] *= (10 ** (r * t / 20))[:, None]

    le = n_keep
    L = int(loop_s * rate)
    X = int(xf_s * rate)
    ls, corr = best_loop_start(y, le, L, X)
    y = crossfade_loop(y, ls, le, X, corr)
    return Prepared(y, (ls, le), t_a, 100.0 / r)


def best_loop_start(y: np.ndarray, le: int, L: int, X: int, search=0.3):
    """Pick the loop start whose preceding X samples best match the X samples before `le`.

    Returns (loop_start, normalised correlation). Matching the crossfade source and
    destination in phase avoids the amplitude dips that a blind crossfade produces.
    """
    mono = y.mean(axis=1) if y.ndim > 1 else y
    lo = max(X + 1, le - int(L * (1 + search)))
    hi = max(lo + 1, le - int(L * (1 - search)))
    a = mono[le - X:le].astype(np.float64)
    region = mono[lo - X:hi].astype(np.float64)
    n = len(region) + len(a)
    nfft = 1 << (n - 1).bit_length()
    c = np.fft.irfft(np.fft.rfft(region, nfft) * np.conj(np.fft.rfft(a, nfft)), nfft)[:hi - lo]
    csum = np.concatenate([[0.0], np.cumsum(region ** 2)])
    e = np.sqrt(csum[X:X + hi - lo] - csum[:hi - lo]) + 1e-12
    ncc = c / (e * (np.linalg.norm(a) + 1e-12))
    k = int(np.argmax(ncc))
    return lo + k, float(ncc[k])


def crossfade_loop(y: np.ndarray, ls: int, le: int, X: int, corr: float) -> np.ndarray:
    w = np.linspace(0, 1, X, endpoint=False, dtype=np.float32)
    if corr > 0.6:
        fin, fout = w, 1 - w                                   # correlated: constant amplitude
    else:
        fin, fout = np.sin(w * np.pi / 2), np.cos(w * np.pi / 2)  # uncorrelated: constant power
    y = y.copy()
    y[le - X:le] = y[le - X:le] * fout[:, None] + y[ls - X:ls] * fin[:, None]
    # Guard region: keep playing the loop's continuation well past the loop end, so the
    # Ogg Vorbis end-of-stream block edge never lands on the samples the loop wraps over.
    guard = []
    need = LOOP_GUARD
    while need > 0:
        take = min(need, le - ls)
        guard.append(y[ls:ls + take])
        need -= take
    return np.concatenate([y[:le]] + guard, axis=0)


def find_sustain_loop(x: np.ndarray, rate: int, start_s: float, loop_s: float, xf_s: float):
    """Crossfade loop for steady sounds (organs, pads). Returns (data, (ls, le))."""
    X = int(xf_s * rate)
    L = int(loop_s * rate)
    le = min(len(x) - 64, int(start_s * rate) + L)
    y = x[:le + 64].astype(np.float32)
    ls, corr = best_loop_start(y, le, L, X, search=0.25)
    return crossfade_loop(y, ls, le, X, corr), (ls, le)


def to_int16(x: np.ndarray) -> np.ndarray:
    y = np.clip(x, -1.0, 32767 / 32768)
    # TPDF dither keeps quiet tails clean after normalisation.
    d = (np.random.default_rng(1).random(y.shape) - np.random.default_rng(2).random(y.shape)) / 32768
    return np.round((y + d) * 32768).clip(-32768, 32767).astype(np.int16)


def timecents(seconds: float) -> int:
    return int(round(1200 * math.log2(max(seconds, 0.001))))


def estimate_midi(x: np.ndarray, rate: int, t0=0.12, dur=0.45) -> float:
    """Harmonic-product-spectrum pitch estimate (used to validate sample mapping)."""
    mono = x.mean(axis=1) if x.ndim > 1 else x
    seg = mono[int(t0 * rate):int((t0 + dur) * rate)]
    if len(seg) < 1024:
        return float('nan')
    seg = seg * np.hanning(len(seg))
    n = 1 << 18
    spec = np.abs(np.fft.rfft(seg, n))
    hps = spec.copy()
    for h in (2, 3):
        d = spec[::h]
        hps[:len(d)] *= d
    lo, hi = int(25 / (rate / n)), int(4500 / (rate / n))
    k = lo + int(np.argmax(hps[lo:hi]))
    return 69 + 12 * math.log2(k * rate / n / 440.0)
