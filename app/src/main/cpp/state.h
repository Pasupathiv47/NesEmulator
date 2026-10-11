#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

class CPU;
class PPU;
class APU;
class NesBus;
class Cartridge;

// Saves / restores the complete emulator state (used for save-and-continue).
struct StateIO {
    static void save(std::vector<uint8_t>& out, const CPU& cpu, const PPU& ppu,
                     const APU& apu, const NesBus& bus, const Cartridge& cart);
    static bool load(const uint8_t* data, size_t n, CPU& cpu, PPU& ppu,
                     APU& apu, NesBus& bus, Cartridge& cart);
};
