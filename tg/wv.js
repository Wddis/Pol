// args via env: BOT, APP, START
module.exports = async (c, Api) => {
  const bot = await c.getInputEntity(process.env.BOT);
  const r = await c.invoke(new Api.messages.RequestAppWebView({
    peer: "me",
    app: new Api.InputBotAppShortName({ botId: new Api.InputUser({ userId: bot.userId, accessHash: bot.accessHash }), shortName: process.env.APP }),
    startParam: process.env.START, platform: "android", writeAllowed: true,
  }));
  require("fs").writeFileSync(__dirname + "/webapp_url.txt", r.url, { mode: 0o600 });
  console.log("url host:", new URL(r.url).host);
};
