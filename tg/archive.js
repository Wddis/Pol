const fs = require("fs");
module.exports = async (c, Api) => {
  const s = JSON.parse(fs.readFileSync(__dirname + "/joined.json"));
  s.archived = s.archived || [];
  const todo = s.newly.filter(id => !s.archived.includes(id));
  const peers = [];
  for (const id of todo) peers.push(new Api.InputFolderPeer({ peer: await c.getInputEntity(new Api.PeerChannel({ channelId: BigInt(id) })), folderId: 1 }));
  if (peers.length) await c.invoke(new Api.folders.EditPeerFolders({ folderPeers: peers }));
  s.archived.push(...todo);
  fs.writeFileSync(__dirname + "/joined.json", JSON.stringify(s));
  console.log("archived", todo.length);
};
