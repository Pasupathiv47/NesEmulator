#include "ppu.h"

static const uint32_t NES_PAL[64] = {
    0xFF666666, 0xFF002A88, 0xFF1412A7, 0xFF3B00A4, 0xFF5C007E, 0xFF6E0040, 0xFF6C0600, 0xFF561D00,
    0xFF333500, 0xFF0B4800, 0xFF005200, 0xFF004F08, 0xFF00404D, 0xFF000000, 0xFF000000, 0xFF000000,
    0xFFADADAD, 0xFF155FD9, 0xFF4240FF, 0xFF7527FE, 0xFFA01ACC, 0xFFB71E7B, 0xFFB53120, 0xFF994E00,
    0xFF6B6D00, 0xFF388700, 0xFF0C9300, 0xFF008F32, 0xFF007C8D, 0xFF000000, 0xFF000000, 0xFF000000,
    0xFFFFFEFF, 0xFF64B0FF, 0xFF9290FF, 0xFFC676FF, 0xFFF36AFF, 0xFFFE6ECC, 0xFFFE8170, 0xFFEA9E22,
    0xFFBCBE00, 0xFF88D800, 0xFF5CE430, 0xFF45E082, 0xFF48CDDE, 0xFF4F4F4F, 0xFF000000, 0xFF000000,
    0xFFFFFEFF, 0xFFC0DFFF, 0xFFD3D2FF, 0xFFE8C8FF, 0xFFFBC2FF, 0xFFFEC4EA, 0xFFFECCC5, 0xFFF7D8A5,
    0xFFE4E594, 0xFFCFEF96, 0xFFBDF4AB, 0xFFB3F3CC, 0xFFB5EBF2, 0xFFB8B8B8, 0xFF000000, 0xFF000000
};

static int palIndex(uint16_t a) {
    int i = a & 0x1F;
    if ((i & 0x13) == 0x10) i &= ~0x10;
    return i;
}

PPU::PPU(Cartridge* c) : cart(c) { reset(); }

void PPU::reset() {
    memset(oam, 0, sizeof(oam));
    memset(vram, 0, sizeof(vram));
    memset(pal, 0, sizeof(pal));
    memset(fb, 0, sizeof(fb));
    oamAddr = 0;
    ctrl = 0; mask = 0; status = 0; latch = 0; readBuf = 0;
    v = 0; t = 0; fineX = 0; w = false;
    scanline = 261; dot = 0; s0HitDot = -1;
    nmiPending = false; frameReady = false;
}

uint16_t PPU::ntOffset(uint16_t a) const {
    int table = cart->ntTable((a >> 10) & 3);
    return (uint16_t)(table * 0x400 + (a & 0x3FF));
}

uint8_t PPU::rd(uint16_t a) {
    a &= 0x3FFF;
    if (a < 0x2000) return cart->ppuRead(a);
    if (a < 0x3F00) return vram[ntOffset(a & 0x2FFF)];
    return pal[palIndex(a)];
}

void PPU::wr(uint16_t a, uint8_t val) {
    a &= 0x3FFF;
    if (a < 0x2000) {
        cart->ppuWrite(a, val);
    } else if (a < 0x3F00) {
        vram[ntOffset(a & 0x2FFF)] = val;
    } else {
        pal[palIndex(a)] = val & 0x3F;
    }
}

uint32_t PPU::color(uint8_t idx) const {
    uint8_t c = pal[palIndex(idx)] & 0x3F;
    if (mask & 0x01) c &= 0x30;
    return NES_PAL[c];
}

uint8_t PPU::readReg(uint16_t reg) {
    switch (reg & 7) {
    case 2: {
        uint8_t r = (status & 0xE0) | (latch & 0x1F);
        status &= ~0x80;
        w = false;
        latch = r;
        return r;
    }
    case 4:
        latch = oam[oamAddr];
        return latch;
    case 7: {
        uint16_t a = v & 0x3FFF;
        uint8_t r;
        if (a >= 0x3F00) {
            r = rd(a);
            readBuf = rd(a - 0x1000);
        } else {
            r = readBuf;
            readBuf = rd(a);
        }
        v += (ctrl & 0x04) ? 32 : 1;
        latch = r;
        return r;
    }
    default:
        return latch;
    }
}

void PPU::writeReg(uint16_t reg, uint8_t val) {
    latch = val;
    switch (reg & 7) {
    case 0: {
        bool wasNmi = (ctrl & 0x80) != 0;
        ctrl = val;
        t = (t & 0xF3FF) | ((val & 3) << 10);
        if (!wasNmi && (ctrl & 0x80) && (status & 0x80)) nmiPending = true;
        break;
    }
    case 1: mask = val; break;
    case 3: oamAddr = val; break;
    case 4: oam[oamAddr] = val; oamAddr++; break;
    case 5:
        if (!w) {
            t = (t & ~0x001F) | (val >> 3);
            fineX = val & 7;
            w = true;
        } else {
            t = (t & ~0x73E0) | ((val & 7) << 12) | ((val >> 3) << 5);
            w = false;
        }
        break;
    case 6:
        if (!w) {
            t = (t & 0x00FF) | ((val & 0x3F) << 8);
            w = true;
        } else {
            t = (t & 0xFF00) | val;
            v = t;
            w = false;
        }
        break;
    case 7:
        wr(v, val);
        v += (ctrl & 0x04) ? 32 : 1;
        break;
    }
}

void PPU::incY() {
    if ((v & 0x7000) != 0x7000) {
        v += 0x1000;
    } else {
        v &= ~0x7000;
        int y = (v & 0x03E0) >> 5;
        if (y == 29) { y = 0; v ^= 0x0800; }
        else if (y == 31) { y = 0; }
        else y++;
        v = (v & ~0x03E0) | (y << 5);
    }
}

void PPU::renderLine(int line) {
    uint32_t* out = &fb[line * 256];
    bool showBg = (mask & 0x08) != 0;
    bool showSp = (mask & 0x10) != 0;

    if (!showBg && !showSp) {
        uint32_t c = color(0);
        for (int i = 0; i < 256; i++) out[i] = c;
        return;
    }

    uint8_t bg[256], bgPal[256], sp[256], spAttr[256];
    bool spZero[256];
    memset(bg, 0, sizeof(bg));
    memset(bgPal, 0, sizeof(bgPal));
    memset(sp, 0, sizeof(sp));
    memset(spAttr, 0, sizeof(spAttr));
    memset(spZero, 0, sizeof(spZero));

    if (showBg) {
        int coarseY = (v >> 5) & 31;
        int fy = (v >> 12) & 7;
        int baseNt = (v >> 10) & 3;
        int cx0 = v & 31;
        uint16_t patBase = (ctrl & 0x10) ? 0x1000 : 0;
        for (int ti = 0; ti < 33; ti++) {
            int cx = cx0 + ti;
            int nt = baseNt;
            if (cx >= 32) { nt ^= 1; cx &= 31; }
            uint16_t ntBase = 0x2000 | (nt << 10);
            uint8_t tile = rd(ntBase | (coarseY << 5) | cx);
            uint8_t attr = rd(ntBase + 0x3C0 + ((coarseY >> 2) << 3) + (cx >> 2));
            int shift = ((coarseY & 2) << 1) | (cx & 2);
            uint8_t palSel = (attr >> shift) & 3;
            uint16_t addr = patBase + tile * 16 + fy;
            uint8_t lo = rd(addr), hi = rd(addr + 8);
            for (int px = 0; px < 8; px++) {
                int sx = ti * 8 + px - fineX;
                if (sx < 0 || sx >= 256) continue;
                int bit = 7 - px;
                uint8_t c = ((lo >> bit) & 1) | (((hi >> bit) & 1) << 1);
                if (sx < 8 && !(mask & 0x02)) c = 0;
                bg[sx] = c;
                bgPal[sx] = palSel;
            }
        }
    }

    if (showSp) {
        int h = (ctrl & 0x20) ? 16 : 8;
        int count = 0;
        for (int i = 0; i < 64; i++) {
            int y = oam[i * 4];
            int r = line - y - 1;
            if (r < 0 || r >= h) continue;
            if (++count > 8) { status |= 0x20; break; }
            uint8_t tile = oam[i * 4 + 1];
            uint8_t attr = oam[i * 4 + 2];
            int x = oam[i * 4 + 3];
            if (attr & 0x80) r = h - 1 - r;
            uint16_t addr;
            if (h == 8) addr = ((ctrl & 0x08) ? 0x1000 : 0) + tile * 16 + r;
            else addr = ((tile & 1) ? 0x1000 : 0) + (tile & 0xFE) * 16 + (r >= 8 ? 16 : 0) + (r & 7);
            uint8_t lo = rd(addr), hi = rd(addr + 8);
            for (int px = 0; px < 8; px++) {
                int sx = x + px;
                if (sx >= 256) break;
                int bit = (attr & 0x40) ? px : 7 - px;
                uint8_t c = ((lo >> bit) & 1) | (((hi >> bit) & 1) << 1);
                if (!c) continue;
                if (sx < 8 && !(mask & 0x04)) continue;
                if (sp[sx]) continue;
                sp[sx] = c;
                spAttr[sx] = attr;
                spZero[sx] = (i == 0);
            }
        }
    }

    for (int sx = 0; sx < 256; sx++) {
        uint8_t bc = bg[sx], sc = sp[sx];
        if (bc && sc && spZero[sx] && sx != 255 && showBg && showSp && s0HitDot < 0)
            s0HitDot = sx + 2;
        uint8_t idx;
        uint8_t bgIdx = (uint8_t)((bgPal[sx] << 2) | bc);
        uint8_t spIdx = (uint8_t)(0x10 | ((spAttr[sx] & 3) << 2) | sc);
        if (!sc) idx = bc ? bgIdx : 0;
        else if (!bc) idx = spIdx;
        else idx = (spAttr[sx] & 0x20) ? bgIdx : spIdx;
        out[sx] = color(idx);
    }
}

void PPU::tick() {
    bool rendering = (mask & 0x18) != 0;

    if (scanline < 240) {
        if (dot == 1) {
            s0HitDot = -1;
            renderLine(scanline);
        }
        if (dot == s0HitDot) status |= 0x40;
        if (rendering && dot == 256) incY();
        if (rendering && dot == 257) v = (v & ~0x041F) | (t & 0x041F);
        if (rendering && dot == 260) cart->clockScanline();   // MMC3 scanline counter
    } else if (scanline == 241) {
        if (dot == 1) {
            status |= 0x80;
            frameReady = true;
            if (ctrl & 0x80) nmiPending = true;
        }
    } else if (scanline == 261) {
        if (dot == 1) status &= ~0xE0;
        if (rendering && dot == 257) v = (v & ~0x041F) | (t & 0x041F);
        if (rendering && dot >= 280 && dot <= 304) v = (v & ~0x7BE0) | (t & 0x7BE0);
        if (rendering && dot == 260) cart->clockScanline();
    }

    if (++dot > 340) {
        dot = 0;
        if (++scanline > 261) scanline = 0;
    }
}
