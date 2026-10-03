"""Minimal ELF reader for the natives gates (T5.1): header, program headers, PT_INTERP, DT_NEEDED, DT_SONAME.
Pure Python, no dependencies."""
import struct

EM = {3: "x86", 40: "arm", 62: "x86_64", 183: "aarch64"}
ABI_MACHINE = {"arm64-v8a": "aarch64", "armeabi-v7a": "arm", "x86": "x86", "x86_64": "x86_64"}
PT_LOAD, PT_DYNAMIC, PT_INTERP = 1, 2, 3
DT_NEEDED, DT_STRTAB, DT_SONAME = 1, 5, 14
ET_EXEC, ET_DYN = 2, 3


class Elf:
    def __init__(self, data: bytes):
        if data[:4] != b"\x7fELF":
            raise ValueError("not an ELF file")
        self.data = data
        self.is64 = data[4] == 2
        self.le = data[5] == 1
        e = "<" if self.le else ">"
        if self.is64:
            (self.e_type, self.e_machine, _, _, self.e_phoff, _, _, _, self.e_phentsize, self.e_phnum) = struct.unpack_from(e + "HHIQQQIHHH", data, 16)
        else:
            (self.e_type, self.e_machine, _, _, self.e_phoff, _, _, _, self.e_phentsize, self.e_phnum) = struct.unpack_from(e + "HHIIIIIHHH", data, 16)
        self.machine = EM.get(self.e_machine, str(self.e_machine))
        self.phdrs = []
        for i in range(self.e_phnum):
            off = self.e_phoff + i * self.e_phentsize
            if self.is64:
                p_type, p_flags, p_offset, p_vaddr, _, p_filesz, _, p_align = struct.unpack_from(e + "IIQQQQQQ", data, off)
            else:
                p_type, p_offset, p_vaddr, _, p_filesz, _, p_flags, p_align = struct.unpack_from(e + "IIIIIIII", data, off)
            self.phdrs.append(dict(type=p_type, offset=p_offset, vaddr=p_vaddr, filesz=p_filesz, align=p_align))

    @property
    def interp(self):
        for p in self.phdrs:
            if p["type"] == PT_INTERP:
                return self.data[p["offset"]:p["offset"] + p["filesz"]].rstrip(b"\x00").decode("ascii", "replace")
        return None

    def _vaddr_to_off(self, vaddr):
        for p in self.phdrs:
            if p["type"] == PT_LOAD and p["vaddr"] <= vaddr < p["vaddr"] + p["filesz"]:
                return vaddr - p["vaddr"] + p["offset"]
        return None

    def dynamic(self):
        dyn = next((p for p in self.phdrs if p["type"] == PT_DYNAMIC), None)
        if not dyn:
            return [], None
        e = "<" if self.le else ">"
        fmt, size = (e + "qQ", 16) if self.is64 else (e + "iI", 8)
        entries = []
        for i in range(dyn["filesz"] // size):
            tag, val = struct.unpack_from(fmt, self.data, dyn["offset"] + i * size)
            if tag == 0:
                break
            entries.append((tag, val))
        strtab = next((v for t, v in entries if t == DT_STRTAB), None)
        base = self._vaddr_to_off(strtab) if strtab is not None else None

        def s(off):
            if base is None:
                return None
            end = self.data.index(b"\x00", base + off)
            return self.data[base + off:end].decode("ascii", "replace")
        needed = [s(v) for t, v in entries if t == DT_NEEDED]
        soname = next((s(v) for t, v in entries if t == DT_SONAME), None)
        return [n for n in needed if n], soname

    @property
    def min_load_align(self):
        aligns = [p["align"] for p in self.phdrs if p["type"] == PT_LOAD]
        return min(aligns) if aligns else 0


def make_seed(align=0x1000, machine=183) -> bytes:
    """a tiny 64-bit shared object with one PT_LOAD of [align] - the gate self-test's 4 KB seed"""
    ehdr = struct.pack("<4sBBBBB7sHHIQQQIHHHHHH", b"\x7fELF", 2, 1, 1, 0, 0, b"\x00" * 7, ET_DYN, machine, 1, 0, 64, 0, 0, 64, 56, 1, 64, 0, 0)
    phdr = struct.pack("<IIQQQQQQ", PT_LOAD, 5, 0, 0, 0, 120, 120, align)
    return ehdr + phdr
