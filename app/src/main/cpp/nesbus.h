#pragma once
#include <cstring>
#include "cpu.h"
#include "cartridge.h"

class NesBus : public Bus {
public:
    explicit NesBus(Cartridge* c) : cart(c) {
        memset(ram, 0, sizeof(ram));
        memset(prgRam, 0, sizeof(prgRam));
    }

    uint8_t read(uint16_t a) override {
        if (a < 0x2000) return ram[a & 0x7FF];
        if (a < 0x4000) return 0;               // PPU registers (next step)
        if (a < 0x4020) return 0;               // APU and controllers (later)
        if (a < 0x6000) return 0;
        if (a < 0x8000) return prgRam[a - 0x6000];
        return cart->cpuRead(a);
    }

    void write(uint16_t a, uint8_t v) override {
        if (a < 0x2000) ram[a & 0x7FF] = v;
        else if (a >= 0x6000 && a < 0x8000) prgRam[a - 0x6000] = v;
    }

    uint8_t ram[2048];
    uint8_t prgRam[8192];
private:
    Cartridge* cart;
};
