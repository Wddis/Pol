const fs = require("fs");
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
module.exports = async (c, Api) => {
  const list = process.env.CHANNELS.split(",");
  const s = JSON.parse(fs.readFileSync(__dirname + "/joined.json"));
  for (const x of list) {
    for (let attempt = 0; attempt < 3; attempt++) {
      try {
        let ch;
        if (x.startsWith("+")) {
          const inv = await c.invoke(new Api.messages.CheckChatInvite({ hash: x.slice(1) }));
          if (inv.className === "ChatInviteAlready" || inv.className === "ChatInvitePeek") {
            ch = inv.chat;
            if (!ch.left) { console.log(x, "already:", ch.title); break; }
            await c.invoke(new Api.channels.JoinChannel({ channel: ch }));
          } else ch = (await c.invoke(new Api.messages.ImportChatInvite({ hash: x.slice(1) }))).chats[0];
        } else {
          ch = await c.getEntity(x);
          if (!ch.left) { console.log(x, "already:", ch.title); break; }
          await c.invoke(new Api.channels.JoinChannel({ channel: ch }));
        }
        s.newly.push(ch.id.toString()); fs.writeFileSync(__dirname + "/joined.json", JSON.stringify(s));
        console.log(x, "joined:", ch.title); break;
      } catch (e) {
        if (e.seconds) { console.log(x, "flood wait", e.seconds); await sleep((e.seconds + 5) * 1000); continue; }
        console.log(x, "ERROR", e.errorMessage || e.message); break;
      }
    }
    await sleep(15000 + Math.floor(Math.random() * 5000));
  }
};
