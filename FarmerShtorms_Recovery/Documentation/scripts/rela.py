import struct,sys,collections
f=open(sys.argv[1],'rb').read(); D=0x841
n=0x1a199a8//24
ts=[]
for k in range(n):
    o,i,a=struct.unpack_from('<QQq',f,0x3660+k*24)
    if i==0x403 and 0x1a1ea40<=a<0x3b8154c: ts.append(a)
def ok(o): return f[o-1]==0 and all(32<=c<127 for c in f[o:o+3])
lo,hi,st=[int(x,16) for x in sys.argv[2:5]]
b=collections.defaultdict(lambda:[0,0])
for t in ts:
    if lo<=t<hi: x=b[(t-lo)//st]; x[0]+=ok(t); x[1]+=ok(t+D)
for k in sorted(b): print(hex(lo+k*st), b[k])
print(len(ts))
