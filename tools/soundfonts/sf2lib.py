"""Minimal SoundFont 2 / SF3 reader and writer used to build Nocturne's sound packs.

Only the parts of the SF2 2.04 specification that Nocturne needs are modelled:
presets, instruments, zones (generators + modulators) and 16-bit samples.
SF3 files (MuseScore flavour: every sample is an independent Ogg Vorbis stream)
are decoded on read and can be produced on write.
"""
from __future__ import annotations

import io
import struct
from dataclasses import dataclass, field

import numpy as np
import soundfile as sf

# Generator ids (SF2.04 section 8.1.2)
G = dict(
    startAddrsOffset=0, endAddrsOffset=1, startloopAddrsOffset=2, endloopAddrsOffset=3,
    startAddrsCoarseOffset=4, modLfoToPitch=5, vibLfoToPitch=6, modEnvToPitch=7,
    initialFilterFc=8, initialFilterQ=9, modLfoToFilterFc=10, modEnvToFilterFc=11,
    endAddrsCoarseOffset=12, modLfoToVolume=13, chorusEffectsSend=15, reverbEffectsSend=16,
    pan=17, delayModLFO=21, freqModLFO=22, delayVibLFO=23, freqVibLFO=24, delayModEnv=25,
    attackModEnv=26, holdModEnv=27, decayModEnv=28, sustainModEnv=29, releaseModEnv=30,
    keynumToModEnvHold=31, keynumToModEnvDecay=32, delayVolEnv=33, attackVolEnv=34,
    holdVolEnv=35, decayVolEnv=36, sustainVolEnv=37, releaseVolEnv=38,
    keynumToVolEnvHold=39, keynumToVolEnvDecay=40, instrument=41, keyRange=43, velRange=44,
    startloopAddrsCoarseOffset=45, keynum=46, velocity=47, initialAttenuation=48,
    endloopAddrsCoarseOffset=50, coarseTune=51, fineTune=52, sampleID=53, sampleModes=54,
    scaleTuning=56, exclusiveClass=57, overridingRootKey=58,
)
RANGE_GENS = (G['keyRange'], G['velRange'])

MONO, RIGHT, LEFT, LINKED = 1, 2, 4, 8
COMPRESSED = 0x10


@dataclass
class Sample:
    name: str
    data: np.ndarray            # int16 mono
    rate: int = 44100
    root: int = 60
    correction: int = 0
    loop: tuple[int, int] | None = None   # (start, end) in frames relative to sample
    stype: int = MONO
    link: 'Sample | None' = None


@dataclass
class Modulator:
    src: int
    dest: int
    amount: int
    amt_src: int = 0
    trans: int = 0


@dataclass
class Zone:
    gens: dict[int, int | tuple[int, int]] = field(default_factory=dict)
    mods: list[Modulator] = field(default_factory=list)
    target: object = None       # Sample (instrument zone) or Instrument (preset zone); None = global


@dataclass
class Instrument:
    name: str
    zones: list[Zone] = field(default_factory=list)


@dataclass
class Preset:
    name: str
    program: int
    bank: int
    zones: list[Zone] = field(default_factory=list)


@dataclass
class SoundFont:
    name: str = 'Untitled'
    info: dict[str, str] = field(default_factory=dict)
    presets: list[Preset] = field(default_factory=list)


# ---------------------------------------------------------------- reading

def _chunks(d, off, end):
    while off + 8 <= end:
        cid = d[off:off + 4].decode('latin1')
        sz = struct.unpack_from('<I', d, off + 4)[0]
        yield cid, off + 8, sz
        off += 8 + sz + (sz & 1)


def _cstr(b):
    return b.split(b'\0')[0].decode('latin1').strip()


def read(path) -> SoundFont:
    d = open(path, 'rb').read()
    assert d[:4] == b'RIFF' and d[8:12] == b'sfbk', 'not a SoundFont'
    lists = {}
    for cid, o, sz in _chunks(d, 12, len(d)):
        if cid == 'LIST':
            lists[d[o:o + 4].decode()] = {c: (oo, ss) for c, oo, ss in _chunks(d, o + 4, o + sz)}
    info = {c: _cstr(d[o:o + s]) for c, (o, s) in lists['INFO'].items() if c != 'ifil' and c != 'iver'}
    so, ss = lists['sdta']['smpl']
    smpl = d[so:so + ss]
    pd = lists['pdta']

    def recs(name, size):
        o, s = pd[name]
        return [d[o + i * size:o + (i + 1) * size] for i in range(s // size)]

    shdr = recs('shdr', 46)[:-1]
    samples = []
    raw = []
    for r in shdr:
        name, start, end, ls, le, rate, root, corr, link, stype = struct.unpack('<20sIIIIIBbHH', r)
        raw.append((start, end, ls, le, link))
        if stype & COMPRESSED:
            pcm, _ = sf.read(io.BytesIO(smpl[start:end]), dtype='int16')
            if pcm.ndim > 1:
                pcm = pcm[:, 0]
            loop = (ls, le) if le > ls else None
        else:
            pcm = np.frombuffer(smpl, dtype='<i2', count=end - start, offset=start * 2).copy()
            loop = (ls - start, le - start) if le > ls else None
        samples.append(Sample(_cstr(name), pcm, rate, root, corr, loop, stype & ~COMPRESSED & 0xF))
    for s, (_, _, _, _, link) in zip(samples, raw):
        if s.stype in (LEFT, RIGHT) and link < len(samples):
            s.link = samples[link]

    def zones_of(hdr_recs, bag_name, gen_name, mod_name, targets, target_gen):
        bags = [struct.unpack('<HH', r) for r in recs(bag_name, 4)]
        gens = [struct.unpack('<HH', r) for r in recs(gen_name, 4)]
        mods = [struct.unpack('<HHhHH', r) for r in recs(mod_name, 10)]
        out = []
        for i in range(len(hdr_recs) - 1):
            b0, b1 = hdr_recs[i][1], hdr_recs[i + 1][1]
            zs = []
            for b in range(b0, b1):
                g0, m0 = bags[b]
                g1, m1 = bags[b + 1]
                z = Zone()
                for oper, amt in gens[g0:g1]:
                    if oper in RANGE_GENS:
                        z.gens[oper] = (amt & 0xFF, amt >> 8)
                    elif oper == target_gen:
                        z.target = targets[amt]
                    else:
                        z.gens[oper] = amt - 65536 if amt >= 32768 else amt
                z.mods = [Modulator(*m) for m in mods[m0:m1]]
                zs.append(z)
            out.append(zs)
        return out

    inst_hdr = [(_cstr(r[:20]), struct.unpack('<H', r[20:22])[0]) for r in recs('inst', 22)]
    inst_zones = zones_of(inst_hdr, 'ibag', 'igen', 'imod', samples, G['sampleID'])
    instruments = [Instrument(n, z) for (n, _), z in zip(inst_hdr, inst_zones)]

    phdr = []
    for r in recs('phdr', 38):
        name, prog, bank, bag = struct.unpack('<20sHHH', r[:26])
        phdr.append((_cstr(name), bag, prog, bank))
    pz = zones_of([(n, b) for n, b, _, _ in phdr], 'pbag', 'pgen', 'pmod', instruments, G['instrument'])
    presets = [Preset(n, p, b, z) for (n, _, p, b), z in zip(phdr, pz)]
    return SoundFont(info.get('INAM', 'Untitled'), info, presets)


def find_preset(sfont: SoundFont, bank: int, program: int) -> Preset:
    for p in sfont.presets:
        if p.bank == bank and p.program == program:
            return p
    raise KeyError((bank, program))


# ---------------------------------------------------------------- writing

def _encode_ogg(pcm: np.ndarray, rate: int, quality: float) -> bytes:
    buf = io.BytesIO()
    # soundfile maps compression_level 0..1 onto libvorbis quality 1..0 (inverted)
    sf.write(buf, pcm.astype(np.int16), rate, format='OGG', subtype='VORBIS',
             compression_level=max(0.0, min(1.0, 1.0 - quality)))
    return buf.getvalue()


def _name20(s):
    return s.encode('latin1', 'replace')[:19].ljust(20, b'\0')


def write(sfont: SoundFont, path, compress: bool = False, quality: float = 0.6, log=print):
    """Write sfont. When compress=True an SF3 file is produced (Ogg Vorbis per sample)."""
    # Collect objects reachable from the presets, in a stable order.
    instruments: list[Instrument] = []
    samples: list[Sample] = []
    for p in sfont.presets:
        for z in p.zones:
            if z.target is not None and all(z.target is not i for i in instruments):
                instruments.append(z.target)
    for inst in instruments:
        for z in inst.zones:
            s = z.target
            if s is not None:
                for t in (s, s.link):
                    if t is not None and all(t is not x for x in samples):
                        samples.append(t)
    sidx = {id(s): i for i, s in enumerate(samples)}
    iidx = {id(i): n for n, i in enumerate(instruments)}

    smpl = bytearray()
    shdr = bytearray()
    for n, s in enumerate(samples):
        stype = s.stype if s.link is not None else MONO
        link = sidx[id(s.link)] if s.link is not None else 0
        if compress:
            blob = _encode_ogg(s.data, s.rate, quality)
            start = len(smpl)
            smpl += blob
            end = len(smpl)
            ls, le = s.loop if s.loop else (0, 0)
            stype |= COMPRESSED
        else:
            start = len(smpl) // 2
            smpl += s.data.astype('<i2').tobytes()
            end = len(smpl) // 2
            smpl += b'\0' * 92          # 46 zero frames of padding as required by the spec
            ls, le = (start + s.loop[0], start + s.loop[1]) if s.loop else (start, start)
        shdr += struct.pack('<20sIIIIIBbHH', _name20(s.name), start, end, ls, le, s.rate,
                            s.root, s.correction, link, stype)
        if log and n % 50 == 0:
            log(f'  sample {n + 1}/{len(samples)}')
    if len(smpl) & 1:
        smpl += b'\0'
    shdr += struct.pack('<20sIIIIIBbHH', _name20('EOS'), 0, 0, 0, 0, 0, 0, 0, 0, 0)


    def build(headers, target_gen, index_of):
        bag, gen, mod = bytearray(), bytearray(), bytearray()
        gi = mi = bi = 0
        hdr_bags = []
        for zones in headers:
            hdr_bags.append(bi)
            for z in zones:
                bag += struct.pack('<HH', gi, mi)
                bi += 1
                items = []
                for k in RANGE_GENS:
                    if k in z.gens:
                        lo, hi = z.gens[k]
                        items.append((k, lo | (hi << 8)))
                for k, v in sorted(z.gens.items()):
                    if k in RANGE_GENS or k in (G['instrument'], G['sampleID']):
                        continue
                    items.append((k, v & 0xFFFF))
                if z.target is not None:
                    items.append((target_gen, index_of(z.target)))
                for k, v in items:
                    gen += struct.pack('<HH', k, v)
                    gi += 1
                for m in z.mods:
                    mod += struct.pack('<HHhHH', m.src, m.dest, m.amount, m.amt_src, m.trans)
                    mi += 1
        hdr_bags.append(bi)
        bag += struct.pack('<HH', gi, mi)
        gen += struct.pack('<HH', 0, 0)
        mod += struct.pack('<HHhHH', 0, 0, 0, 0, 0)
        return bag, gen, mod, hdr_bags

    ibag, igen, imod, ibags = build([i.zones for i in instruments], G['sampleID'], lambda s: sidx[id(s)])
    inst = bytearray()
    for i, b in zip(instruments + [None], ibags):
        inst += struct.pack('<20sH', _name20(i.name if i else 'EOI'), b)

    presets = sorted(sfont.presets, key=lambda p: (p.bank, p.program))
    pbag, pgen, pmod, pbags = build([p.zones for p in presets], G['instrument'], lambda i: iidx[id(i)])
    phdr = bytearray()
    for p, b in zip(presets + [None], pbags):
        if p:
            phdr += struct.pack('<20sHHHIII', _name20(p.name), p.program, p.bank, b, 0, 0, 0)
        else:
            phdr += struct.pack('<20sHHHIII', _name20('EOP'), 0, 0, b, 0, 0, 0)

    def chunk(cid, payload):
        payload = bytes(payload)
        pad = b'\0' if len(payload) & 1 else b''
        return cid.encode() + struct.pack('<I', len(payload)) + payload + pad

    def zstr(s):
        b = s.encode('latin1', 'replace') + b'\0'
        return b + (b'\0' if len(b) & 1 else b'')

    info = chunk('ifil', struct.pack('<HH', 3 if compress else 2, 1 if compress else 4))
    info += chunk('isng', zstr('EMU8000'))
    info += chunk('INAM', zstr(sfont.name))
    for k in ('ICOP', 'ICMT', 'IENG', 'ISFT'):
        if sfont.info.get(k):
            info += chunk(k, zstr(sfont.info[k][:65000]))
    body = chunk('LIST', b'INFO' + info)
    body += chunk('LIST', b'sdta' + chunk('smpl', smpl))
    pdta = (chunk('phdr', phdr) + chunk('pbag', pbag) + chunk('pmod', pmod) + chunk('pgen', pgen)
            + chunk('inst', inst) + chunk('ibag', ibag) + chunk('imod', imod) + chunk('igen', igen)
            + chunk('shdr', shdr))
    body += chunk('LIST', b'pdta' + pdta)
    with open(path, 'wb') as f:
        f.write(b'RIFF' + struct.pack('<I', len(body) + 4) + b'sfbk' + body)
    return len(samples)
