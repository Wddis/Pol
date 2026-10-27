// Воркер и бот на Python (bot/oda_bot) должны выглядеть и подписывать одинаково:
// все экраны (текст, кнопки, данные кнопок) и лицензии сравниваются один в один.
// Запуск: PYTHON=<python с зависимостями bot/requirements.txt> npm run parity
import { execFileSync } from "node:child_process";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { randomBytes } from "node:crypto";
import assert from "node:assert/strict";
import { build } from "esbuild";

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..");
const PY = process.env.PYTHON || "python3";
const tmp = mkdtempSync(join(tmpdir(), "oda-parity-"));
try {
  for (const m of ["views", "license"]) {
    await build({ entryPoints: [join(ROOT, `src/${m}.ts`)], bundle: true, format: "esm", platform: "node",
      outfile: join(tmp, `${m}.mjs`), logLevel: "error" });
  }

  const ts = JSON.parse(execFileSync("node", [join(ROOT, "test/parity/screens.mjs"), join(tmp, "views.mjs")]).toString());
  const py = JSON.parse(execFileSync(PY, [join(ROOT, "test/parity/screens.py")]).toString());
  assert.deepEqual(Object.keys(ts).sort(), Object.keys(py).sort());
  for (const name of Object.keys(py)) assert.deepEqual(ts[name], py[name], `экран ${name}`);
  console.log(`  ✓ экраны: ${Object.keys(py).length} одинаковы`);

  const seed = randomBytes(32).toString("base64url");
  const payload = { v: 1, key: "ODA-ACDE-FGHJ-KMNP", dev: "phone-A-0001", tariff: "month", devices: 2, exp: 1790000000, iat: 1789000000 };
  const { Signer } = await import(join(tmp, "license.mjs"));
  const s = await Signer.load(seed);
  const token = await s.sign(payload);
  const pyOut = execFileSync(PY, ["-c", `
import base64, json, sys
sys.path.insert(0, sys.argv[1])
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from oda_bot.license import Signer, verify
seed = sys.argv[2]
s = Signer(Ed25519PrivateKey.from_private_bytes(base64.urlsafe_b64decode(seed + "=" * (-len(seed) % 4))))
print(json.dumps({"pub": s.public_b64(), "token": s.sign(json.loads(sys.argv[3])), "verifies": verify(sys.argv[4], s.public_b64()) is not None}))
`, join(ROOT, ".."), seed, JSON.stringify(payload), token]).toString();
  const p = JSON.parse(pyOut);
  assert.equal(s.publicB64, p.pub, "открытый ключ");
  assert.equal(token, p.token, "лицензия");
  assert.ok(p.verifies, "Python принимает лицензию воркера");
  console.log("  ✓ лицензии: тот же ключ — та же подпись, Python её принимает");
} finally {
  rmSync(tmp, { recursive: true, force: true });
}
