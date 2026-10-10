#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <cstdio>
#include "cpu.h"
#include "cartridge.h"
#include "nesbus.h"

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
    char buf[160];
    snprintf(buf, sizeof(buf), "CPU test: %s (cycles=%llu)",
             ok ? "PASS" : "FAIL", (unsigned long long)cpu.cycles);
    return std::string(buf);
}

static std::string loadAndRun(const uint8_t* data, size_t n) {
    Cartridge cart;
    std::string err = cart.load(data, n);
    if (!err.empty()) return "ROM error: " + err;

    char head[128];
    snprintf(head, sizeof(head), "ROM: mapper %d, PRG %dKB, CHR %dKB%s, %s mirroring",
             cart.mapper, (int)(cart.prg.size() / 1024),
             cart.chrRam ? 0 : (int)(cart.chr.size() / 1024),
             cart.chrRam ? " (RAM)" : "",
             cart.vertical ? "vertical" : "horizontal");
    std::string out = head;

    if (cart.mapper != 0) {
        out += "\nMapper not supported yet (only mapper 0 for now)";
        return out;
    }

    // nestest automatic mode: start at $C000, results in $02 and $03
    NesBus bus(&cart);
    CPU cpu(&bus);
    cpu.pc = 0xC000;
    cpu.sp = 0xFD;
    cpu.p = 0x24;
    cpu.cycles = 7;

    int count = 0;
    bool reachedEnd = false;
    while (count < 8991) {
        if (cpu.pc == 0xC6BD) { reachedEnd = true; break; }
        cpu.step();
        count++;
    }

    char res[200];
    snprintf(res, sizeof(res),
             "\nnestest: $02=%02X $03=%02X, %d instructions, pc=%04X%s",
             bus.ram[2], bus.ram[3], count, cpu.pc,
             reachedEnd ? ", reached end of official tests" : "");
    out += res;
    out += (reachedEnd && bus.ram[2] == 0) ? "\nOfficial opcodes: PASS"
                                           : "\nOfficial opcodes: check $02 (00 = pass)";
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pasupathi_nesemu_MainActivity_nativeVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF("v0.3 (cartridge + bus)");
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
    return env->NewStringUTF(loadAndRun(buf.data(), buf.size()).c_str());
}
