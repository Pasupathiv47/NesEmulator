#include "cartridge.h"

namespace {
const int MMC1_MIRROR[4] = {2, 3, 0, 1};
}

bool Cartridge::supported(int m) {
    switch (m) {
    case 0: case 1: case 2: case 3: case 4: case 7:
    case 66: case 71: case 225: case 227:
        return true;
    default:
        return false;
    }
}

std::string Cartridge::load(const uint8_t* d, size_t n) {
    if (n < 16 || d[0] != 'N' || d[1] != 'E' || d[2] != 'S' || d[3] != 0x1A)
        return "Not a valid .nes file";

    size_t prgSize = (size_t)d[4] * 16384;
    size_t chrSize = (size_t)d[5] * 8192;
    uint8_t f6 = d[6], f7 = d[7];

    bool nes2 = (f7 & 0x0C) == 0x08;
    if (nes2) mapper = (f7 & 0xF0) | (f6 >> 4) | ((d[8] & 0x0F) << 8);
    else if ((d[12] | d[13] | d[14] | d[15]) != 0) mapper = f6 >> 4;  // old dumps with junk in the header
    else mapper = (f7 & 0xF0) | (f6 >> 4);

    vertical = (f6 & 1) != 0;
    battery = (f6 & 2) != 0;

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
    powerOn();
    return "";
}

void Cartridge::powerOn() {
    ms = MapState();
    ms.mirror = vertical ? 0 : 1;
    if (mapper == 1) ms.mirror = MMC1_MIRROR[ms.ctrl & 3];
    if (mapper == 7) ms.mirror = 2;
    if (mapper == 225 || mapper == 227) ms.mirror = 0;
}

// ---------------- CPU side ----------------

size_t Cartridge::prgIndex(uint16_t a) const {
    const size_t sz = prg.size();
    const size_t off = a & 0x3FFF;           // offset inside a 16KB window
    const bool hi = (a & 0x4000) != 0;       // $C000-$FFFF
    const int banks16 = (int)(sz / 0x4000);
    int bank = 0;

    switch (mapper) {
    case 1: {
        int mode = (ms.ctrl >> 2) & 3;
        int outer = (banks16 > 16) ? (ms.chr0 & 0x10) : 0;   // 512KB boards
        if (mode <= 1) {
            bank = ((ms.prgReg & 0x0E) | (hi ? 1 : 0)) + outer;
        } else if (mode == 2) {
            bank = hi ? ((ms.prgReg & 0x0F) + outer) : outer;
        } else {
            if (!hi) bank = (ms.prgReg & 0x0F) + outer;
            else bank = (banks16 > 16) ? (0x0F + outer) : banks16 - 1;
        }
        break;
    }
    case 2:
        bank = hi ? banks16 - 1 : ms.latch;
        break;
    case 4: {
        const int banks8 = (int)(sz / 0x2000);
        const bool pm = (ms.bankSel & 0x40) != 0;
        int b8;
        switch ((a >> 13) & 3) {
        case 0: b8 = pm ? banks8 - 2 : ms.r[6]; break;
        case 1: b8 = ms.r[7]; break;
        case 2: b8 = pm ? ms.r[6] : banks8 - 2; break;
        default: b8 = banks8 - 1; break;
        }
        return ((size_t)b8 * 0x2000 + (a & 0x1FFF)) % sz;
    }
    case 7:
        return ((size_t)(ms.latch & 7) * 0x8000 + (size_t)(a - 0x8000)) % sz;
    case 66:
        return ((size_t)((ms.latch >> 4) & 3) * 0x8000 + (size_t)(a - 0x8000)) % sz;
    case 71:
        bank = hi ? banks16 - 1 : (ms.latch & 0x0F);
        break;
    case 225: {
        int page = ((ms.latch >> 6) & 0x3F) | ((ms.latch >> 8) & 0x40);
        bool mode16 = ((ms.latch >> 12) & 1) != 0;
        bank = mode16 ? page : ((page & ~1) | (hi ? 1 : 0));
        break;
    }
    case 227: {
        int lv = ms.latch;
        int prgReg = ((lv >> 2) & 0x1F) | ((lv & 0x100) >> 3);
        bool s = (lv & 1) != 0;
        bool o = (lv & 0x80) != 0;
        bool l = (lv & 0x200) != 0;
        if (o) bank = s ? ((prgReg & ~1) | (hi ? 1 : 0)) : prgReg;
        else if (!hi) bank = s ? (prgReg & ~1) : prgReg;
        else bank = (prgReg & ~7) | (l ? 7 : 0);
        break;
    }
    default:   // 0 and 3: fixed 16KB (mirrored) or 32KB
        return (size_t)(a - 0x8000) % sz;
    }
    return ((size_t)bank * 0x4000 + off) % sz;
}

uint8_t Cartridge::cpuRead(uint16_t a) const {
    if (prg.empty() || a < 0x8000) return 0;
    return prg[prgIndex(a)];
}

void Cartridge::mmc1Write(uint16_t a, uint8_t v) {
    if (v & 0x80) {
        ms.shift = 0x10;
        ms.ctrl |= 0x0C;
    } else {
        bool last = (ms.shift & 1) != 0;
        ms.shift = (ms.shift >> 1) | ((v & 1) << 4);
        if (last) {
            int val = ms.shift & 0x1F;
            switch ((a >> 13) & 3) {
            case 0: ms.ctrl = val; break;
            case 1: ms.chr0 = val; break;
            case 2: ms.chr1 = val; break;
            default: ms.prgReg = val; break;
            }
            ms.shift = 0x10;
        }
    }
    ms.mirror = MMC1_MIRROR[ms.ctrl & 3];
}

void Cartridge::mmc3Write(uint16_t a, uint8_t v) {
    bool odd = (a & 1) != 0;
    switch ((a >> 13) & 3) {
    case 0:
        if (!odd) ms.bankSel = v;
        else ms.r[ms.bankSel & 7] = v;
        break;
    case 1:
        if (!odd) ms.mirror = (v & 1) ? 1 : 0;
        break;
    case 2:
        if (!odd) {
            ms.irqLatch = v;
        } else {
            ms.irqCounter = 0;
            ms.irqReload = 1;
        }
        break;
    default:
        if (!odd) {
            ms.irqEnable = 0;
            ms.irqPending = 0;
        } else {
            ms.irqEnable = 1;
        }
        break;
    }
}

void Cartridge::cpuWrite(uint16_t a, uint8_t v) {
    if (a < 0x8000) return;
    switch (mapper) {
    case 1: mmc1Write(a, v); break;
    case 2: ms.latch = v; break;
    case 3: ms.latch = v; break;
    case 4: mmc3Write(a, v); break;
    case 7:
        ms.latch = v;
        ms.mirror = (v & 0x10) ? 3 : 2;
        break;
    case 66: ms.latch = v; break;
    case 71:
        if (a >= 0xC000) ms.latch = v;
        break;
    case 225:
        ms.latch = a;
        ms.mirror = ((a >> 13) & 1) ? 1 : 0;
        break;
    case 227:
        ms.latch = a;
        ms.mirror = ((a >> 1) & 1) ? 1 : 0;
        break;
    default:
        break;
    }
}

void Cartridge::clockScanline() {
    if (mapper != 4) return;
    if (ms.irqCounter == 0 || ms.irqReload) {
        ms.irqCounter = ms.irqLatch;
        ms.irqReload = 0;
    } else {
        ms.irqCounter--;
    }
    if (ms.irqCounter == 0 && ms.irqEnable) ms.irqPending = 1;
}

// ---------------- PPU side ----------------

size_t Cartridge::chrIndex(uint16_t a) const {
    const size_t sz = chr.size();
    switch (mapper) {
    case 1: {
        bool mode4k = (ms.ctrl & 0x10) != 0;
        int bank;
        if (mode4k) bank = (a < 0x1000) ? ms.chr0 : ms.chr1;
        else bank = (ms.chr0 & 0x1E) | (a >= 0x1000 ? 1 : 0);
        return ((size_t)bank * 0x1000 + (a & 0x0FFF)) % sz;
    }
    case 3:
        return ((size_t)ms.latch * 0x2000 + a) % sz;
    case 66:
        return ((size_t)(ms.latch & 3) * 0x2000 + a) % sz;
    case 4: {
        int slot = a >> 10;
        bool inv = (ms.bankSel & 0x80) != 0;
        int s = inv ? (slot ^ 4) : slot;
        int bank;
        switch (s) {
        case 0: bank = ms.r[0] & 0xFE; break;
        case 1: bank = ms.r[0] | 1; break;
        case 2: bank = ms.r[1] & 0xFE; break;
        case 3: bank = ms.r[1] | 1; break;
        default: bank = ms.r[s - 2]; break;
        }
        return ((size_t)bank * 0x400 + (a & 0x3FF)) % sz;
    }
    case 225: {
        int page = (ms.latch & 0x3F) | ((ms.latch >> 8) & 0x40);
        return ((size_t)page * 0x2000 + a) % sz;
    }
    default:
        return (size_t)a % sz;
    }
}

uint8_t Cartridge::ppuRead(uint16_t a) const {
    if (chr.empty()) return 0;
    return chr[chrIndex(a & 0x1FFF)];
}

void Cartridge::ppuWrite(uint16_t a, uint8_t v) {
    if (!chrRam || chr.empty()) return;
    // multicart boards write-protect CHR-RAM in their NROM modes
    if (mapper == 227 && !battery && (ms.latch & 0x80)) return;
    chr[chrIndex(a & 0x1FFF)] = v;
}

int Cartridge::ntTable(int t) const {
    switch (ms.mirror) {
    case 0: return t & 1;
    case 1: return t >> 1;
    case 2: return 0;
    case 3: return 1;
    default: return t & 1;
    }
}
