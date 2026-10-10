#include "apu.h"
#include <cstring>

namespace {
const uint8_t LEN[32] = {10,254,20,2,40,4,80,6,160,8,60,10,14,12,26,14,
                         12,16,24,18,48,20,96,22,192,24,72,26,16,28,32,30};
const uint8_t DUTY[4][8] = {{0,1,0,0,0,0,0,0},{0,1,1,0,0,0,0,0},
                            {0,1,1,1,1,0,0,0},{1,0,0,1,1,1,1,1}};
const uint16_t NOISE_P[16] = {4,8,16,32,64,96,128,160,202,254,380,508,762,1016,2034,4068};
const uint16_t DMC_R[16] = {428,380,340,320,286,254,226,214,190,160,142,128,106,84,72,54};
const uint8_t TRI[32] = {15,14,13,12,11,10,9,8,7,6,5,4,3,2,1,0,
                         0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15};
float pulseTab[31];
float tndTab[203];
bool tabsReady = false;
}

void APU::Env::clock() {
    if (start) {
        start = false; decay = 15; divider = vol;
    } else if (divider == 0) {
        divider = vol;
        if (decay > 0) decay--;
        else if (loop) decay = 15;
    } else {
        divider--;
    }
}

uint16_t APU::Pulse::target() const {
    int change = reload >> sweepShift;
    int t = sweepNegate ? (reload - change - (ones ? 1 : 0)) : (reload + change);
    return t < 0 ? 0 : (uint16_t)t;
}

void APU::Pulse::clockLength() { if (!env.loop && length) length--; }

void APU::Pulse::clockSweep() {
    uint16_t tg = target();
    if (sweepDivider == 0 && sweepEnable && sweepShift && reload >= 8 && tg <= 0x7FF)
        reload = tg;
    if (sweepDivider == 0 || sweepReload) {
        sweepDivider = sweepPeriod;
        sweepReload = false;
    } else {
        sweepDivider--;
    }
}

void APU::Pulse::clockTimer() {
    if (timer == 0) { timer = reload; seq = (seq + 1) & 7; }
    else timer--;
}

uint8_t APU::Pulse::out() const {
    if (!enabled || length == 0 || reload < 8 || target() > 0x7FF || !DUTY[duty][seq]) return 0;
    return env.out();
}

APU::APU() {
    if (!tabsReady) {
        pulseTab[0] = 0;
        for (int i = 1; i < 31; i++) pulseTab[i] = 95.52f / (8128.0f / i + 100.0f);
        tndTab[0] = 0;
        for (int i = 1; i < 203; i++) tndTab[i] = 163.67f / (24329.0f / i + 100.0f);
        tabsReady = true;
    }
    p1.ones = true;
    memset(ring, 0, sizeof(ring));
}

void APU::quarter() {
    p1.env.clock(); p2.env.clock(); noi.env.clock();
    if (tri.reloadFlag) tri.linear = tri.linearVal;
    else if (tri.linear) tri.linear--;
    if (!tri.control) tri.reloadFlag = false;
}

void APU::half() {
    p1.clockLength(); p2.clockLength(); noi.clockLength();
    if (!tri.control && tri.length) tri.length--;
    p1.clockSweep(); p2.clockSweep();
}

void APU::write(uint16_t a, uint8_t v) {
    switch (a) {
    case 0x4000: case 0x4004: {
        Pulse& p = (a == 0x4000) ? p1 : p2;
        p.duty = v >> 6;
        p.env.loop = (v & 0x20) != 0;
        p.env.constVol = (v & 0x10) != 0;
        p.env.vol = v & 0x0F;
        break;
    }
    case 0x4001: case 0x4005: {
        Pulse& p = (a == 0x4001) ? p1 : p2;
        p.sweepEnable = (v & 0x80) != 0;
        p.sweepPeriod = (v >> 4) & 7;
        p.sweepNegate = (v & 8) != 0;
        p.sweepShift = v & 7;
        p.sweepReload = true;
        break;
    }
    case 0x4002: case 0x4006: {
        Pulse& p = (a == 0x4002) ? p1 : p2;
        p.reload = (p.reload & 0x700) | v;
        break;
    }
    case 0x4003: case 0x4007: {
        Pulse& p = (a == 0x4003) ? p1 : p2;
        p.reload = (p.reload & 0xFF) | ((v & 7) << 8);
        if (p.enabled) p.length = LEN[v >> 3];
        p.seq = 0;
        p.env.start = true;
        break;
    }
    case 0x4008:
        tri.control = (v & 0x80) != 0;
        tri.linearVal = v & 0x7F;
        break;
    case 0x400A: tri.reload = (tri.reload & 0x700) | v; break;
    case 0x400B:
        tri.reload = (tri.reload & 0xFF) | ((v & 7) << 8);
        if (tri.enabled) tri.length = LEN[v >> 3];
        tri.reloadFlag = true;
        break;
    case 0x400C:
        noi.env.loop = (v & 0x20) != 0;
        noi.env.constVol = (v & 0x10) != 0;
        noi.env.vol = v & 0x0F;
        break;
    case 0x400E:
        noi.mode = (v & 0x80) != 0;
        noi.idx = v & 0x0F;
        break;
    case 0x400F:
        if (noi.enabled) noi.length = LEN[v >> 3];
        noi.env.start = true;
        break;
    case 0x4010:
        dmc.irqEnable = (v & 0x80) != 0;
        if (!dmc.irqEnable) dmc.irq = false;
        dmc.loop = (v & 0x40) != 0;
        dmc.rate = v & 0x0F;
        break;
    case 0x4011: dmc.level = v & 0x7F; break;
    case 0x4012: dmc.addr = (uint16_t)(0xC000 | (v << 6)); break;
    case 0x4013: dmc.len = (uint16_t)((v << 4) | 1); break;
    case 0x4015:
        p1.enabled = (v & 1) != 0;  if (!p1.enabled) p1.length = 0;
        p2.enabled = (v & 2) != 0;  if (!p2.enabled) p2.length = 0;
        tri.enabled = (v & 4) != 0; if (!tri.enabled) tri.length = 0;
        noi.enabled = (v & 8) != 0; if (!noi.enabled) noi.length = 0;
        dmc.irq = false;
        if (v & 0x10) {
            if (dmc.remain == 0) { dmc.cur = dmc.addr; dmc.remain = dmc.len; }
        } else {
            dmc.remain = 0;
        }
        break;
    case 0x4017:
        mode5 = (v & 0x80) != 0;
        irqInhibit = (v & 0x40) != 0;
        if (irqInhibit) frameIrq = false;
        frameCycle = 0;
        if (mode5) { quarter(); half(); }
        break;
    }
}

uint8_t APU::readStatus() {
    uint8_t r = 0;
    if (p1.length) r |= 1;
    if (p2.length) r |= 2;
    if (tri.length) r |= 4;
    if (noi.length) r |= 8;
    if (dmc.remain) r |= 0x10;
    if (frameIrq) r |= 0x40;
    if (dmc.irq) r |= 0x80;
    frameIrq = false;
    return r;
}

void APU::clockDmc() {
    if (dmc.timer == 0) {
        dmc.timer = DMC_R[dmc.rate];
        if (!dmc.silence) {
            if (dmc.shift & 1) { if (dmc.level <= 125) dmc.level += 2; }
            else { if (dmc.level >= 2) dmc.level -= 2; }
        }
        dmc.shift >>= 1;
        if (--dmc.bits == 0) {
            dmc.bits = 8;
            if (dmc.bufEmpty) {
                dmc.silence = true;
            } else {
                dmc.silence = false;
                dmc.shift = dmc.buffer;
                dmc.bufEmpty = true;
            }
        }
    } else {
        dmc.timer--;
    }

    if (dmc.bufEmpty && dmc.remain > 0 && bus) {
        dmc.buffer = bus->read(dmc.cur);
        dmc.bufEmpty = false;
        dmc.cur = (dmc.cur == 0xFFFF) ? 0x8000 : (uint16_t)(dmc.cur + 1);
        if (--dmc.remain == 0) {
            if (dmc.loop) { dmc.cur = dmc.addr; dmc.remain = dmc.len; }
            else if (dmc.irqEnable) dmc.irq = true;
        }
    }
}

void APU::tick(int n) {
    for (int i = 0; i < n; i++) {
        frameCycle++;
        if (!mode5) {
            if (frameCycle == 7457) quarter();
            else if (frameCycle == 14913) { quarter(); half(); }
            else if (frameCycle == 22371) quarter();
            else if (frameCycle == 29829) { quarter(); half(); if (!irqInhibit) frameIrq = true; }
            else if (frameCycle >= 29830) frameCycle = 0;
        } else {
            if (frameCycle == 7457) quarter();
            else if (frameCycle == 14913) { quarter(); half(); }
            else if (frameCycle == 22371) quarter();
            else if (frameCycle == 37281) { quarter(); half(); }
            else if (frameCycle >= 37282) frameCycle = 0;
        }

        if (tri.timer == 0) {
            tri.timer = tri.reload;
            if (tri.length && tri.linear) tri.seq = (tri.seq + 1) & 31;
        } else {
            tri.timer--;
        }

        if (noi.timer == 0) {
            noi.timer = NOISE_P[noi.idx];
            int fb = (noi.sr & 1) ^ ((noi.mode ? (noi.sr >> 6) : (noi.sr >> 1)) & 1);
            noi.sr = (uint16_t)((noi.sr >> 1) | (fb << 14));
        } else {
            noi.timer--;
        }

        clockDmc();

        if (odd) { p1.clockTimer(); p2.clockTimer(); }
        odd = !odd;

        int pu = p1.out() + p2.out();
        int tnd = 3 * TRI[tri.seq] + 2 * noi.out() + dmc.level;
        acc += pulseTab[pu] + tndTab[tnd];
        accN++;

        sampleCounter += 44100;
        if (sampleCounter >= 1789773) {
            sampleCounter -= 1789773;
            float x = acc / (float)accN;
            acc = 0; accN = 0;
            float y = x - px + 0.996f * py;
            px = x; py = y;
            int s = (int)(y * 30000.0f);
            if (s > 32767) s = 32767;
            if (s < -32768) s = -32768;
            if (ringCount < 8192) ring[ringCount++] = (int16_t)s;
        }
    }
}

int APU::takeSamples(int16_t* out, int maxCount) {
    int n = ringCount < maxCount ? ringCount : maxCount;
    memcpy(out, ring, n * sizeof(int16_t));
    if (n < ringCount) memmove(ring, ring + n, (ringCount - n) * sizeof(int16_t));
    ringCount -= n;
    return n;
}
