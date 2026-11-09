"""Минимальный парсер RIFX (формат .ffx / .aep After Effects): дерево чанков."""
import struct, sys

def parse(d, off=0, end=None, depth=0):
    end = len(d) if end is None else end
    out = []
    while off + 8 <= end:
        cid = d[off:off+4].decode('latin1'); size = struct.unpack('>I', d[off+4:off+8])[0]
        body = off + 8
        if cid in ('LIST', 'RIFX'):
            typ = d[body:body+4].decode('latin1')
            if typ == 'btdk':  # текстовый документ: сырой CoS, не чанки
                out.append({'id': cid, 'type': typ, 'children': [{'id': 'btdkraw', 'data': d[body+4:body+size]}]})
            else:
                out.append({'id': cid, 'type': typ, 'children': parse(d, body+4, body+size, depth+1)})
        else:
            out.append({'id': cid, 'data': d[body:body+size]})
        off = body + size + (size & 1)
    return out

def dump(nodes, ind=0, maxdata=48):
    for n in nodes:
        if 'children' in n:
            print(' ' * ind + f"{n['id']}:{n['type']}"); dump(n['children'], ind + 2, maxdata)
        else:
            dd = n['data']; txt = dd[:maxdata]
            s = ''.join(chr(c) if 32 <= c < 127 else '.' for c in txt)
            print(' ' * ind + f"{n['id']} [{len(dd)}] {txt.hex()[:96]} |{s}|")

if __name__ == '__main__':
    d = open(sys.argv[1], 'rb').read()
    dump(parse(d))
