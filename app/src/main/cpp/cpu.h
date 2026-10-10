#pragma once
#include <cstdint>

class Bus {
public:
    virtual ~Bus() {}
    virtual uint8_t read(uint16_t addr) = 0;
    virtual void write(uint16_t addr, uint8_t val) = 0;
};

class CPU {
public:
    explicit CPU(Bus* b) : bus(b) {}
    void reset();
    int step();   // runs one instruction, returns cycles used
    void nmi();
    void irq();

    uint16_t pc = 0;
    uint8_t a = 0, x = 0, y = 0, sp = 0xFD, p = 0x24;
    uint64_t cycles = 0;

private:
    Bus* bus;
    void setNZ(uint8_t v);
    void push(uint8_t v);
    uint8_t pull();
    void push16(uint16_t v);
    uint16_t pull16();
    uint8_t fetch();
    uint16_t fetch16();
    uint16_t read16(uint16_t at);
    uint16_t addr(int mode, bool& crossed);
    void adc(uint8_t v);
    void cmp(uint8_t r, uint8_t v);
    uint8_t shift(int kind, uint8_t v);
    int done(int n);
};
