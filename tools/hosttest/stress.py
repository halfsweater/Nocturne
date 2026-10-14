#!/usr/bin/env python3
"""Engine stress test: instrument switching on a loader thread while audio renders and notes are held,
dense polyphony with pedal, fast repeated notes. Reports dropouts, render-time budget and stuck voices."""
import os, sys, threading, time
import numpy as np
sys.path.insert(0, os.path.dirname(__file__))
import evaluate as E, nocturne_host as N

RATE, BLOCK = 48000, 192
cat = E.load_catalog()
packs = E.ensure_packs(cat, sys.argv[1], sys.argv[2], {})
specs = {i['id']: E.spec_for(i, packs) for i in cat['instruments'] if all(l['pack'] in packs for l in i['layers'])}
e = N.Engine(RATE, BLOCK)
e.load(*specs['yamaha_grand']); e.params()

worst_block_ms, budget_ms = 0.0, BLOCK / RATE * 1000
def render(seconds):
    global worst_block_ms
    out = []
    n = int(seconds * RATE / BLOCK)
    for _ in range(n):
        t = time.perf_counter(); y = e.render(BLOCK / RATE); worst_block_ms = max(worst_block_ms, (time.perf_counter() - t) * 1000)
        out.append(y)
    return np.concatenate(out)

# 1) hold a chord, switch instruments mid-chord on a background thread
for k in (48, 55, 60, 64, 67): e.note_on(k, 90)
render(0.3)
order = ['steinway_concert', 'rhodes', 'church_organ', 'cinematic_piano', 'wurlitzer', 'ambient_piano', 'harpsichord', 'bright_grand']
gaps = 0
for iid in order:
    th = threading.Thread(target=e.load, args=specs[iid]); th.start()
    seg = []
    while th.is_alive():
        seg.append(render(0.02))
    seg.append(render(0.05))
    y = np.concatenate(seg)
    blk = y[:len(y)//BLOCK*BLOCK].reshape(-1, BLOCK, 2)
    rms = np.sqrt((blk ** 2).mean(axis=(1, 2)))
    gaps += int(np.sum(rms < 1e-5))
    e.note_on(72, 80); render(0.15); e.note_off(72)
for k in (48, 55, 60, 64, 67): e.note_off(k)
print(f'switch-while-holding: {len(order)} switches, silent blocks while chord held: {gaps}')

# 2) dense pedal passage: 4 octaves of arpeggios with sustain, then release
e.load(*specs['cinematic_piano'])
e.sustain(1)
t0 = time.perf_counter()
for rep in range(6):
    for k in range(36, 96, 3):
        e.note_on(k, 100); render(0.012); e.note_off(k)
peak_voices = e.voices()
render(1.0)
e.sustain(0)
render(4.0)
print(f'dense pedal arpeggios: peak voices {peak_voices}, voices 4 s after pedal up: {e.voices()}')

# 3) fast repeated notes (16 per second) and trills
e.load(*specs['steinway_concert'])
for i in range(64):
    e.note_on(64 + (i % 2), 70 + i % 30); render(1 / 32); e.note_off(64 + (i % 2)); render(1 / 32)
render(3.0)
print(f'trill/repeats: voices after release {e.voices()}')
print(f'worst block render time {worst_block_ms:.2f} ms of {budget_ms:.2f} ms budget (host CPU)')
e.close()
