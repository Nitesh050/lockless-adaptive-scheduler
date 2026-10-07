import { useState } from "react";
import { api, type DeadlockResult } from "../api";
import { fmt, type Theme } from "../theme";
import { PhilosopherRing } from "./PhilosopherRing";
import { StatTiles } from "./StatTiles";

export function DeadlockView({ theme }: { theme: Theme }) {
  const [ph, setPh] = useState(5);
  const [meals, setMeals] = useState(20);
  const [hold, setHold] = useState(200);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState<{ text: string; error?: boolean }>({ text: "" });
  const [result, setResult] = useState<DeadlockResult | null>(null);

  async function onRun() {
    setBusy(true);
    setStatus({ text: "Running dining philosophers…" });
    try {
      const r = await api.deadlock(ph, meals, hold);
      setResult(r);
      setStatus({ text: `Finished in ${fmt(r.elapsedMs, 1)} ms.` });
    } catch (e) {
      setStatus({ text: `Error: ${(e as Error).message}`, error: true });
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="controls">
        <label className="field">Philosophers
          <input type="number" min={2} max={8} value={ph} onChange={(e) => setPh(Number(e.target.value))} disabled={busy} /></label>
        <label className="field">Meals each
          <input type="number" min={1} max={200} value={meals} onChange={(e) => setMeals(Number(e.target.value))} disabled={busy} /></label>
        <label className="field">Hold time (µs)
          <input type="number" min={0} max={5000} value={hold} onChange={(e) => setHold(Number(e.target.value))} disabled={busy} /></label>
        <button className="btn primary" onClick={onRun} disabled={busy}>Run dining philosophers</button>
      </div>
      <p className="desc">Each philosopher picks up the left fork, waits, then reaches for the right one. If everyone holds a
        left fork, nobody can eat: a deadlock. The resource manager finds the cycle in its wait-for graph and aborts one
        philosopher, who puts the fork down and retries.</p>
      <p className={`status ${status.error ? "error" : ""} ${busy ? "busy" : ""}`}>{status.text}</p>

      {result && (
        <StatTiles tiles={[
          { label: "Deadlocks detected and broken", value: fmt(result.deadlocksDetected), note: "cycles found in the wait-for graph" },
          { label: "Meals eaten", value: `${fmt(result.mealsEaten)} / ${fmt(result.mealsExpected)}`,
            note: result.mealsEaten === result.mealsExpected ? "✓ everyone finished" : "✗ incomplete",
            tone: result.mealsEaten === result.mealsExpected ? "good" : "bad" },
          { label: "Run time", value: `${fmt(result.elapsedMs, 1)} ms`, note: `${result.philosophers} philosophers` },
        ]} />
      )}

      <div className="grid2">
        <section className="card">
          <h2>First deadlock detected</h2>
          <p className="hint">Solid line = holds the fork. Dashed line = waiting for it. Red = aborted to break the cycle.</p>
          {result ? <PhilosopherRing result={result} theme={theme} /> : <p className="empty">Run the demo to see a detected cycle.</p>}
        </section>
        <section className="card">
          <h2>Detected cycles</h2>
          <p className="hint">Up to 10, in the order they were found.</p>
          {result?.cycles.length ? (
            <div className="table-wrap">
              <table>
                <thead><tr><th className="l">#</th><th className="l">Cycle</th><th className="l">Aborted</th></tr></thead>
                <tbody>
                  {result.cycles.map((c, i) => (
                    <tr key={i}>
                      <td className="l">{i + 1}</td>
                      <td className="l">{c.philosophers.map((p, j) => `P${p} waits for ${c.resources[j]}`).join(" → ")} → back to start</td>
                      <td className="l">P{c.victim}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : <p className="empty">{result ? "No deadlock formed this run." : "—"}</p>}
        </section>
      </div>
    </>
  );
}
