module.exports = async (c) => {
  const since = Date.now() / 1000 - 130 * 3600;
  const ds = await c.getDialogs({ limit: 200 });
  for (const d of ds) {
    if (!d.isUser || !d.message || d.message.date < since || d.entity.self) continue;
    const ms = await c.getMessages(d.entity, { limit: 10 });
    for (const m of ms) if (!m.out && m.date >= since && /побед|выигр|поздравл|приз|winner|congrat/i.test(m.message || ""))
      console.log("DM", "@" + (d.entity.username || d.title), new Date(m.date * 1000).toISOString().slice(5, 16), "|", (m.message || "").replace(/\n/g, " ").slice(0, 200));
  }
  console.log("checked DMs");
};
