#pragma once
#include <cstdint>
#include <cstddef>
#include <string>
#include <vector>

class Cartridge {
public:
    std::vector<uint8_t> prg;
    std::vector<uint8_t> chr;
    int mapper = 0;
    bool vertical = false;
    bool chrRam = false;

    // returns "" on success, otherwise an error message
    std::string load(const uint8_t* d, size_t n);
    uint8_t cpuRead(uint16_t addr) const;
};
