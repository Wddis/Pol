import json, re, sys
S = sys.argv[1]
raw = {m['id']: m['text'] for m in json.load(open(f'{S}/saved_raw.json'))}
src = raw[120049] + '\n' + raw[120050]
status = {}
for l in open(f'{S}/unsub_status_a1.txt'):
    m = re.match(r'^([=→]) .*?@(\w+)\s+\((.*?)\)', l)
    if m: status[m.group(2).lower()] = 'a' if 'архиве' in m.group(3) else ('m' if 'общем' in m.group(3) else 'n')
# result times from the 06:28 list, by @channel
times = {}
for l in raw[120002].split('\n'):
    m = re.match(r'^(?:🆕 )?~?(\d\d\.\d\d) (\d\d:\d\d) — .*?@(\w+)', l)
    if m: times[m.group(3).lower()] = m.group(2)
items, day = [], None
for l in src.split('\n'):
    l = l.strip()
    if l.startswith('🗓'): day = l[1:].strip(); continue
    if not l.startswith('•'): continue
    body = l[1:].strip()
    title, _, chans = body.rpartition(': ') if ': @' in body or 'без ссылки' in body else (body, '', '')
    if 'без ссылки' in body: title, chans = body.split(': (')[0], ''
    users = re.findall(r'@(\w+)', chans)
    key = (day, title.replace(' · VG/Двач', ''), tuple(u.lower() for u in users))
    if any(k[2] == key[2] and k[0] == day and users for k in [i['key'] for i in items]): continue  # duplicate line (same day, same channels)
    items.append({'key': key, 'day': day, 'title': title, 'users': users, 'nolink': 'без ссылки' in body})
MARK = {'a': '📦', 'm': '📌', 'n': '❔'}
def ch(u): return f"@{u} {MARK.get(status.get(u.lower(), 'n'))}"
weeks = [('17–23 НОЯБРЯ', lambda d: d.endswith('.11') and int(d[:2]) <= 23), ('24–30 НОЯБРЯ', lambda d: d.endswith('.11') and int(d[:2]) >= 24),
         ('ДЕКАБРЬ', lambda d: d.endswith('.12')), ('БЕЗ ДАТЫ', lambda d: not re.match(r'\d\d\.\d\d', d))]
allu = sorted({u.lower() for i in items for u in i['users']})
na = sum(status.get(u) == 'a' for u in allu); nm = sum(status.get(u) == 'm' for u in allu); nn = len(allu) - na - nm
nolink = [i for i in items if i['nolink']]
first = next(i['day'] for i in items)
head = f"""📋 ОТПИСАТЬСЯ ПОСЛЕ ИТОГОВ — обновлено 17.11
━━━━━━━━━━━━━━━
📊 Конкурсов в списке: {len(items)} · каналов: {len(allu)} (+{len(nolink)} без ссылки)
📦 Уже в архиве: {na}
📌 Ещё в общем списке: {nm} — в архив пока НЕ перенесены
🗓 Ближайшие итоги: {first} ({sum(i['day'] == first for i in items)} конкурса)
━━━━━━━━━━━━━━━

ℹ️ КАК ПОЛЬЗОВАТЬСЯ
• Наступила дата итогов → открыть канал, найти пост с итогами, проверить, есть ли мы среди победителей
• Нас нет → отписаться от ВСЕХ каналов этого конкурса (их может быть несколько — указаны в строке)
• Выиграли → написать организатору, от каналов не отписываться до получения приза
• Время итогов (МСК) указано, где оно известно

🔖 ОБОЗНАЧЕНИЯ
📦 — канал в архиве
📌 — канал ещё в общем списке чатов
❔ — канала нет в списке чатов (не подписаны или ссылка изменилась)
🔍 — канал без ссылки: искать в архиве по названию

⚠️ КАНАЛЫ БЕЗ ССЫЛКИ (искать в архиве по названию):
""" + '\n'.join(f"• {i['day']} — {i['title']}" for i in nolink) + "\n\nСписок по датам — в следующем сообщении 👇"
blocks = []
for name, f in weeks:
    its = [i for i in items if f(i['day'])]
    if not its: continue
    lines = [f"{name} · конкурсов: {len(its)}"]
    for i in its:
        t = next((times[u.lower()] for u in i['users'] if u.lower() in times), '')
        d = i['day'] if re.match(r'\d\d\.\d\d', i['day']) else ''
        pre = (d + (' ' + t if t else '') + ' — ') if d else ''
        chans = ' '.join(ch(u) for u in i['users']) if i['users'] else '🔍 без ссылки'
        lines.append(f"{pre}{i['title']} → {chans}")
    blocks.append('\n'.join(lines))
# pack into messages < 3900 chars
msgs, cur = [head], f"📅 ОТПИСКА ПО ДАТАМ ({len(items)}) · 📦 архив · 📌 общий список · 🔍 без ссылки\n"
for b in blocks:
    if len(cur) + len(b) + 2 > 3900: msgs.append(cur.rstrip() + "\n\nПродолжение — в следующем сообщении 👇"); cur = ''
    cur += '\n' + b + '\n'
msgs.append(cur.strip())
open(f'{S}/report_a1.txt', 'w').write('\n=====\n'.join(msgs))
print([len(m) for m in msgs], len(items), len(allu), na, nm, nn)
