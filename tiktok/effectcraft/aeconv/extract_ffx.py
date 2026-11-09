"""Извлекает из .ffx/.aep эффекты: имена параметров, значения и ключи (с ease)."""
import json, struct, sys
import os; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rifx import parse

def s_utf8(b):  # 'Utf8' + len + text
    if b[:4] == b'Utf8':
        n = struct.unpack('>I', b[4:8])[0]; return b[8:8 + n].decode('utf8', 'replace')
    return b.decode('latin1').rstrip('\x00')

def mn(b): return b.split(b'\x00')[0].decode('latin1')

def keys_from_list(lst):
    hd = next(c for c in lst if c['id'] == 'lhd3')['data']
    count = struct.unpack('>I', hd[10:14])[0] if False else struct.unpack('>H', hd[10:12])[0]
    size = struct.unpack('>H', hd[18:20])[0]
    # lhd3: ... count @ +10 (u16), item size @ +14 (u16)
    raw = next(c for c in lst if c['id'] == 'ldat')['data']
    n = len(raw) // size if size else 0
    out = []
    for i in range(n):
        it = raw[i * size:(i + 1) * size]
        t = struct.unpack('>I', b'\x00' + it[1:4])[0]
        interp_in, interp_out = it[4], it[5]
        dbl = [struct.unpack('>d', it[8 + 8 * j:16 + 8 * j])[0] for j in range((size - 8) // 8)]
        out.append({'t': t, 'in': interp_in, 'out': interp_out, 'd': dbl})
    return {'size': size, 'keys': out}

def params_of(tdgp_children):
    res = []; cur = None
    for c in tdgp_children:
        if c['id'] == 'tdmn': cur = mn(c['data'])
        elif c.get('type') == 'tdbs' and cur:
            ch = c['children']; p = {'match': cur}
            for k in ch:
                if k['id'] == 'tdsn': p['name'] = s_utf8(k['data'])
                if k['id'] == 'cdat':
                    d = k['data']; p['value'] = [struct.unpack('>d', d[i:i + 8])[0] for i in range(0, min(len(d), 32) - 7, 8)]; p['raw'] = d[:48].hex()
                if k.get('type') == 'list': p['anim'] = keys_from_list(k['children'])
                if k['id'] == 'tdb4': p['tdb4'] = k['data'][:16].hex()
            res.append(p)
        elif c.get('type') == 'tdgp' and cur and not cur.startswith('ADBE Effect Built In'):
            pass
    return res

def effects(tree):
    out = []
    def walk(nodes):
        for n in nodes:
            if n.get('type') == 'sspc':
                name = next((s_utf8(c['data']) for c in n['children'] if c['id'] == 'fnam'), '?')
                pard = {}
                for c in n['children']:
                    if c.get('type') == 'parT':
                        cur = None
                        for k in c['children']:
                            if k['id'] == 'tdmn': cur = mn(k['data'])
                            if k['id'] == 'pard' and cur:
                                pard[cur] = {'ptype': k['data'][15], 'pname': k['data'][16:48].split(b'\x00')[0].decode('latin1')}
                            if k['id'] == 'pdnm' and cur: pard[cur]['options'] = s_utf8(k['data'])
                tdgp = next((c for c in n['children'] if c.get('type') == 'tdgp'), None)
                ps = params_of(tdgp['children']) if tdgp else []
                for p in ps: p.update(pard.get(p['match'], {}))
                out.append({'effect': name, 'params': [p for p in ps if not p['match'].startswith('ADBE Effect Built In')]})
            if 'children' in n: walk(n['children'])
    walk(tree)
    return out

if __name__ == '__main__':
    d = open(sys.argv[1], 'rb').read()
    print(json.dumps(effects(parse(d)), indent=1, ensure_ascii=False))
