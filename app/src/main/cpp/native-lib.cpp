#include <jni.h>
#include <string>
#include <cstring>
#include <cstdio>
#include "cpu.h"

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
        0xA2, 0x0A,        // LDX #10
        0xA9, 0x00,        // LDA #0
        0x86, 0x10,        // loop: STX $10
        0x18,              // CLC
        0x65, 0x10,        // ADC $10
        0xCA,              // DEX
        0xD0, 0xF8,        // BNE loop
        0x8D, 0x00, 0x02,  // STA $0200
        0x20, 0x20, 0x06,  // JSR $0620
        0x00               // BRK (test stops before this)
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
    snprintf(buf, sizeof(buf), "CPU test: %s (sum=%d expected 55, [0201]=0x%02X expected 0x42, cycles=%llu)",
             ok ? "PASS" : "FAIL", bus.ram[0x200], bus.ram[0x201],
             (unsigned long long)cpu.cycles);
    return std::string(buf);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pasupathi_nesemu_MainActivity_nativeVersion(JNIEnv *env, jobject) {
    return env->NewStringUTF("v0.2 (6502 CPU)");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pasupathi_nesemu_MainActivity_nativeCpuTest(JNIEnv *env, jobject) {
    return env->NewStringUTF(runCpuTest().c_str());
}
