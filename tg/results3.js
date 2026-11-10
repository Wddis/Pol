module.exports = async (c) => {
  const map = JSON.parse(require("fs").readFileSync(__dirname + "/state/srcposts.json"));
  
  for (const [tag, [ch, id]] of Object.entries(map)) {
    try {
      const [m] = await c.getMessages(ch, { ids: [id] });
      if (!m) { console.log(tag.padEnd(9), "пост удалён/недоступен"); continue; }
      const t = m.message || "";
      const btn = m.replyMarkup && m.replyMarkup.rows ? m.replyMarkup.rows.flatMap(r => r.buttons).map(b => b.text).join(",") : "-";
      const me = /dpoltin|sahalinov|6866284629/i.test(t);
      const res = t.match(/(Результаты розыгрыша|Победител[ьи]( розыгрыша)?\s*:|Итоги розыгрыша\s*:|Список победител)[\s\S]{0,220}/i);
      console.log(tag.padEnd(9), ("@" + ch).padEnd(20), "btn:", btn.slice(0, 22).padEnd(22), "|", me ? "★★ ВЫ ПОБЕДИЛИ ★★" : res ? "итоги: " + res[0].replace(/\s+/g, " ").slice(0, 120) : (m.media && m.media.className === "MessageMediaUnsupported" ? "(новый формат поста)" : "итогов нет"));
    } catch (e) { console.log(tag, ch, "ERR", e.errorMessage || e.message); }
  }
};
