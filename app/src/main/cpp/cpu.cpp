#include "cpu.h"

namespace {
enum { FC = 1, FZ = 2, FI = 4, FD = 8, FB = 16, FU = 32, FV = 64, FN = 128 };
enum Mode { IMM, ZP, ZPX, ZPY, ABS, ABX, ABY, INDX, INDY };
const int RD[9]  = {2, 3, 4, 4, 4, 4, 4, 6, 5};
const int WR[9]  = {0, 3, 4, 4, 4, 5, 5, 6, 6};
const int RMW[9] = {0, 5, 6, 0, 6, 7, 0, 0, 0};
}

void CPU::reset() {
    a = x = y = 0;
    sp = 0xFD;
    p = 0x24;
    pc = read16(0xFFFC);
    cycles = 7;
}

void CPU::setNZ(uint8_t v) {
    p = (p & ~(FZ | FN)) | (v == 0 ? FZ : 0) | (v & 0x80);
}
void CPU::push(uint8_t v) { bus->write(0x100 + sp, v); sp--; }
uint8_t CPU::pull() { sp++; return bus->read(0x100 + sp); }
void CPU::push16(uint16_t v) { push(v >> 8); push(v & 0xFF); }
uint16_t CPU::pull16() { uint8_t lo = pull(); uint8_t hi = pull(); return lo | (hi << 8); }
uint8_t CPU::fetch() { return bus->read(pc++); }
uint16_t CPU::fetch16() { uint8_t lo = fetch(); uint8_t hi = fetch(); return lo | (hi << 8); }
uint16_t CPU::read16(uint16_t at) { return bus->read(at) | (bus->read(at + 1) << 8); }
int CPU::done(int n) { cycles += n; return n; }

uint16_t CPU::addr(int mode, bool& crossed) {
    crossed = false;
    switch (mode) {
    case IMM:  return pc++;
    case ZP:   return fetch();
    case ZPX:  return (uint8_t)(fetch() + x);
    case ZPY:  return (uint8_t)(fetch() + y);
    case ABS:  return fetch16();
    case ABX: {
        uint16_t b = fetch16(); uint16_t r = b + x;
        crossed = (b & 0xFF00) != (r & 0xFF00); return r;
    }
    case ABY: {
        uint16_t b = fetch16(); uint16_t r = b + y;
        crossed = (b & 0xFF00) != (r & 0xFF00); return r;
    }
    case INDX: {
        uint8_t z = (uint8_t)(fetch() + x);
        return bus->read(z) | (bus->read((uint8_t)(z + 1)) << 8);
    }
    case INDY: {
        uint8_t z = fetch();
        uint16_t b = bus->read(z) | (bus->read((uint8_t)(z + 1)) << 8);
        uint16_t r = b + y;
        crossed = (b & 0xFF00) != (r & 0xFF00); return r;
    }
    }
    return 0;
}

void CPU::adc(uint8_t v) {
    uint16_t s = a + v + (p & FC);
    p &= ~(FC | FV);
    if (s > 0xFF) p |= FC;
    if (~(a ^ v) & (a ^ s) & 0x80) p |= FV;
    a = (uint8_t)s;
    setNZ(a);
}

void CPU::cmp(uint8_t r, uint8_t v) {
    p &= ~FC;
    if (r >= v) p |= FC;
    setNZ((uint8_t)(r - v));
}

uint8_t CPU::shift(int kind, uint8_t v) {
    uint8_t c;
    switch (kind) {
    case 0: c = v >> 7; v <<= 1; break;                       // ASL
    case 1: c = v >> 7; v = (v << 1) | (p & FC); break;       // ROL
    case 2: c = v & 1;  v >>= 1; break;                       // LSR
    default: c = v & 1; v = (v >> 1) | ((p & FC) << 7); break; // ROR
    }
    p = (p & ~FC) | c;
    setNZ(v);
    return v;
}

void CPU::nmi() {
    push16(pc); push((p & ~FB) | FU); p |= FI;
    pc = read16(0xFFFA); cycles += 7;
}

void CPU::irq() {
    if (p & FI) return;
    push16(pc); push((p & ~FB) | FU); p |= FI;
    pc = read16(0xFFFE); cycles += 7;
}

int CPU::step() {
    uint8_t op = fetch();
    int aaa = op >> 5, bbb = (op >> 2) & 7, cc = op & 3;
    bool cr = false;

    // Single-byte and special instructions
    switch (op) {
    case 0x00: fetch(); push16(pc); push(p | FB | FU); p |= FI;
               pc = read16(0xFFFE); return done(7);
    case 0x20: { uint8_t lo = fetch(); push16(pc);
                 pc = lo | (bus->read(pc) << 8); return done(6); }
    case 0x40: p = (pull() & ~FB) | FU; pc = pull16(); return done(6);
    case 0x60: pc = pull16() + 1; return done(6);
    case 0x08: push(p | FB | FU); return done(3);
    case 0x28: p = (pull() & ~FB) | FU; return done(4);
    case 0x48: push(a); return done(3);
    case 0x68: a = pull(); setNZ(a); return done(4);
    case 0x88: y--; setNZ(y); return done(2);
    case 0xA8: y = a; setNZ(y); return done(2);
    case 0xC8: y++; setNZ(y); return done(2);
    case 0xE8: x++; setNZ(x); return done(2);
    case 0x8A: a = x; setNZ(a); return done(2);
    case 0x98: a = y; setNZ(a); return done(2);
    case 0x9A: sp = x; return done(2);
    case 0xAA: x = a; setNZ(x); return done(2);
    case 0xBA: x = sp; setNZ(x); return done(2);
    case 0xCA: x--; setNZ(x); return done(2);
    case 0xEA: return done(2);
    case 0x18: p &= ~FC; return done(2);
    case 0x38: p |= FC; return done(2);
    case 0x58: p &= ~FI; return done(2);
    case 0x78: p |= FI; return done(2);
    case 0xB8: p &= ~FV; return done(2);
    case 0xD8: p &= ~FD; return done(2);
    case 0xF8: p |= FD; return done(2);
    case 0x4C: pc = fetch16(); return done(3);
    case 0x6C: {
        uint16_t ptr = fetch16();
        uint8_t lo = bus->read(ptr);
        uint8_t hi = bus->read((ptr & 0xFF00) | ((ptr + 1) & 0xFF));
        pc = lo | (hi << 8);
        return done(5);
    }
    }

    // Branches
    if ((op & 0x1F) == 0x10) {
        static const uint8_t mask[4] = {FN, FV, FC, FZ};
        int8_t off = (int8_t)fetch();
        bool taken = ((p & mask[aaa >> 1]) != 0) == ((aaa & 1) != 0);
        int c = 2;
        if (taken) {
            uint16_t np = pc + off;
            c += 1 + (((np ^ pc) & 0xFF00) ? 1 : 0);
            pc = np;
        }
        return done(c);
    }

    // Group 1: ORA AND EOR ADC STA LDA CMP SBC
    if (cc == 1) {
        static const int M[8] = {INDX, ZP, IMM, ABS, INDY, ZPX, ABY, ABX};
        int m = M[bbb];
        if (aaa == 4) {
            if (m == IMM) return done(2);
            uint16_t ea = addr(m, cr);
            bus->write(ea, a);
            return done(WR[m]);
        }
        uint16_t ea = addr(m, cr);
        uint8_t v = bus->read(ea);
        switch (aaa) {
        case 0: a |= v; setNZ(a); break;
        case 1: a &= v; setNZ(a); break;
        case 2: a ^= v; setNZ(a); break;
        case 3: adc(v); break;
        case 5: a = v; setNZ(a); break;
        case 6: cmp(a, v); break;
        case 7: adc((uint8_t)~v); break;
        }
        return done(RD[m] + (cr ? 1 : 0));
    }

    // Group 2: shifts, INC/DEC, LDX/STX
    if (cc == 2) {
        if (bbb == 2) {                       // accumulator
            if (aaa < 4) a = shift(aaa, a);
            return done(2);
        }
        if (aaa == 4 || aaa == 5) {           // STX / LDX
            static const int MX[8] = {IMM, ZP, ZP, ABS, ZP, ZPY, ZP, ABY};
            int m = MX[bbb];
            if (aaa == 4) {
                if (bbb != 1 && bbb != 3 && bbb != 5) return done(2);
                uint16_t ea = addr(m, cr);
                bus->write(ea, x);
                return done(WR[m]);
            }
            if (bbb == 4 || bbb == 6) return done(2);
            uint16_t ea = addr(m, cr);
            x = bus->read(ea);
            setNZ(x);
            return done(RD[m] + (cr ? 1 : 0));
        }
        if (bbb == 0 || bbb == 4 || bbb == 6) return done(2);
        static const int MR[8] = {IMM, ZP, IMM, ABS, IMM, ZPX, IMM, ABX};
        int m = MR[bbb];
        uint16_t ea = addr(m, cr);
        uint8_t v = bus->read(ea);
        if (aaa < 4) v = shift(aaa, v);
        else if (aaa == 6) { v--; setNZ(v); }
        else { v++; setNZ(v); }
        bus->write(ea, v);
        return done(RMW[m]);
    }

    // Group 0: BIT STY LDY CPY CPX
    if (cc == 0) {
        static const int M[8] = {IMM, ZP, IMM, ABS, IMM, ZPX, IMM, ABX};
        if (bbb == 2 || bbb == 4 || bbb == 6) return done(2);
        int m = M[bbb];
        switch (aaa) {
        case 1:
            if (bbb == 1 || bbb == 3) {
                uint8_t v = bus->read(addr(m, cr));
                p = (p & ~(FZ | FV | FN)) | ((a & v) == 0 ? FZ : 0) | (v & (FV | FN));
                return done(RD[m]);
            }
            break;
        case 4:
            if (bbb == 1 || bbb == 3 || bbb == 5) {
                bus->write(addr(m, cr), y);
                return done(WR[m]);
            }
            break;
        case 5: {
            y = bus->read(addr(m, cr));
            setNZ(y);
            return done(RD[m] + (cr ? 1 : 0));
        }
        case 6:
        case 7:
            if (bbb == 0 || bbb == 1 || bbb == 3) {
                uint8_t v = bus->read(addr(m, cr));
                cmp(aaa == 6 ? y : x, v);
                return done(RD[m]);
            }
            break;
        }
        return done(2);
    }

    return done(2);   // unofficial opcodes treated as NOP for now
}
