#include "cartridge.h"

std::string Cartridge::load(const uint8_t* d, size_t n) {
    if (n < 16 || d[0] != 'N' || d[1] != 'E' || d[2] != 'S' || d[3] != 0x1A)
        return "Not a valid .nes file";

    size_t prgSize = (size_t)d[4] * 16384;
    size_t chrSize = (size_t)d[5] * 8192;
    uint8_t f6 = d[6], f7 = d[7];

    mapper = (f7 & 0xF0) | (f6 >> 4);
    vertical = (f6 & 1) != 0;

    size_t off = 16 + ((f6 & 4) ? 512 : 0);
    if (prgSize == 0 || off + prgSize + chrSize > n)
        return "File is truncated or has no PRG data";

    prg.assign(d + off, d + off + prgSize);
    off += prgSize;

    if (chrSize == 0) {
        chrRam = true;
        chr.assign(8192, 0);
    } else {
        chrRam = false;
        chr.assign(d + off, d + off + chrSize);
    }
    return "";
}

uint8_t Cartridge::cpuRead(uint16_t addr) const {
    if (prg.empty() || addr < 0x8000) return 0;
    return prg[(addr - 0x8000) % prg.size()];
}
