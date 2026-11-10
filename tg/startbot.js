const sleep = (ms) => new Promise(r => setTimeout(r, ms));
module.exports = async (c, Api) => {
  const bot = await c.getEntity(process.env.BOT);
  console.log("bot:", bot.firstName, "@" + bot.username);
  await c.invoke(new Api.messages.StartBot({ bot, peer: bot, startParam: process.env.START, randomId: BigInt(Date.now()) }));
  await sleep(6000);
  const ms = await c.getMessages(bot, { limit: 3 });
  for (const m of ms.reverse()) {
    console.log("---", m.id, m.out ? "OUT" : "BOT", (m.message || "[media]").slice(0, 500).replace(/\n/g, " | "));
    if (m.replyMarkup && m.replyMarkup.rows) for (const r of m.replyMarkup.rows) for (const b of r.buttons) console.log("  btn", b.className, b.text, b.url || (b.data ? b.data.toString() : ""));
  }
};
