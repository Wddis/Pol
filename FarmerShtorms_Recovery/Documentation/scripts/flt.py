import struct,sys,collections
f=open(sys.argv[1],'rb').read(); D=0x841
TV=0x57b9480; TO=0x57b5480+D; L=0x6aa4340
w=struct.unpack_from(f'<{L//4}I',f,TO)
def nice(o):
    v=struct.unpack_from('<I',f,o)[0]
    return v!=0 and (v&0xffff)==0  # round floats have zero low mantissa
lo,hi,st=[int(x,16) for x in sys.argv[2:5]]
b=collections.defaultdict(lambda:[0,0])
for k in range(len(w)-1):
    x=w[k]
    if x&0x9f000000==0x90000000:
        y=w[k+1]
        if y&0xffc00000==0xbd400000 and (y>>5)&31==x&31:
            imm=((((x>>5)&0x7ffff)<<2)|((x>>29)&3))
            if imm&(1<<20): imm-=1<<21
            t=((TV+k*4)&~0xfff)+(imm<<12)+((y>>10)&0xfff)*4
            if lo<=t<hi: e=b[(t-lo)//st]; e[0]+=nice(t); e[1]+=nice(t+D)
print(' '.join(f'{lo+k*st:#x}:{v[0]}/{v[1]}' for k,v in sorted(b.items())))
