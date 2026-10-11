#pragma once
#include <cstdint>
#include "cpu.h"

class APU {
public:
    APU();
    void setBus(Bus* b) { bus = b; }
    void write(uint16_t addr, uint8_t v);
    uint8_t readStatus();
    void tick(int cpuCycles);
    int takeSamples(int16_t* out, int maxCount);

private:
    friend struct StateIO;

    struct Env {
        bool start = false, loop = false, constVol = false;
        uint8_t vol = 0, divider = 0, decay = 0;
        void clock();
        uint8_t out() const { return constVol ? vol : decay; }
    };
    struct Pulse {
        bool enabled = false, ones = false;
        bool sweepEnable = false, sweepNegate = false, sweepReload = false;
        uint8_t duty = 0, seq = 0, length = 0;
        uint8_t sweepPeriod = 0, sweepShift = 0, sweepDivider = 0;
        uint16_t timer = 0, reload = 0;
        Env env;
        uint16_t target() const;
        void clockLength();
        void clockSweep();
        void clockTimer();
        uint8_t out() const;
    };
    struct Triangle {
        bool enabled = false, control = false, reloadFlag = false;
        uint8_t linearVal = 0, linear = 0, length = 0, seq = 0;
        uint16_t timer = 0, reload = 0;
    };
    struct Noise {
        bool enabled = false, mode = false;
        uint8_t idx = 0, length = 0;
        uint16_t timer = 0, sr = 1;
        Env env;
        void clockLength() { if (!env.loop && length) length--; }
        uint8_t out() const { return (length == 0 || (sr & 1)) ? 0 : env.out(); }
    };
    struct DMC {
        bool irqEnable = false, irq = false, loop = false;
        bool bufEmpty = true, silence = true;
        uint8_t rate = 0, level = 0, buffer = 0, shift = 0, bits = 8;
        uint16_t timer = 0, addr = 0xC000, cur = 0xC000;
        uint16_t len = 1, remain = 0;
    };

    void quarter();
    void half();
    void clockDmc();

    Bus* bus = nullptr;
    Pulse p1, p2;
    Triangle tri;
    Noise noi;
    DMC dmc;

    int frameCycle = 0;
    bool mode5 = false, irqInhibit = false, frameIrq = false, odd = false;

    int sampleCounter = 0;
    float acc = 0; int accN = 0;
    float px = 0, py = 0;
    int16_t ring[8192];
    int ringCount = 0;
};
