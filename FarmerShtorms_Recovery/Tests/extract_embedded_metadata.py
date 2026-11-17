#!/usr/bin/env python3
"""Вырезает встроенный блок метаданных IL2CPP из Repaired/Test_repaired.so (vaddr == file offset в .rodata)."""
import os,struct
ROOT=os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
R=open(f'{ROOT}/Repaired/Test_repaired.so','rb').read()
START,END=0x1c04200,0x31fb7e4
blob=R[START:END]
open(f'{ROOT}/Extracted/embedded_metadata_raw.bin','wb').write(blob)
hdr=struct.unpack_from('<64I',blob,0)
with open(f'{ROOT}/Extracted/embedded_metadata_header.txt','w') as f:
    f.write('# 64 x uint32 заголовка (поля перемешаны, magic/version заменены)\n')
    for i,v in enumerate(hdr): f.write(f'{i:2d} 0x{v:08x}\n')
print(len(blob))
