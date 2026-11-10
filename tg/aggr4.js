const fs = require("fs");
module.exports = async (c, Api) => {
  const out = [];
  const plan = { draweverything: [500, 2200], uchastvovat: [500, 2200], konkursyw: [0, 300], rozygryshix: [0, 300], rozygryshie: [0, 300], prizeHuntChannel: [0, 200], khalyava9: [0, 300] };
  for (const [u, [skip, total]] of Object.entries(plan)) {
    try {
      let offsetId = 0, got = 0;
      if (skip) { const first = await c.getMessages(u, { limit: 1, addOffset: skip - 1 }); offsetId = first[0] ? first[0].id : 0; }
      while (got < total - skip) {
        const ms = await c.getMessages(u, { limit: 100, offsetId });
        if (!ms.length) break;
        for (const m of ms) {
          let src = null;
          if (m.fwdFrom && m.fwdFrom.fromId && m.fwdFrom.fromId.className === "PeerChannel") { const e = (await c.getEntity(m.fwdFrom.fromId).catch(() => null)); if (e) src = { username: e.username, title: e.title, post: m.fwdFrom.channelPost }; }
          const btns = m.replyMarkup && m.replyMarkup.rows ? m.replyMarkup.rows.flatMap(r => r.buttons).filter(b => b.url).map(b => b.text + " -> " + b.url) : [];
          out.push({ agg: u, id: m.id, date: new Date(m.date * 1000).toISOString().slice(0, 10), text: m.message || "", btns, links: (m.entities || []).filter(e => e.url).map(e => e.url), src });
        }
        got += ms.length; offsetId = ms[ms.length - 1].id;
        await new Promise(r => setTimeout(r, 800));
      }
      console.log(u, got);
    } catch (e) { console.log(u, "ERR", e.errorMessage || e.message); }
  }
  fs.writeFileSync(__dirname + "/aggr_posts3.json", JSON.stringify(out));
};
