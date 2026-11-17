#!/usr/bin/env python3
"""Доказательные проверки ремонта Test.so -> Test_repaired.so. Запуск: python3 -I Tests/verify_repair.py"""
import struct, subprocess, sys, re, os
ROOT=os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
O=open(f'{ROOT}/Original/Test.so','rb').read()
R=open(f'{ROOT}/Repaired/Test_repaired.so','rb').read()
P,GAP=0x2edd675,1999
res=[]
def check(name,cond,detail=''):
    res.append(cond); print(('PASS ' if cond else 'FAIL ')+name+(' — '+detail if detail else ''))
# 1 только вставка, оригинальные байты не тронуты
check('T1 оригинальные байты сохранены (R = O[:P] + 1999×00 + O[P:])', R[:P]==O[:P] and R[P+GAP:]==O[P:] and R[P:P+GAP]==b'\0'*GAP)
# 2 размер = e_shoff + shnum*shentsize
shoff,=struct.unpack_from('<Q',R,0x28); shent,shnum,shstr=struct.unpack_from('<HHH',R,0x3a)
check('T2 размер файла точно равен концу таблицы секций', len(R)==shoff+shent*shnum, f'{len(R)} == {shoff}+{shent}*{shnum}')
# 3 секции согласованы с сегментами: offset = vaddr - (vaddr-offset сегмента)
ph=[struct.unpack_from('<IIQQQQQQ',R,64+56*i) for i in range(10)]
loads=[(p[3],p[2],p[5]) for p in ph if p[0]==1]
secs=[struct.unpack_from('<IIQQQQIIQQ',R,shoff+shent*i) for i in range(shnum)]
bad=0;cnt=0
for s in secs:
    if s[3] and s[1]!=8:
        cnt+=1
        seg=[l for l in loads if l[0]<=s[3]<l[0]+max(l[2],1)]
        if not seg or s[3]-seg[0][0]+seg[0][1]!=s[4]: bad+=1
check('T3 все аллоцируемые секции совпадают с картой сегментов', bad==0, f'{cnt} секций, расхождений {bad}')
# 4 PT_DYNAMIC читается по своему смещению
dyn=[p for p in ph if p[0]==2][0]; ents=[struct.unpack_from('<qQ',R,dyn[2]+16*i) for i in range(31)]
tags={t:v for t,v in ents}
check('T4 DYNAMIC на месте и валиден', tags.get(5)==0x2240 and tags.get(6)==0x288 and tags.get(7)==0x3660, f'STRTAB={tags.get(5):#x} SYMTAB={tags.get(6):#x} RELA={tags.get(7):#x}')
# 5 .eh_frame_hdr: каждая запись указывает на FDE, чей pc_begin совпадает с таблицей
h=[p for p in ph if p[0]==0x6474e550][0][2]
ver,pe,fe,te=R[h:h+4]; fc,=struct.unpack_from('<I',R,h+8)
ehf=[s for s in secs if s[3]==0x42d9d60][0]
ok=0
for i in range(fc):
    ipc,ifde=struct.unpack_from('<ii',R,h+12+8*i)
    f=h+ifde  # vaddr==offset для этого сегмента
    ln,cie=struct.unpack_from('<Ii',R,f)
    pcb,=struct.unpack_from('<i',R,f+8)
    if cie and f+8+pcb==h+ipc: ok+=1
check('T5 все FDE из .eh_frame_hdr совпадают с .eh_frame', ok==fc, f'{ok}/{fc}')
# 6 инструменты binutils
def run(*a): return subprocess.run(a,capture_output=True,text=True)
f=f'{ROOT}/Repaired/Test_repaired.so'
r=run('readelf','-hSlWd',f); check('T6 readelf без ошибок', r.returncode==0 and 'Error' not in r.stderr, r.stderr.strip()[:120])
r=run('nm','-D','--defined-only',f); ex=[l.split()[-1].split('@')[0] for l in r.stdout.splitlines()]
check('T7 nm -D читает экспорт', r.returncode==0 and 'JNI_OnLoad' in ex, ', '.join(ex))
r=run('llvm-objdump','-d','--no-show-raw-insn','--start-address=0x5ac3bf0','--stop-address=0x5ac3cc4',f)
lines=[l for l in r.stdout.splitlines() if re.match(r'\s+[0-9a-f]+:',l)]
check('T8 llvm-objdump: JNI_OnLoad (212 байт) = пролог sub sp … и ret', r.returncode==0 and re.search(r'sub\s+sp',lines[0]) and any(re.search(r'\sret',l) for l in lines[-4:]), lines[0].strip()+' … '+lines[-1].strip())
# 9 релокации, указывающие на строки в .rodata ПОСЛЕ точки вставки: ремонт vs оригинал (контроль)
ro=[s for s in secs if s[3]==0x1a1ea40][0]; roend=ro[3]+ro[5]
pat=re.compile(rb'[A-Za-z_%][\x20-\x7e]{5,}\x00')
tgt=[a for o,inf,a in struct.iter_unpack('<QQq',R[0x3660:0x3660+0x1a199a8]) if inf&0xffffffff==0x403 and P<=a<roend]
gR=sum(1 for a in tgt if R[a-1]==0 and pat.match(R,a)); gO=sum(1 for a in tgt if O[a-1]==0 and pat.match(O,a))
check('T9 указатели из релокаций на строки после точки вставки', gR>10*max(gO,1), f'отремонтированный: {gR}/{len(tgt)} попадают точно на начало строки, оригинал (контроль): {gO}/{len(tgt)}')
print(f'\nИТОГ: {sum(res)}/{len(res)} PASS'); sys.exit(0 if all(res) else 1)
