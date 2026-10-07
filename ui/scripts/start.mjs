// `npm start`: runs the Java backend (scheduler --ui on :8080) and the Vite dev server together,
// then opens the browser. Ctrl+C stops both.
import { spawn, spawnSync } from "node:child_process";
import { existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const uiDir = join(dirname(fileURLToPath(import.meta.url)), "..");
const root = join(uiDir, "..");
// 127.0.0.1, not localhost: Node resolves localhost to IPv6 ::1 first, the backend listens on IPv4
const API = "http://127.0.0.1:8080/api/options";
const children = [];

/** "ours" if the scheduler answers on :8080, "other" if some other program does, "none" if nothing. */
const probe = () => fetch(API)
  .then(async (r) => (r.ok && "strategies" in (await r.json().catch(() => ({})))) ? "ours" : "other")
  .catch(() => "none");
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function stop(code = 0) {
  for (const c of children) c.kill("SIGTERM");
  process.exit(code);
}
process.on("SIGINT", () => stop(0));
process.on("SIGTERM", () => stop(0));

if (!existsSync(join(uiDir, "node_modules", ".bin", "vite"))) {
  console.log("Installing UI dependencies (first run only)...");
  const r = spawnSync("npm", ["install", "--no-audit", "--no-fund"], { cwd: uiDir, stdio: "inherit" });
  if (r.status !== 0) process.exit(r.status ?? 1);
}

const state = await probe();
if (state === "ours") {
  console.log("Scheduler backend already running on :8080, reusing it.");
} else if (state === "other") {
  console.error("Port 8080 is used by another program. Stop it (find it with: lsof -i :8080) and run npm start again.");
  process.exit(1);
} else {
  console.log("Starting the scheduler backend on :8080 (Java)...");
  const backend = spawn("bash", [join(root, "scripts", "ui.sh"), "8080"], {
    cwd: root, stdio: "inherit", env: { ...process.env, NO_OPEN: "1" },
  });
  children.push(backend);
  backend.on("exit", (code) => {
    console.error(`\nScheduler backend exited (code ${code}). Is port 8080 already in use?`);
    stop(1);
  });
  for (let i = 0; (await probe()) !== "ours"; i++) {
    if (i > 600) { console.error("Backend did not start within 2 minutes."); stop(1); }
    await sleep(200);
  }
}

console.log("Starting the web UI (Vite)...");
const vite = spawn(join(uiDir, "node_modules", ".bin", "vite"), ["--open"], { cwd: uiDir, stdio: "inherit" });
children.push(vite);
vite.on("exit", (code) => stop(code ?? 0));
