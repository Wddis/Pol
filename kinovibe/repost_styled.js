// Re-publish posts that cannot be edited (forwards) as our own posts with the styled caption.
// Reads SPEC (default new.json, see style_posts.js), keeps channel order, posts silently one by one,
// waits for the discussion thread, deletes the original, then rewrites SPEC with the new ids.
// Afterwards run style_posts.js (navigator + registry) and apply_covers.js.
// Splitting an album with several films: give each film its own spec item with the id of its video;
// with REPOST_FROM each film becomes a separate post (originals are deleted after the new post has comments).
const fs = require("fs");
const { HTMLParser } = require("telegram/extensions/html");
const { caption } = require("./style_posts.js");
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

module.exports = async (client, Api) => {
  const specPath = process.env.SPEC || "new.json";
  const spec = JSON.parse(fs.readFileSync(specPath, "utf8"));
  const ch = await client.getEntity("kinovibetwitch");
  const order = [...spec].sort((a, b) => a.ids[0] - b.ids[0]);
  for (const p of order) {
    const msgs = (await client.getMessages(ch, { ids: p.ids })).filter(Boolean);
    if (msgs.length !== p.ids.length) { console.log("SKIP missing messages", p.ids); continue; }
    // REPOST_FROM=<id>: also re-publish our own posts from this id on (to split multi-film albums
    // into separate posts while keeping channel order). Otherwise only forwards are re-published.
    const force = process.env.REPOST_FROM && p.ids[0] >= Number(process.env.REPOST_FROM);
    if (!msgs[0].fwdFrom && !force) { console.log("not a forward, edit in place:", p.ids[0]); continue; }
    const [text, entities] = HTMLParser.parse(caption(p));
    const rnd = () => BigInt(Date.now()) * 1000n + BigInt(Math.floor(Math.random() * 1000));
    const media = (m) => new Api.InputMediaDocument({ id: new Api.InputDocument({ id: m.media.document.id, accessHash: m.media.document.accessHash, fileReference: m.media.document.fileReference }) });
    const res = msgs.length === 1
      ? await client.invoke(new Api.messages.SendMedia({ peer: ch, media: media(msgs[0]), message: text, entities, randomId: rnd(), silent: true }))
      : await client.invoke(new Api.messages.SendMultiMedia({ peer: ch, silent: true, multiMedia: msgs.map((m, i) => new Api.InputSingleMedia({ media: media(m), randomId: rnd(), message: i === 0 ? text : "", entities: i === 0 ? entities : [] })) }));
    const newIds = res.updates.filter((u) => u.className === "UpdateNewChannelMessage").map((u) => u.message.id).sort((a, b) => a - b);
    let ok = false;
    for (let i = 0; i < 12 && !ok; i++) {
      await sleep(5000);
      const nm = (await client.getMessages(ch, { ids: [newIds[0]] }))[0];
      ok = !!(nm && nm.replies && nm.replies.comments);
    }
    console.log(`${p.ids.join("+")} -> ${newIds.join("+")} comments:${ok} | ${p.title}`);
    if (!ok) { console.log("STOP: no discussion thread, original kept"); break; }
    await client.deleteMessages(ch, p.ids, { revoke: true });
    p.ids = newIds;
    fs.writeFileSync(specPath, JSON.stringify(spec, null, 1));
    await sleep(15000);
  }
};
