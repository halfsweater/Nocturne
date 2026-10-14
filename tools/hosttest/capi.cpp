// C API over the Nocturne engine, used only by the host-side test harness (ctypes).
#include <cstring>
#include <string>

#include "../../app/src/main/cpp/engine/Engine.h"
#include "../../app/src/main/cpp/engine/Sf3Transcoder.h"

using namespace nocturne;

static std::string g_error;

extern "C" {

void *nt_create(int rate) {
    auto *e = new Engine();
    if (!e->init(rate)) { delete e; return nullptr; }
    return e;
}
void nt_destroy(void *e) { delete static_cast<Engine *>(e); }

void *nt_spec_new() { return new InstrumentSpec(); }
void nt_spec_free(void *s) { delete static_cast<InstrumentSpec *>(s); }
void nt_spec_file(void *s, const char *path) { static_cast<InstrumentSpec *>(s)->files.emplace_back(path); }
void nt_spec_layer(void *s, int file, int bank, int program, int transpose, float gainDb, float velScale, int velOffset) {
    LayerSpec l{file, bank, program, transpose, gainDb, velScale, velOffset};
    static_cast<InstrumentSpec *>(s)->layers.push_back(l);
}
int nt_spec_set(void *sp, const char *key, float v) {
    auto *s = static_cast<InstrumentSpec *>(sp);
#define F(name) if (!strcmp(key, #name)) { s->name = v; return 1; }
    F(filterCents) F(filterQ) F(decayTimecents) F(releaseTimecents) F(reverbSend) F(chorusSend)
    F(roomSize) F(damping) F(reverbWidth) F(reverbLevel) F(chorusDepthMs) F(chorusSpeedHz) F(chorusLevel)
    F(lowShelfDb) F(presenceDb) F(highShelfDb) F(stereoWidth) F(outputGainDb)
#undef F
    if (!strcmp(key, "chorusVoices")) { s->chorusVoices = int(v); return 1; }
    return 0;
}
int nt_load(void *e, void *spec) {
    g_error.clear();
    return static_cast<Engine *>(e)->loadInstrument(*static_cast<InstrumentSpec *>(spec), g_error) ? 1 : 0;
}
const char *nt_error() { return g_error.c_str(); }
void nt_note_on(void *e, int k, int v) { static_cast<Engine *>(e)->noteOn(k, v); }
void nt_note_off(void *e, int k) { static_cast<Engine *>(e)->noteOff(k); }
void nt_sustain(void *e, int d) { static_cast<Engine *>(e)->setSustain(d != 0); }
void nt_all_off(void *e) { static_cast<Engine *>(e)->allNotesOff(); }
void nt_params(void *e, float volume, float brightness, float reverb, float sustain, int hall, int studio, int soft) {
    MasterParams p{volume, brightness, reverb, sustain, hall != 0, studio != 0, soft != 0};
    static_cast<Engine *>(e)->setMasterParams(p);
}
void nt_render(void *e, float *out, int frames) { static_cast<Engine *>(e)->render(out, frames); }
int nt_voices(void *e) { return static_cast<Engine *>(e)->activeVoices(); }
int nt_transcode(const char *in, const char *out) {
    g_error.clear();
    return Sf3Transcoder::transcodeFile(in, out, nullptr, g_error) ? 1 : 0;
}
}
