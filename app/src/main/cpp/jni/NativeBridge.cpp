// JNI surface for app.nocturne.audio.NativeEngine
#include <jni.h>

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "AudioOutput.h"
#include "engine/Engine.h"
#include "engine/Sf3Transcoder.h"

using namespace nocturne;

namespace {

std::mutex gLock;
std::unique_ptr<Engine> gEngine;
std::unique_ptr<AudioOutput> gOutput;
std::atomic<Engine *> gReady{nullptr};   // published once the engine is fully initialised

void fluidLog(int level, const char *message, void *) {
    const int prio = level <= FLUID_ERR ? ANDROID_LOG_ERROR : level == FLUID_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_DEBUG;
    __android_log_print(prio, "FluidSynth", "%s", message);
}

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

Engine *engine() { return gReady.load(std::memory_order_acquire); }

} // namespace

extern "C" {

JNIEXPORT jint JNICALL Java_app_nocturne_audio_NativeEngine_nativeInit(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> l(gLock);
    if (gEngine) return gEngine->sampleRate();
    fluid_set_log_function(FLUID_PANIC, fluidLog, nullptr);
    fluid_set_log_function(FLUID_ERR, fluidLog, nullptr);
    fluid_set_log_function(FLUID_WARN, fluidLog, nullptr);
    fluid_set_log_function(FLUID_INFO, nullptr, nullptr);
    fluid_set_log_function(FLUID_DBG, nullptr, nullptr);

    auto e = std::make_unique<Engine>();
    auto out = std::make_unique<AudioOutput>(*e);
    int rate = out->open(0);            // device native rate avoids resampling in the HAL
    if (rate <= 0) rate = 48000;
    if (!e->init(rate)) return 0;
    gEngine = std::move(e);
    gOutput = std::move(out);
    gReady.store(gEngine.get(), std::memory_order_release);
    gOutput->start();
    return rate;
}

JNIEXPORT jboolean JNICALL Java_app_nocturne_audio_NativeEngine_nativeStart(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> l(gLock);
    return gOutput && gOutput->start();
}

JNIEXPORT void JNICALL Java_app_nocturne_audio_NativeEngine_nativeStop(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> l(gLock);
    if (gEngine) gEngine->allNotesOff();
    if (gOutput) gOutput->stop();
}

JNIEXPORT void JNICALL Java_app_nocturne_audio_NativeEngine_nativeNoteOn(JNIEnv *, jobject, jint key, jint vel) {
    if (auto *e = engine()) e->noteOn(key, vel);
}

JNIEXPORT void JNICALL Java_app_nocturne_audio_NativeEngine_nativeNoteOff(JNIEnv *, jobject, jint key) {
    if (auto *e = engine()) e->noteOff(key);
}

JNIEXPORT void JNICALL Java_app_nocturne_audio_NativeEngine_nativeSustain(JNIEnv *, jobject, jboolean down) {
    if (auto *e = engine()) e->setSustain(down);
}

JNIEXPORT void JNICALL Java_app_nocturne_audio_NativeEngine_nativeAllNotesOff(JNIEnv *, jobject) {
    if (auto *e = engine()) e->allNotesOff();
}

JNIEXPORT void JNICALL Java_app_nocturne_audio_NativeEngine_nativeSetParams(
        JNIEnv *, jobject, jfloat volume, jfloat brightness, jfloat reverb, jfloat sustain,
        jboolean hall, jboolean studio, jboolean soft) {
    if (auto *e = engine()) {
        MasterParams p;
        p.volume = volume;
        p.brightness = brightness;
        p.reverb = reverb;
        p.sustain = sustain;
        p.concertHall = hall;
        p.studio = studio;
        p.soft = soft;
        e->setMasterParams(p);
    }
}

/**
 * layerInts: per layer [file, bank, program, transpose, velocityOffset]
 * layerFloats: per layer [gainDb, velocityScale]
 * voicing: see NativeEngine.VOICING_KEYS (same order as below)
 * Returns null on success or an error message.
 */
JNIEXPORT jstring JNICALL Java_app_nocturne_audio_NativeEngine_nativeLoadInstrument(
        JNIEnv *env, jobject, jobjectArray files, jintArray layerInts, jfloatArray layerFloats, jfloatArray voicing) {
    Engine *e = engine();
    if (!e) return env->NewStringUTF("engine not initialised");
    InstrumentSpec spec;
    const jsize nFiles = env->GetArrayLength(files);
    for (jsize i = 0; i < nFiles; ++i) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(files, i));
        spec.files.push_back(jstr(env, s));
        env->DeleteLocalRef(s);
    }
    const jsize ni = env->GetArrayLength(layerInts), nf = env->GetArrayLength(layerFloats);
    std::vector<jint> li(ni);
    std::vector<jfloat> lf(nf);
    env->GetIntArrayRegion(layerInts, 0, ni, li.data());
    env->GetFloatArrayRegion(layerFloats, 0, nf, lf.data());
    for (jsize k = 0; k * 5 + 4 < ni && k * 2 + 1 < nf; ++k) {
        LayerSpec l;
        l.file = li[k * 5];
        l.bank = li[k * 5 + 1];
        l.program = li[k * 5 + 2];
        l.transpose = li[k * 5 + 3];
        l.velocityOffset = li[k * 5 + 4];
        l.gainDb = lf[k * 2];
        l.velocityScale = lf[k * 2 + 1];
        spec.layers.push_back(l);
    }
    const jsize nv = env->GetArrayLength(voicing);
    std::vector<jfloat> v(nv);
    env->GetFloatArrayRegion(voicing, 0, nv, v.data());
    float *fields[] = {&spec.filterCents, &spec.filterQ, &spec.decayTimecents, &spec.releaseTimecents,
                       &spec.reverbSend, &spec.chorusSend, &spec.roomSize, &spec.damping, &spec.reverbWidth,
                       &spec.reverbLevel, &spec.chorusDepthMs, &spec.chorusSpeedHz, &spec.chorusLevel,
                       nullptr /* chorusVoices */, &spec.lowShelfDb, &spec.presenceDb, &spec.highShelfDb,
                       &spec.stereoWidth, &spec.outputGainDb};
    const int nFields = int(sizeof(fields) / sizeof(fields[0]));
    for (int k = 0; k < nFields && k < nv; ++k) {
        if (fields[k]) *fields[k] = v[k];
        else spec.chorusVoices = int(v[k]);
    }
    std::string error;
    if (!e->loadInstrument(spec, error)) return env->NewStringUTF(error.c_str());
    return nullptr;
}

JNIEXPORT jstring JNICALL Java_app_nocturne_audio_NativeEngine_nativeTranscode(
        JNIEnv *env, jobject, jobject assetManager, jstring assetPath, jstring inPath, jstring outPath,
        jobject listener) {
    std::string error;
    const std::string out = jstr(env, outPath);

    jmethodID onProgress = nullptr;
    if (listener) {
        jclass cls = env->GetObjectClass(listener);
        onProgress = env->GetMethodID(cls, "onProgress", "(F)V");
        env->DeleteLocalRef(cls);
    }
    float lastReported = -1.0f;
    Sf3Transcoder::Progress progress = [&](float f) {
        if (!listener || !onProgress || f - lastReported < 0.01f) return;
        lastReported = f;
        env->CallVoidMethod(listener, onProgress, f);
        if (env->ExceptionCheck()) env->ExceptionClear();
    };

    bool ok;
    if (assetManager && assetPath) {
        AAssetManager *am = AAssetManager_fromJava(env, assetManager);
        const std::string name = jstr(env, assetPath);
        AAsset *asset = am ? AAssetManager_open(am, name.c_str(), AASSET_MODE_BUFFER) : nullptr;
        if (!asset) return env->NewStringUTF(("missing asset " + name).c_str());
        const void *buf = AAsset_getBuffer(asset);
        const off_t len = AAsset_getLength(asset);
        ok = buf && Sf3Transcoder::transcode(static_cast<const uint8_t *>(buf), size_t(len), out, progress, error);
        if (!buf) error = "could not map asset";
        AAsset_close(asset);
    } else {
        ok = Sf3Transcoder::transcodeFile(jstr(env, inPath), out, progress, error);
    }
    return ok ? nullptr : env->NewStringUTF(error.c_str());
}

/** [sampleRate, framesPerBurst, bufferFrames, xruns, latencyMs, activeVoices, peak, running] */
JNIEXPORT jfloatArray JNICALL Java_app_nocturne_audio_NativeEngine_nativeStats(JNIEnv *env, jobject) {
    float s[8] = {0};
    {
        std::lock_guard<std::mutex> l(gLock);
        if (gEngine && gOutput) {
            s[0] = float(gEngine->sampleRate());
            s[1] = float(gOutput->framesPerBurst());
            s[2] = float(gOutput->bufferSizeFrames());
            s[3] = float(gOutput->xRuns());
            s[4] = float(gOutput->latencyMs());
            s[5] = float(gEngine->activeVoices());
            s[6] = gEngine->peakLevel();
            s[7] = gOutput->isRunning() ? 1.0f : 0.0f;
        }
    }
    jfloatArray arr = env->NewFloatArray(8);
    env->SetFloatArrayRegion(arr, 0, 8, s);
    return arr;
}

} // extern "C"
