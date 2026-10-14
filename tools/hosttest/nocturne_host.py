"""ctypes binding for the host build of the Nocturne engine (test harness only)."""
import ctypes as C
import os

import numpy as np

LIB = C.CDLL(os.environ.get('NOCTURNE_HOST_LIB', '/home/user/work/hostbuild/libnocturne_host.so'))
LIB.nt_create.restype = C.c_void_p
LIB.nt_spec_new.restype = C.c_void_p
LIB.nt_error.restype = C.c_char_p
for fn in ('nt_destroy', 'nt_spec_free', 'nt_spec_file', 'nt_spec_layer', 'nt_spec_set', 'nt_load', 'nt_note_on',
           'nt_note_off', 'nt_sustain', 'nt_all_off', 'nt_params', 'nt_render', 'nt_voices'):
    getattr(LIB, fn).argtypes = None
LIB.nt_spec_file.argtypes = [C.c_void_p, C.c_char_p]
LIB.nt_spec_layer.argtypes = [C.c_void_p, C.c_int, C.c_int, C.c_int, C.c_int, C.c_float, C.c_float, C.c_int]
LIB.nt_spec_set.argtypes = [C.c_void_p, C.c_char_p, C.c_float]
LIB.nt_load.argtypes = [C.c_void_p, C.c_void_p]
LIB.nt_note_on.argtypes = [C.c_void_p, C.c_int, C.c_int]
LIB.nt_note_off.argtypes = [C.c_void_p, C.c_int]
LIB.nt_sustain.argtypes = [C.c_void_p, C.c_int]
LIB.nt_all_off.argtypes = [C.c_void_p]
LIB.nt_params.argtypes = [C.c_void_p, C.c_float, C.c_float, C.c_float, C.c_float, C.c_int, C.c_int, C.c_int]
LIB.nt_render.argtypes = [C.c_void_p, C.POINTER(C.c_float), C.c_int]
LIB.nt_voices.argtypes = [C.c_void_p]
LIB.nt_destroy.argtypes = [C.c_void_p]
LIB.nt_spec_free.argtypes = [C.c_void_p]
LIB.nt_transcode.argtypes = [C.c_char_p, C.c_char_p]


def transcode(src, dst):
    if not LIB.nt_transcode(src.encode(), dst.encode()):
        raise RuntimeError(LIB.nt_error().decode())


class Engine:
    def __init__(self, rate=48000, block=192):
        self.rate, self.block = rate, block
        self.h = LIB.nt_create(rate)
        assert self.h

    def load(self, files, layers, voicing):
        s = LIB.nt_spec_new()
        for f in files:
            LIB.nt_spec_file(s, f.encode())
        for l in layers:
            LIB.nt_spec_layer(s, l.get('file', 0), l.get('bank', 0), l['program'], l.get('transpose', 0),
                              l.get('gainDb', 0.0), l.get('velocityScale', 1.0), l.get('velocityOffset', 0))
        for k, v in voicing.items():
            if not LIB.nt_spec_set(s, k.encode(), float(v)):
                raise KeyError(k)
        ok = LIB.nt_load(self.h, s)
        LIB.nt_spec_free(s)
        if not ok:
            raise RuntimeError(LIB.nt_error().decode())

    def params(self, volume=0.8, brightness=0.0, reverb=0.5, sustain=0.5, hall=False, studio=False, soft=False):
        LIB.nt_params(self.h, volume, brightness, reverb, sustain, int(hall), int(studio), int(soft))

    def note_on(self, k, v): LIB.nt_note_on(self.h, k, v)
    def note_off(self, k): LIB.nt_note_off(self.h, k)
    def sustain(self, d): LIB.nt_sustain(self.h, int(d))
    def voices(self): return LIB.nt_voices(self.h)

    def render(self, seconds):
        n = int(seconds * self.rate)
        out = np.zeros((n, 2), dtype=np.float32)
        buf = np.zeros(self.block * 2, dtype=np.float32)
        p = buf.ctypes.data_as(C.POINTER(C.c_float))
        for i in range(0, n, self.block):
            m = min(self.block, n - i)
            LIB.nt_render(self.h, p, m)
            out[i:i + m] = buf[:m * 2].reshape(m, 2)
        return out

    def play(self, events, tail=2.0):
        """events: list of (time_s, kind, a, b) sorted by time; kind in on/off/sus."""
        chunks, t = [], 0.0
        for when, kind, a, b in events:
            if when > t:
                chunks.append(self.render(when - t))
                t = when
            if kind == 'on':
                self.note_on(a, b)
            elif kind == 'off':
                self.note_off(a)
            elif kind == 'sus':
                self.sustain(a)
        chunks.append(self.render(tail))
        return np.concatenate(chunks)

    def close(self):
        LIB.nt_destroy(self.h)
