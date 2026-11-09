"""Извлекает из .aep: композиции, слои, тайминг, дерево свойств (transform/effects/text) с ключами."""
import json, os, struct, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from rifx import parse

TICKS = 30720.0  # тики времени в .ffx/.aep на секунду (beso/cdta)

def u8(b):
    if b[:4] == b'Utf8':
        return b[8:8 + struct.unpack('>I', b[4:8])[0]].decode('utf8', 'replace')
    return b.decode('utf8', 'replace').rstrip('\x00')

def mn(b): return b.split(b'\x00')[0].decode('latin1')

def keylist(lst):
    hd = next(c for c in lst if c['id'] == 'lhd3')['data']
    n = struct.unpack('>H', hd[10:12])[0]; size = struct.unpack('>H', hd[18:20])[0]
    raw = next(c for c in lst if c['id'] == 'ldat')['data']
    keys = []
    for i in range(min(n, len(raw) // size if size else 0)):
        it = raw[i * size:(i + 1) * size]
        t = struct.unpack('>i', b'\x00' + it[1:4])[0] if True else 0
        k = {'tick': t, 'interp': [it[4], it[5]], 'size': size, 'raw': it.hex()}
        if size == 48:  # 1D: value, speedIn, inflIn, speedOut, inflOut
            v = struct.unpack('>5d', it[8:48]); k.update(value=v[0], ease_in=[v[1], v[2]], ease_out=[v[3], v[4]])
        keys.append(k)
    return keys

def propgroup(ch):
    """tdgp children -> list of {match, name, value|keys|children}"""
    out = []; cur = None
    for c in ch:
        if c['id'] == 'tdmn':
            cur = mn(c['data'])
            if cur == 'ADBE Group End': cur = None
            continue
        if cur is None: continue
        if c.get('type') == 'tdbs':
            p = {'match': cur}
            for k in c['children']:
                if k['id'] == 'tdsn': p['name'] = u8(k['data'])
                elif k['id'] == 'cdat':
                    d = k['data']; p['value'] = [round(struct.unpack('>d', d[i:i + 8])[0], 6) for i in range(0, len(d) - 7, 8)][:4]
                elif k.get('type') == 'list': p['keys'] = keylist(k['children'])
                elif k['id'] == 'tdb4': p['dims'] = struct.unpack('>H', k['data'][2:4])[0]
                elif k.get('type') == 'btds': p['textdoc'] = True
                elif k['id'] == 'Utf8': p['expr'] = u8(k['data'])
            out.append(p); cur = None
        elif c.get('type') == 'sspc':
            fn = next((u8(k['data']) for k in c['children'] if k['id'] == 'fnam'), cur)
            tg = next((k for k in c['children'] if k.get('type') == 'tdgp'), None)
            out.append({'match': cur, 'name': fn, 'type': 'effect', 'children': propgroup(tg['children']) if tg else []})
            cur = None
        elif c.get('type') in ('tdgp', 'om-s', 'otst', 'GCst', 'mrst', 'sspc') or 'children' in c:
            name = next((u8(k['data']) for k in c.get('children', []) if k['id'] == 'tdsn'), '')
            out.append({'match': cur, 'name': name, 'type': c.get('type'), 'children': propgroup(c.get('children', []))})
            cur = None
    return out

def text_strings(layer):
    """Строки текста и шрифты из btdk (CoS) — грубо: ищем UTF-16BE текст и имена шрифтов."""
    res = {'texts': [], 'fonts': []}
    def walk(ns):
        for n in ns:
            if n.get('id') == 'btdkraw':
                d = n['data']
                import re
                for m in re.finditer(rb'\(\xfe\xff((?:[\x00-\xff][\x00-\xff])*?)\)', d):
                    try: s = m.group(1).decode('utf-16-be')
                    except Exception: continue
                    if s.strip(): res['texts'].append(s)
                for m in re.finditer(rb'/0 \(\xfe\xff((?:[\x00-\xff][\x00-\xff])*?)\)', d):
                    try: res['fonts'].append(m.group(1).decode('utf-16-be'))
                    except Exception: pass
            if 'children' in n: walk(n['children'])
    walk(layer['children'])
    return res

def layers_of(item):
    out = []
    for c in item['children']:
        if c.get('type') != 'Layr': continue
        L = {'name': next((u8(k['data']) for k in c['children'] if k['id'] == 'Utf8'), '?')}
        ld = next((k['data'] for k in c['children'] if k['id'] == 'ldta'), None)
        if ld:
            L['ldta'] = ld[:64].hex()
        tg = next((k for k in c['children'] if k.get('type') == 'tdgp'), None)
        if tg: L['props'] = propgroup(tg['children'])
        L.update(text_strings(c))
        L['_node'] = c  # сырой узел слоя: aefx читает из него эффекты без потерь
        out.append(L)
    return out

def comps(tree):
    res = []
    def walk(ns):
        for n in ns:
            if n.get('type') == 'Item':
                ch = n['children']
                cd = next((c['data'] for c in ch if c['id'] == 'cdta'), None)
                if cd:
                    res.append({'name': next((u8(c['data']) for c in ch if c['id'] == 'Utf8'), '?'),
                                'size': list(struct.unpack('>HH', cd[140:144])), 'cdta': cd[:160].hex(),
                                'layers': layers_of(n)})
            if 'children' in n: walk(n['children'])
    walk(tree)
    return res

if __name__ == '__main__':
    t = parse(open(sys.argv[1], 'rb').read())
    cs = comps(t)
    for c in cs:
        for L in c['layers']: L.pop('_node', None)
    print(json.dumps(cs, ensure_ascii=False, indent=1))
