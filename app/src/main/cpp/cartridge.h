#pragma once
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

// All changing mapper registers live here so they can be saved in save states.
struct MapState {
    int32_t mirror = 0;      // 0 vertical, 1 horizontal, 2 one-screen A, 3 one-screen B
    int32_t latch = 0;       // generic register (bank number, or the last write address)
    int32_t shift = 0x10;    // MMC1 shift register
    int32_t ctrl = 0x0C;     // MMC1 control
    int32_t chr0 = 0;        // MMC1 CHR bank 0
    int32_t chr1 = 0;        // MMC1 CHR bank 1
    int32_t prgReg = 0;      // MMC1 PRG bank
    int32_t bankSel = 0;     // MMC3 $8000
    int32_t r[8] = {0, 2, 4, 5, 6, 7, 0, 1};   // MMC3 bank registers
    int32_t irqLatch = 0;
    int32_t irqCounter = 0;
    int32_t irqReload = 0;
    int32_t irqEnable = 0;
    int32_t irqPending = 0;
};

class Cartridge {
public:
    std::vector<uint8_t> prg;
    std::vector<uint8_t> chr;
    int mapper = 0;
    bool vertical = false;   // mirroring from the file header
    bool chrRam = false;
    bool battery = false;
    MapState ms;

    static bool supported(int mapper);

    // returns "" on success, otherwise an error message
    std::string load(const uint8_t* d, size_t n);
    void powerOn();

    uint8_t cpuRead(uint16_t addr) const;        // $8000-$FFFF
    void cpuWrite(uint16_t addr, uint8_t val);   // $8000-$FFFF (mapper registers)
    uint8_t ppuRead(uint16_t addr) const;        // $0000-$1FFF
    void ppuWrite(uint16_t addr, uint8_t val);
    int ntTable(int table) const;                // which 1KB of VRAM a nametable uses
    void clockScanline();                        // MMC3 IRQ counter
    bool irqLine() const { return ms.irqPending != 0; }

private:
    size_t prgIndex(uint16_t addr) const;
    size_t chrIndex(uint16_t addr) const;
    void mmc1Write(uint16_t addr, uint8_t val);
    void mmc3Write(uint16_t addr, uint8_t val);
};
