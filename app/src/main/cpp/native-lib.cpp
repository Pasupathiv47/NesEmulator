#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <cstring>
#include <cstdio>
#include "cpu.h"
#include "cartridge.h"
#include "ppu.h"
#include "apu.h"
#include "nesbus.h"
#include "state.h"

class TestBus : public Bus {
public:
    uint8_t ram[65536];
    TestBus() { memset(ram, 0, sizeof(ram)); }
    uint8_t read(uint16_t a) override { return ram[a]; }
    void write(uint16_t a, uint8_t v) override { ram[a] = v; }
};

static std::string runCpuTest() {
    TestBus bus;
    const uint8_t prog[] = {
        0xA2, 0x0A, 0xA9, 0x00, 0x86, 0x10, 0x18, 0x65, 0x10,
        0xCA, 0xD0, 0xF8, 0x8D, 0x00, 0x02, 0x20, 0x20, 0x06, 0x00
    };
    const uint8_t sub[] = {0xA9, 0x42, 0x8D, 0x01, 0x02, 0x60};
    memcpy(&bus.ram[0x0600], prog, sizeof(prog));
    memcpy(&bus.ram[0x0620], sub, sizeof(sub));
    bus.ram[0xFFFC] = 0x00;
    bus.ram[0xFFFD] = 0x06;

    CPU cpu(&bus);
    cpu.reset();
    int guard = 0;
    while (cpu.pc != 0x0612 && guard++ < 1000) cpu.step();

    bool ok = bus.ram[0x200] == 0x37 && bus.ram[0x201] == 0x42 && cpu.pc == 0x0612;
    return ok ? "CPU test: PASS" : "CPU test: FAIL";
}

struct Emu {
    Cartridge cart;
    PPU ppu;
    APU apu;
    NesBus bus;
    CPU cpu;
    Emu() : ppu(&cart), bus(&cart, &ppu, &apu), cpu(&bus) { apu.setBus(&bus); }
};

static std::unique_ptr<Emu> g_emu;

static void runFrame(Emu& e) {
    int guard = 0;
    while (!e.ppu.frameReady && guard++ < 200000) {
        int cyc = e.cpu.step();
        cyc += e.bus.stall;
        e.bus.stall = 0;
        e.apu.tick(cyc);
        for (int i = 0; i < cyc * 3; i++) e.ppu.tick();
        if (e.ppu.nmiPending) {
            e.ppu.nmiPending = false;
            e.cpu.nmi();
            e.apu.tick(7);
            for (int i = 0; i < 21; i++) e.ppu.tick();
        }
    }
    e.ppu.frameReady = false;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pasupathi_nesemu_MainActivity_nativeVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF("v0.6 (save states)");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pasupathi_nesemu_MainActivity_nativeCpuTest(JNIEnv *env, jobject) {
    return env->NewStringUTF(runCpuTest().c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pasupathi_nesemu_MainActivity_nativeLoadRom(JNIEnv *env, jobject, jbyteArray data) {
    jsize len = env->GetArrayLength(data);
    std::vector<uint8_t> buf((size_t)len);
    env->GetByteArrayRegion(data, 0, len, reinterpret_cast<jbyte*>(buf.data()));

    g_emu.reset();
    std::unique_ptr<Emu> e(new Emu());
    std::string err = e->cart.load(buf.data(), buf.size());
    std::string out;
    if (!err.empty()) {
        out = "ROM error: " + err;
    } else if (e->cart.mapper != 0) {
        out = "Mapper " + std::to_string(e->cart.mapper) + " is not supported yet (only mapper 0)";
    } else {
        e->cpu.reset();
        char head[160];
        snprintf(head, sizeof(head), "ROM: mapper %d, PRG %dKB, CHR %dKB%s, %s mirroring",
                 e->cart.mapper, (int)(e->cart.prg.size() / 1024),
                 e->cart.chrRam ? 0 : (int)(e->cart.chr.size() / 1024),
                 e->cart.chrRam ? " (RAM)" : "",
                 e->cart.vertical ? "vertical" : "horizontal");
        out = head;
        g_emu = std::move(e);
    }
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pasupathi_nesemu_MainActivity_nativeRunFrame(JNIEnv *env, jobject,
                                                      jintArray pixels, jshortArray audio) {
    if (!g_emu) return 0;
    runFrame(*g_emu);
    env->SetIntArrayRegion(pixels, 0, 256 * 240,
                           reinterpret_cast<const jint*>(g_emu->ppu.fb));
    int16_t tmp[2048];
    int n = g_emu->apu.takeSamples(tmp, 2048);
    env->SetShortArrayRegion(audio, 0, n, reinterpret_cast<const jshort*>(tmp));
    return n;
}

extern "C" JNIEXPORT void JNICALL
Java_com_pasupathi_nesemu_MainActivity_nativeSetButtons(JNIEnv *env, jobject, jint player, jint mask) {
    if (!g_emu || player < 0 || player > 1) return;
    g_emu->bus.pad[player].store((uint8_t)mask);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_pasupathi_nesemu_MainActivity_nativeSaveState(JNIEnv *env, jobject) {
    if (!g_emu) return nullptr;
    std::vector<uint8_t> out;
    StateIO::save(out, g_emu->cpu, g_emu->ppu, g_emu->apu, g_emu->bus, g_emu->cart);
    jbyteArray arr = env->NewByteArray((jsize)out.size());
    env->SetByteArrayRegion(arr, 0, (jsize)out.size(), reinterpret_cast<const jbyte*>(out.data()));
    return arr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pasupathi_nesemu_MainActivity_nativeLoadState(JNIEnv *env, jobject, jbyteArray data) {
    if (!g_emu) return JNI_FALSE;
    jsize len = env->GetArrayLength(data);
    std::vector<uint8_t> buf((size_t)len);
    env->GetByteArrayRegion(data, 0, len, reinterpret_cast<jbyte*>(buf.data()));
    bool ok = StateIO::load(buf.data(), buf.size(), g_emu->cpu, g_emu->ppu,
                            g_emu->apu, g_emu->bus, g_emu->cart);
    return ok ? JNI_TRUE : JNI_FALSE;
}
