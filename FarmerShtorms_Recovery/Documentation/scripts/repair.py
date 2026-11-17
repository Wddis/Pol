import sys
src,dst=sys.argv[1],sys.argv[2]; P=0x2edd675; D=0x841
f=open(src,'rb').read()
open(dst,'wb').write(f[:P]+f[P+D:])
open(dst+'.removed.bin','wb').write(f[P:P+D])
