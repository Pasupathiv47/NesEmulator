#pragma once
#include <cstdint>
#include <cstring>
#include "cartridge.h"

class PPU {
public:
    explicit PPU(Cartridge* c);
    void reset();
    uint8_t readReg(uint16_t reg);
    void writeReg(uint16_t reg, uint8_t val);
    void tick();   // one PPU dot

    uint8_t oam[256];
    uint8_t oamAddr = 0;
    bool nmiPending = false;
    bool frameReady = false;
    uint32_t fb[256 * 240];

private:
    Cartridge* cart;
    uint8_t vram[2048];
    uint8_t pal[32];
    uint8_t ctrl = 0, mask = 0, status = 0, latch = 0, readBuf = 0;
    uint16_t v = 0, t = 0;
    uint8_t fineX = 0;
    bool w = false;
    int scanline = 261, dot = 0;
    int s0HitDot = -1;

    uint16_t ntOffset(uint16_t a) const;
    uint8_t rd(uint16_t a);
    void wr(uint16_t a, uint8_t val);
    uint32_t color(uint8_t idx) const;
    void renderLine(int line);
    void incY();
};
