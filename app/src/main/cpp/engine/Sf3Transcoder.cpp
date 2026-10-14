#include "Sf3Transcoder.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <vector>

#define STB_VORBIS_NO_STDIO 1
#define STB_VORBIS_NO_PUSHDATA_API 1
#define STB_VORBIS_HEADER_ONLY 1
#include "../third_party/stb_vorbis.c"

namespace nocturne {
namespace {

constexpr uint16_t kTypeOggVorbis = 0x10;
constexpr uint16_t kTypeRom = 0x8000;
constexpr size_t kShdrSize = 46;
constexpr uint32_t kPaddingFrames = 46;
constexpr uint32_t kLoopSeamFrames = 128;

/**
 * Lossy coding leaves the two sides of a loop seam with slightly different
 * quantisation noise, which is audible as a faint tick every time a sustained
 * note wraps. The packs are built with a phase-matched crossfade, so the decoded
 * audio just before the loop end and just before the loop start are near-identical;
 * blending them over a few milliseconds restores a seamless wrap.
 */
void healLoopSeam(int16_t *pcm, uint32_t frames, uint32_t ls, uint32_t le) {
    if (le > frames || le <= ls || le - ls < 2 * kLoopSeamFrames || ls < kLoopSeamFrames) return;
    for (uint32_t i = 0; i < kLoopSeamFrames; ++i) {
        const float w = float(i + 1) / float(kLoopSeamFrames);
        const float a = pcm[le - kLoopSeamFrames + i], b = pcm[ls - kLoopSeamFrames + i];
        pcm[le - kLoopSeamFrames + i] = int16_t(std::lround(a + (b - a) * w));
    }
    // Interpolation reads a few samples past the loop end: make them the loop start.
    for (uint32_t i = 0; i < 8 && le + i < frames; ++i) pcm[le + i] = pcm[ls + i];
}

uint32_t rd32(const uint8_t *p) { return p[0] | (p[1] << 8) | (p[2] << 16) | (uint32_t(p[3]) << 24); }
uint16_t rd16(const uint8_t *p) { return uint16_t(p[0] | (p[1] << 8)); }
void wr32(uint8_t *p, uint32_t v) { p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24; }
void wr16(uint8_t *p, uint16_t v) { p[0] = v; p[1] = v >> 8; }

struct Span {
    char id[5] = {0};
    const uint8_t *data = nullptr;
    uint32_t size = 0;
};

bool readChunks(const uint8_t *begin, const uint8_t *end, std::vector<Span> &out) {
    const uint8_t *p = begin;
    while (p + 8 <= end) {
        Span s;
        memcpy(s.id, p, 4);
        s.size = rd32(p + 4);
        s.data = p + 8;
        if (s.data + s.size > end) return false;
        out.push_back(s);
        p = s.data + s.size + (s.size & 1);
    }
    return true;
}

struct Writer {
    FILE *f = nullptr;
    bool ok = true;
    void put(const void *d, size_t n) {
        if (ok && n && fwrite(d, 1, n, f) != n) ok = false;
    }
    void put32(uint32_t v) { uint8_t b[4]; wr32(b, v); put(b, 4); }
    void tag(const char *t) { put(t, 4); }
    long pos() { return ftell(f); }
    void patch32(long at, uint32_t v) {
        long here = ftell(f);
        fseek(f, at, SEEK_SET);
        put32(v);
        fseek(f, here, SEEK_SET);
    }
    void chunk(const char *id, const uint8_t *d, uint32_t n) {
        tag(id);
        put32(n);
        put(d, n);
        if (n & 1) { uint8_t z = 0; put(&z, 1); }
    }
};

} // namespace

bool Sf3Transcoder::transcode(const uint8_t *data, size_t size, const std::string &outPath,
                              const Progress &progress, std::string &error) {
    if (size < 12 || memcmp(data, "RIFF", 4) != 0 || memcmp(data + 8, "sfbk", 4) != 0) {
        error = "not a SoundFont file";
        return false;
    }
    const uint8_t *end = data + std::min<size_t>(size, 8 + size_t(rd32(data + 4)));
    std::vector<Span> top;
    if (!readChunks(data + 12, end, top)) { error = "truncated RIFF"; return false; }

    std::vector<Span> info, pdta;
    Span smpl;
    for (auto &c : top) {
        if (strcmp(c.id, "LIST") != 0 || c.size < 4) continue;
        std::vector<Span> sub;
        if (!readChunks(c.data + 4, c.data + c.size, sub)) { error = "bad LIST"; return false; }
        if (memcmp(c.data, "INFO", 4) == 0) info = sub;
        else if (memcmp(c.data, "pdta", 4) == 0) pdta = sub;
        else if (memcmp(c.data, "sdta", 4) == 0)
            for (auto &s : sub) if (strcmp(s.id, "smpl") == 0) smpl = s;
    }
    const Span *shdr = nullptr;
    for (auto &s : pdta) if (strcmp(s.id, "shdr") == 0) shdr = &s;
    if (!shdr || shdr->size < kShdrSize || pdta.empty()) { error = "missing pdta/shdr"; return false; }

    const size_t nRecords = shdr->size / kShdrSize;
    std::vector<uint8_t> newShdr(shdr->data, shdr->data + nRecords * kShdrSize);

    const std::string partPath = outPath + ".part";
    Writer w;
    w.f = fopen(partPath.c_str(), "wb");
    if (!w.f) { error = "cannot create " + partPath; return false; }

    w.tag("RIFF");
    long riffSizeAt = w.pos();
    w.put32(0);
    w.tag("sfbk");

    // INFO, with the version bumped down to 2.01 since samples are now PCM.
    w.tag("LIST");
    long infoSizeAt = w.pos();
    w.put32(0);
    w.tag("INFO");
    for (auto &s : info) {
        if (strcmp(s.id, "ifil") == 0 && s.size >= 4) {
            uint8_t v[4];
            wr16(v, 2);
            wr16(v + 2, 1);
            w.chunk("ifil", v, 4);
        } else {
            w.chunk(s.id, s.data, s.size);
        }
    }
    w.patch32(infoSizeAt, uint32_t(w.pos() - infoSizeAt - 4));

    w.tag("LIST");
    long sdtaSizeAt = w.pos();
    w.put32(0);
    w.tag("sdta");
    w.tag("smpl");
    long smplSizeAt = w.pos();
    w.put32(0);

    static const int16_t zeros[kPaddingFrames] = {0};
    uint32_t frame = 0;
    const size_t nSamples = nRecords > 0 ? nRecords - 1 : 0; // last record is EOS
    for (size_t i = 0; i < nSamples && w.ok; ++i) {
        uint8_t *rec = newShdr.data() + i * kShdrSize;
        uint32_t start = rd32(rec + 20), stop = rd32(rec + 24);
        uint32_t loopStart = rd32(rec + 28), loopEnd = rd32(rec + 32);
        uint16_t type = rd16(rec + 44);
        uint32_t newStart = frame, frames = 0;
        uint32_t relLs = 0, relLe = 0;

        if (type & kTypeRom) {
            frames = 0;
        } else if (type & kTypeOggVorbis) {
            if (stop <= start || stop > smpl.size) { error = "bad compressed sample bounds"; w.ok = false; break; }
            int channels = 0, rate = 0;
            short *pcm = nullptr;
            int n = stb_vorbis_decode_memory(smpl.data + start, int(stop - start), &channels, &rate, &pcm);
            if (n < 0 || !pcm || channels < 1) { error = "Ogg Vorbis decode failed"; free(pcm); w.ok = false; break; }
            std::vector<int16_t> mono(static_cast<size_t>(n));
            for (int k = 0; k < n; ++k) mono[size_t(k)] = pcm[size_t(k) * size_t(channels)];
            free(pcm);
            frames = uint32_t(n);
            relLs = loopStart;   // SF3 loop points are relative to the decoded sample
            relLe = loopEnd;
            healLoopSeam(mono.data(), frames, relLs, relLe);
            w.put(mono.data(), size_t(n) * 2);
        } else {
            if (stop < start || size_t(stop) * 2 > smpl.size) { error = "bad sample bounds"; w.ok = false; break; }
            frames = stop - start;
            w.put(smpl.data + size_t(start) * 2, size_t(frames) * 2);
            relLs = loopStart >= start ? loopStart - start : 0;
            relLe = loopEnd >= start ? loopEnd - start : 0;
        }
        if (relLe > frames) relLe = frames;
        if (relLs > relLe) relLs = relLe;
        w.put(zeros, sizeof(zeros));
        frame += frames + kPaddingFrames;

        wr32(rec + 20, newStart);
        wr32(rec + 24, newStart + frames);
        wr32(rec + 28, newStart + relLs);
        wr32(rec + 32, newStart + relLe);
        wr16(rec + 44, uint16_t(type & ~(kTypeOggVorbis | 0x20 | 0x40)));
        if (progress && (i % 8 == 0 || i + 1 == nSamples)) progress(float(i + 1) / float(nSamples));
    }
    if (!w.ok) {
        if (error.empty()) error = "write failed (disk full?)";
        fclose(w.f);
        remove(partPath.c_str());
        return false;
    }
    w.patch32(smplSizeAt, uint32_t(w.pos() - smplSizeAt - 4));
    w.patch32(sdtaSizeAt, uint32_t(w.pos() - sdtaSizeAt - 4));

    w.tag("LIST");
    long pdtaSizeAt = w.pos();
    w.put32(0);
    w.tag("pdta");
    for (auto &s : pdta) {
        if (strcmp(s.id, "shdr") == 0) w.chunk("shdr", newShdr.data(), uint32_t(newShdr.size()));
        else w.chunk(s.id, s.data, s.size);
    }
    w.patch32(pdtaSizeAt, uint32_t(w.pos() - pdtaSizeAt - 4));
    w.patch32(riffSizeAt, uint32_t(w.pos() - 8));

    bool ok = w.ok && fflush(w.f) == 0;
    ok = (fclose(w.f) == 0) && ok;
    if (!ok || rename(partPath.c_str(), outPath.c_str()) != 0) {
        remove(partPath.c_str());
        error = "could not finalise " + outPath;
        return false;
    }
    return true;
}

bool Sf3Transcoder::transcodeFile(const std::string &inPath, const std::string &outPath,
                                  const Progress &progress, std::string &error) {
    FILE *f = fopen(inPath.c_str(), "rb");
    if (!f) { error = "cannot open " + inPath; return false; }
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (n <= 0) { fclose(f); error = "empty file"; return false; }
    std::unique_ptr<uint8_t[]> buf(new (std::nothrow) uint8_t[size_t(n)]);
    if (!buf) { fclose(f); error = "out of memory"; return false; }
    size_t got = fread(buf.get(), 1, size_t(n), f);
    fclose(f);
    if (got != size_t(n)) { error = "short read"; return false; }
    return transcode(buf.get(), size_t(n), outPath, progress, error);
}

} // namespace nocturne
