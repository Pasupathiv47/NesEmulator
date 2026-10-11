#pragma once
#include <atomic>
#include <cstring>
#include "cpu.h"
#include "cartridge.h"
#include "ppu.h"
#include "apu.h"

class NesBus : public Bus {
public:
    NesBus(Cartridge* c, PPU* p, APU* a) : cart(c), ppu(p), apu(a) {
        memset(ram, 0, sizeof(ram));
        memset(prgRam, 0, sizeof(prgRam));
        pad[0].store(0);
        pad[1].store(0);
    }

    uint8_t read(uint16_t a) override {
        if (a < 0x2000) return ram[a & 0x7FF];
        if (a < 0x4000) return ppu->readReg(a & 7);
        if (a == 0x4015) return apu->readStatus();
        if (a == 0x4016 || a == 0x4017) {
            int i = a & 1;
            if (strobe) shift[i] = pad[i].load();
            uint8_t r = shift[i] & 1;
            shift[i] = (uint8_t)((shift[i] >> 1) | 0x80);
            return r | 0x40;
        }
        if (a < 0x6000) return 0;
        if (a < 0x8000) return prgRam[a - 0x6000];
        return cart->cpuRead(a);
    }

    void write(uint16_t a, uint8_t val) override {
        if (a < 0x2000) {
            ram[a & 0x7FF] = val;
        } else if (a < 0x4000) {
            ppu->writeReg(a & 7, val);
        } else if (a == 0x4014) {
            uint16_t base = (uint16_t)(val << 8);
            for (int i = 0; i < 256; i++) ppu->writeReg(4, read((uint16_t)(base + i)));
            stall += 513;
        } else if (a == 0x4016) {
            strobe = (val & 1) != 0;
            if (strobe) {
                shift[0] = pad[0].load();
                shift[1] = pad[1].load();
            }
        } else if ((a >= 0x4000 && a <= 0x4013) || a == 0x4015 || a == 0x4017) {
            apu->write(a, val);
        } else if (a >= 0x6000 && a < 0x8000) {
            prgRam[a - 0x6000] = val;
        }
    }

    std::atomic<uint8_t> pad[2];
    int stall = 0;

private:
    friend struct StateIO;

    Cartridge* cart;
    PPU* ppu;
    APU* apu;
    uint8_t ram[2048];
    uint8_t prgRam[8192];
    bool strobe = false;
    uint8_t shift[2] = {0, 0};
};
