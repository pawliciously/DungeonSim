import { existsSync, readFileSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { basename, join } from "node:path";
import { gzipSync } from "node:zlib";

const args = process.argv.slice(2);
const outAt = args.indexOf("--out");
const out = outAt >= 0 ? args[outAt + 1] : null;
const dir = args.find((a, i) => a !== "--out" && i !== outAt + 1);
if (!dir || (outAt >= 0 && !out)) {
  console.error("usage: upload-secrets.mjs <secrets dir> [--out <secrets.json>]");
  process.exit(1);
}

const COUNTED = new Set(["chest", "item", "bat", "wither"]);
const NOT_ROOMS = new Set(["floorskulls.json", "mimic.json"]);

function walk(d) {
  const out = [];
  for (const name of readdirSync(d)) {
    const full = join(d, name);
    if (statSync(full).isDirectory()) out.push(...walk(full));
    else if (name.endsWith(".json") && !NOT_ROOMS.has(name)) out.push(full);
  }
  return out;
}

const rooms = {};
let secrets = 0;
for (const file of walk(dir)) {
  const room = basename(file, ".json");
  const json = JSON.parse(readFileSync(file, "utf8"));
  const list = (json.secrets ?? []).map(s => ({ name: s.secretName, category: s.category, x: s.x, y: s.y, z: s.z, itemChest: s.itemChest || undefined }));
  const suffix = room.replace(/-(high|low)$/, "").match(/-(\d+)$/);
  const count = suffix ? Number(suffix[1]) : list.filter(s => COUNTED.has(s.category)).length;
  rooms[room] = { count, secrets: list };
  secrets += list.length;
}

const mimicFile = join(dir, "mimic.json");
const mimic = existsSync(mimicFile) ? JSON.parse(readFileSync(mimicFile, "utf8")) : undefined;
const raw = Buffer.from(JSON.stringify({ rooms, mimic }));
if (out) {
  writeFileSync(out, raw);
  console.log(`Wrote ${out}: ${Object.keys(rooms).length} rooms, ${secrets} spots${mimic ? ", mimic" : ""}.`);
  process.exit(0);
}
const { call } = await import("./bridge.mjs");
await call("/files/put", { path: "secrets.json", gzip: gzipSync(raw).toString("base64") });
console.log(`Uploaded secrets.json: ${Object.keys(rooms).length} rooms, ${secrets} spots${mimic ? ", mimic" : ""}. Run /ds reload on the server.`);
