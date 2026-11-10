// Sets custom video covers (covers/<msgId>.jpg) on channel posts listed in posts.json.
// Optional "start" (seconds) is applied to the first part as the playback start time.
const fs = require("fs");
const { CustomFile } = require("telegram/client/uploads");
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
module.exports = async (client, Api) => {
  const ch = await client.getEntity("kinovibetwitch");
  const posts = JSON.parse(fs.readFileSync("posts.json", "utf8"));
  const DONE = "covers_done.json";
  const done = fs.existsSync(DONE) ? JSON.parse(fs.readFileSync(DONE, "utf8")) : [];
  const only = process.env.ONLY ? process.env.ONLY.split(",").map(Number) : null;
  for (const p of posts) {
    for (const [i, mid] of p.ids.entries()) {
      if (only && !only.includes(mid)) continue;
      const path = `covers/${mid}.jpg`;
      const m = (await client.getMessages(ch, { ids: [mid] }))[0];
      if (!m || !m.media || !m.media.document) { console.log(mid, "SKIP: no video"); continue; }
      // skip only videos whose cover WE set earlier (covers_done.json); an uploader's own cover gets replaced
      if (done.includes(mid) && m.media.videoCover && !process.env.FORCE) { console.log(mid, "already has our cover"); continue; }
      const up = await client.uploadFile({ file: new CustomFile(`cover${mid}.jpg`, fs.statSync(path).size, path), workers: 1 });
      const ph = (await client.invoke(new Api.messages.UploadMedia({ peer: ch, media: new Api.InputMediaUploadedPhoto({ file: up }) }))).photo;
      const d = m.media.document;
      const media = new Api.InputMediaDocument({
        id: new Api.InputDocument({ id: d.id, accessHash: d.accessHash, fileReference: d.fileReference }),
        videoCover: new Api.InputPhoto({ id: ph.id, accessHash: ph.accessHash, fileReference: ph.fileReference }),
        ...(i === 0 && p.start ? { videoTimestamp: p.start } : {}),
      });
      for (let attempt = 0; ; attempt++) {
        try {
          await client.invoke(new Api.messages.EditMessage({ peer: ch, id: mid, media, message: m.message || "", entities: m.entities || [] }));
          break;
        } catch (e) {
          const wait = e.seconds || (/FLOOD/.test(e.errorMessage || e.message) ? 60 : 0);
          if (!wait || attempt > 5) throw e;
          console.log(mid, `flood wait ${wait}s`);
          await sleep((wait + 2) * 1000);
        }
      }
      const chk = (await client.getMessages(ch, { ids: [mid] }))[0];
      console.log(mid, "cover:", !!chk.media.videoCover, "start:", chk.media.videoTimestamp || "-", "caption kept:", (chk.message || "") === (m.message || ""));
      if (chk.media.videoCover && !done.includes(mid)) { done.push(mid); fs.writeFileSync(DONE, JSON.stringify(done.sort((a, b) => a - b))); }
      await sleep(3000);
    }
  }
};
