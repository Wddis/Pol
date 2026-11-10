// Ranged sequential download into a shared file: MID OUT FROM_MB TO_MB (one process per range)
const fs = require("fs");
module.exports = async (client) => {
  const ch = await client.getEntity("kinovibetwitch");
  const m = (await client.getMessages(ch, { ids: [Number(process.env.MID)] }))[0];
  const MB = 1024 * 1024, total = Number(m.media.document.size);
  const from = Number(process.env.FROM_MB) * MB, to = Math.min(total, Number(process.env.TO_MB) * MB);
  const fd = fs.openSync(process.env.OUT, "r+"); let pos = from; const t0 = Date.now(); let last = 0;
  for await (const c of client.iterDownload({ file: m.media, offset: require("big-integer")(from), requestSize: MB, limit: Math.ceil((to - from) / MB) })) {
    const n = Math.min(c.length, to - pos); fs.writeSync(fd, c, 0, n, pos); pos += n;
    if (pos - from - last >= 200 * MB) { last = pos - from; console.log(`[${process.env.FROM_MB}] ${((pos - from) / MB) | 0}/${((to - from) / MB) | 0} MB ${((pos - from) / MB / ((Date.now() - t0) / 1000)).toFixed(2)} MB/s`); }
    if (pos >= to) break;
  }
  fs.closeSync(fd); console.log(`[${process.env.FROM_MB}] range done`, ((pos - from) / MB) | 0, "MB");
};
