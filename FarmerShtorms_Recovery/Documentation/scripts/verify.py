import struct,sys,subprocess
p=sys.argv[1]; f=open(p,'rb').read(); ok=0; tot=0
def t(name,c,info=''):
    global ok,tot; tot+=1; ok+=bool(c); print(('PASS' if c else 'FAIL'),name,info)
shoff,=struct.unpack_from('<Q',f,0x28); n,=struct.unpack_from('<H',f,0x3c)
t('size == e_shoff+shnum*64',len(f)==shoff+n*64,f'{len(f)} vs {shoff+n*64}')
d=[struct.unpack_from('<qQ',f,0xc89fc28+i*16) for i in range(31)]
t('.dynamic at PT_DYNAMIC offset',d[0]==(1,0x13ec) and (14,0x13b9) in d)
t('.eh_frame_hdr magic 011b033b',f[0x3e0fa10:0x3e0fa14].hex()=='011b033b')
cnt,=struct.unpack_from('<I',f,0x3e0fa10+8); hdr=0x3e0fa10; bad=0; prev=-1<<62
for i in range(cnt):
    pc,fde=struct.unpack_from('<ii',f,hdr+12+i*8); pc+=hdr; fde+=hdr
    if pc<prev: bad+=1
    prev=pc
    ln,cie=struct.unpack_from('<II',f,fde)
    if not(0<ln<0x10000 and cie!=0 and 0x42d9d60<=fde<0x57b5468): bad+=1
t('eh_frame_hdr table: sorted, all FDE valid',bad==0,f'{cnt} entries, bad={bad}')
off=0x5ac3bf0-0x4000; w=struct.unpack_from('<53I',f,off)
t('JNI_OnLoad starts with sub sp,sp,#imm',w[0]&0xff8003ff==0xd10003ff,hex(w[0]))
t('JNI_OnLoad ends with ret at +208',w[52]==0xd65f03c0,hex(w[52]))
sn=struct.unpack_from('<I',f,shoff+n*64-64*1)[0]
r=subprocess.run(['readelf','-S','-d','-W',p],capture_output=True,text=True)
t('readelf without errors/warnings',r.returncode==0 and 'Error' not in r.stderr and 'Warning' not in r.stderr, r.stderr[:200])
t('readelf sees .dynamic NEEDED libc.so','libc.so' in r.stdout)
r=subprocess.run(['nm','-D',p],capture_output=True,text=True)
t('nm -D lists JNI_OnLoad','JNI_OnLoad' in r.stdout)
print(f'{ok}/{tot} passed')
