#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>
#include "state.h"
#include "cpu.h"
#include "cartridge.h"
#include "ppu.h"
#include "apu.h"
#include "nesbus.h"

namespace {
const uint32_t MAGIC = 0x53534E45;
const uint32_t VERSION = 2;

struct Writer {
    std::vector<uint8_t>& o;
    template <class T>
    void put(const T& v) {
        const uint8_t* p = reinterpret_cast<const uint8_t*>(&v);
        o.insert(o.end(), p, p + sizeof(T));
    }
    void bytes(const void* d, size_t n) {
        const uint8_t* p = static_cast<const uint8_t*>(d);
        o.insert(o.end(), p, p + n);
    }
};

struct Reader {
    const uint8_t* p;
    const uint8_t* end;
    bool ok = true;
    template <class T>
    void get(T& v) {
        if ((size_t)(end - p) < sizeof(T)) { ok = false; return; }
        memcpy(&v, p, sizeof(T));
        p += sizeof(T);
    }
    void bytes(void* d, size_t n) {
        if ((size_t)(end - p) < n) { ok = false; return; }
        memcpy(d, p, n);
        p += n;
    }
};
}  // namespace

void StateIO::save(std::vector<uint8_t>& out, const CPU& cpu, const PPU& ppu,
                   const APU& apu, const NesBus& bus, const Cartridge& cart) {
    out.clear();
    Writer wr{out};

    wr.put(MAGIC);
    wr.put(VERSION);
    wr.put((uint32_t)cart.prg.size());
    wr.put((uint32_t)cart.chr.size());
    wr.put((uint8_t)(cart.chrRam ? 1 : 0));
    wr.put((uint32_t)cart.mapper);
    wr.put(cart.ms);   // mapper registers

    // CPU
    wr.put(cpu.pc);
    wr.put(cpu.a);
    wr.put(cpu.x);
    wr.put(cpu.y);
    wr.put(cpu.sp);
    wr.put(cpu.p);
    wr.put(cpu.cycles);

    // Memory / controllers
    wr.bytes(bus.ram, sizeof(bus.ram));
    wr.bytes(bus.prgRam, sizeof(bus.prgRam));
    wr.put(bus.strobe);
    wr.put(bus.shift[0]);
    wr.put(bus.shift[1]);

    // PPU
    wr.bytes(ppu.oam, sizeof(ppu.oam));
    wr.put(ppu.oamAddr);
    wr.put(ppu.nmiPending);
    wr.put(ppu.frameReady);
    wr.bytes(ppu.vram, sizeof(ppu.vram));
    wr.bytes(ppu.pal, sizeof(ppu.pal));
    wr.put(ppu.ctrl);
    wr.put(ppu.mask);
    wr.put(ppu.status);
    wr.put(ppu.latch);
    wr.put(ppu.readBuf);
    wr.put(ppu.v);
    wr.put(ppu.t);
    wr.put(ppu.fineX);
    wr.put(ppu.w);
    wr.put(ppu.scanline);
    wr.put(ppu.dot);
    wr.put(ppu.s0HitDot);

    // APU
    wr.put(apu.p1);
    wr.put(apu.p2);
    wr.put(apu.tri);
    wr.put(apu.noi);
    wr.put(apu.dmc);
    wr.put(apu.frameCycle);
    wr.put(apu.mode5);
    wr.put(apu.irqInhibit);
    wr.put(apu.frameIrq);
    wr.put(apu.odd);
    wr.put(apu.sampleCounter);
    wr.put(apu.acc);
    wr.put(apu.accN);
    wr.put(apu.px);
    wr.put(apu.py);

    // Cartridge graphics RAM (games without CHR ROM)
    if (cart.chrRam && !cart.chr.empty()) wr.bytes(cart.chr.data(), cart.chr.size());
}

bool StateIO::load(const uint8_t* data, size_t n, CPU& cpu, PPU& ppu,
                   APU& apu, NesBus& bus, Cartridge& cart) {
    Reader rd{data, data + n};

    uint32_t magic = 0, version = 0, prgSize = 0, chrSize = 0, mapper = 0;
    uint8_t chrRam = 0;
    rd.get(magic);
    rd.get(version);
    rd.get(prgSize);
    rd.get(chrSize);
    rd.get(chrRam);
    rd.get(mapper);
    if (!rd.ok || magic != MAGIC || version != VERSION) return false;
    if (prgSize != cart.prg.size() || chrSize != cart.chr.size() ||
        chrRam != (uint8_t)(cart.chrRam ? 1 : 0) || mapper != (uint32_t)cart.mapper)
        return false;

    rd.get(cart.ms);

    // CPU
    rd.get(cpu.pc);
    rd.get(cpu.a);
    rd.get(cpu.x);
    rd.get(cpu.y);
    rd.get(cpu.sp);
    rd.get(cpu.p);
    rd.get(cpu.cycles);

    // Memory / controllers
    rd.bytes(bus.ram, sizeof(bus.ram));
    rd.bytes(bus.prgRam, sizeof(bus.prgRam));
    rd.get(bus.strobe);
    rd.get(bus.shift[0]);
    rd.get(bus.shift[1]);
    bus.stall = 0;

    // PPU
    rd.bytes(ppu.oam, sizeof(ppu.oam));
    rd.get(ppu.oamAddr);
    rd.get(ppu.nmiPending);
    rd.get(ppu.frameReady);
    rd.bytes(ppu.vram, sizeof(ppu.vram));
    rd.bytes(ppu.pal, sizeof(ppu.pal));
    rd.get(ppu.ctrl);
    rd.get(ppu.mask);
    rd.get(ppu.status);
    rd.get(ppu.latch);
    rd.get(ppu.readBuf);
    rd.get(ppu.v);
    rd.get(ppu.t);
    rd.get(ppu.fineX);
    rd.get(ppu.w);
    rd.get(ppu.scanline);
    rd.get(ppu.dot);
    rd.get(ppu.s0HitDot);

    // APU
    rd.get(apu.p1);
    rd.get(apu.p2);
    rd.get(apu.tri);
    rd.get(apu.noi);
    rd.get(apu.dmc);
    rd.get(apu.frameCycle);
    rd.get(apu.mode5);
    rd.get(apu.irqInhibit);
    rd.get(apu.frameIrq);
    rd.get(apu.odd);
    rd.get(apu.sampleCounter);
    rd.get(apu.acc);
    rd.get(apu.accN);
    rd.get(apu.px);
    rd.get(apu.py);

    if (cart.chrRam && !cart.chr.empty()) rd.bytes(cart.chr.data(), cart.chr.size());

    apu.ringCount = 0;
    ppu.frameReady = false;
    return rd.ok;
}
