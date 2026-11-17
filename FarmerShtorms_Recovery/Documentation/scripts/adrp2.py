import struct,sys,collections
f=open(sys.argv[1],'rb').read()
D=0x841; TV=0x57b9480; TO=0x57b5480+D; L=0x6aa4340
words=struct.unpack_from(f'<{L//4}I',f,TO)
res=[]
for k in range(len(words)-1):
    w=words[k]
    if w&0x9f000000==0x90000000:
        w2=words[k+1]; rd=w&31
        if w2&0xffc00000==0x91000000 and (w2>>5)&31==rd:
            imm=((((w>>5)&0x7ffff)<<2)|((w>>29)&3))
            if imm&(1<<20): imm-=1<<21
            t=((TV+k*4)&~0xfff)+(imm<<12)+((w2>>10)&0xfff)
            if 0x1a1ea40<=t<0x3b8154c: res.append(t)
def ok(o): return f[o-1]==0 and all(32<=c<127 for c in f[o:o+4])
lo,hi=int(sys.argv[2],16),int(sys.argv[3],16); step=int(sys.argv[4],16)
b=collections.defaultdict(lambda:[0,0])
for t in res:
    if lo<=t<hi: x=b[(t-lo)//step]; x[0]+=ok(t); x[1]+=ok(t+D)
for k in sorted(b): print(hex(lo+k*step), b[k])
