import { useMemo, useState, type CSSProperties } from "react";
import { api, type Options, type RunResult, type Strategy, type WorkloadName } from "../api";
import { FIXED, fmt, LABEL, MODE_OF, ORDER, signed, type Theme } from "../theme";
import { ControllerChart } from "./ControllerChart";
import { ProgressChart } from "./ProgressChart";
import { RunTimeBars } from "./RunTimeBars";
import { StatTiles, type Tile } from "./StatTiles";
import { WorkerBars } from "./WorkerBars";

type Store = Record<string, Partial<Record<Strategy, RunResult>>>;

export function RunView({ options, theme }: { options: Options; theme: Theme }) {
  const [workload, setWorkload] = useState<WorkloadName>("shifting");
  const [workers, setWorkers] = useState(8);
  const [strategy, setStrategy] = useState<Strategy>("adaptive");
  const [withHalf, setWithHalf] = useState(false);
  const [store, setStore] = useState<Store>({});
  const [selected, setSelected] = useState<Strategy | null>(null);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState<{ text: string; error?: boolean }>({ text: "" });

  const key = `${workload}-${workers}`;
  const runs = useMemo(() => {
    const set = store[key] ?? {};
    return ORDER.filter((s) => set[s]).map((s) => set[s]!);
  }, [store, key]);
  const shown = runs.find((r) => r.strategy === selected) ?? runs[runs.length - 1];
  const adaptive = runs.find((r) => r.strategy === "adaptive");

  async function runOne(s: Strategy) {
    const r = await api.run(workload, workers, s);
    setStore((prev) => ({ ...prev, [key]: { ...prev[key], [s]: r } }));
    return r;
  }

  async function onRun() {
    setBusy(true);
    setStatus({ text: `Running ${LABEL[strategy]}… (one warm-up run, then one measured run)` });
    try {
      const r = await runOne(strategy);
      setSelected(strategy);
      setStatus({ text: `${LABEL[strategy]} finished in ${fmt(r.elapsedMs, 1)} ms.` });
    } catch (e) {
      setStatus({ text: `Error: ${(e as Error).message}`, error: true });
    } finally {
      setBusy(false);
    }
  }

  async function onCompare() {
    const list: Strategy[] = ["static", "wait", "nowait", "adaptive", ...(withHalf ? (["nowait-half"] as Strategy[]) : [])];
    setBusy(true);
    try {
      const done: Partial<Record<Strategy, RunResult>> = {};
      for (const [i, s] of list.entries()) {
        setStatus({ text: `Comparing… ${i + 1}/${list.length}: ${LABEL[s]}` });
        done[s] = await runOne(s);
        setSelected(s);
      }
      setSelected("adaptive");
      const best = FIXED.map((s) => done[s]!).reduce((a, b) => (a.elapsedMs < b.elapsedMs ? a : b));
      const gain = (100 * (best.elapsedMs - done.adaptive!.elapsedMs)) / best.elapsedMs;
      setStatus({ text: `Done. Adaptive ${fmt(done.adaptive!.elapsedMs)} ms vs best fixed (${best.label}) ${fmt(best.elapsedMs)} ms: ${signed(gain)}. Single runs vary by a few %; the saved results use 10 runs each.` });
    } catch (e) {
      setStatus({ text: `Error: ${(e as Error).message}`, error: true });
    } finally {
      setBusy(false);
    }
  }

  const tiles: Tile[] = [];
  if (shown) {
    tiles.push(
      { label: `Run time: ${shown.label}`, value: `${fmt(shown.elapsedMs, 1)} ms`, note: `ideal ${fmt(shown.idealMs)} ms` },
      { label: "Efficiency", value: `${fmt((100 * shown.idealMs) / shown.elapsedMs)}%`, note: "ideal ÷ actual (100% = perfect)" },
      { label: "Tasks", value: fmt(shown.tasks), tone: shown.clean ? "good" : "bad",
        note: shown.clean ? "✓ every task ran exactly once" : `✗ ${shown.duplicates} duplicated, ${shown.failed} failed` },
      { label: "Successful steals", value: shown.stealAttempts ? fmt(shown.stealSuccesses) : "none",
        note: shown.stealAttempts ? `of ${fmt(shown.stealAttempts)} attempted` : "this strategy does not steal" },
    );
    if (shown.strategy === "adaptive") {
      tiles.push({ label: "Mode switches", value: fmt(shown.switches.length), note: "decided by the controller" });
      const fixed = runs.filter((r) => FIXED.includes(r.strategy));
      if (fixed.length === 3) {
        const best = fixed.reduce((a, b) => (a.elapsedMs < b.elapsedMs ? a : b));
        const g = (100 * (best.elapsedMs - shown.elapsedMs)) / best.elapsedMs;
        tiles.push({ label: "vs best fixed", value: signed(g), note: `best fixed: ${best.label}`, tone: g >= 0 ? "good" : "bad" });
      }
    }
  }

  return (
    <>
      <div className="controls">
        <label className="field">Workload
          <select value={workload} onChange={(e) => { setWorkload(e.target.value as WorkloadName); setSelected(null); }} disabled={busy}>
            <option value="shifting">Shifting (changes mid-run)</option>
            <option value="uniform">Uniform</option>
            <option value="fibonacci">Fibonacci tree</option>
          </select>
        </label>
        <label className="field">Workers
          <select value={workers} onChange={(e) => { setWorkers(Number(e.target.value)); setSelected(null); }} disabled={busy}>
            {[2, 4, 6, 8].map((n) => <option key={n} value={n}>{n}</option>)}
          </select>
        </label>
        <label className="field">Strategy
          <select value={strategy} onChange={(e) => setStrategy(e.target.value as Strategy)} disabled={busy}>
            {ORDER.map((s) => <option key={s} value={s}>{options.strategies[s]}</option>)}
          </select>
        </label>
        <button className="btn secondary" onClick={onRun} disabled={busy}>Run this strategy</button>
        <button className="btn primary" onClick={onCompare} disabled={busy}>Compare all strategies</button>
        <label className="check"><input type="checkbox" checked={withHalf} onChange={(e) => setWithHalf(e.target.checked)} disabled={busy} />
          include steal-half (extra)</label>
      </div>
      <p className="desc">{options.workloads[workload]?.[String(workers)]}</p>
      <p className={`status ${status.error ? "error" : ""} ${busy ? "busy" : ""}`}>{status.text}</p>

      {shown && <StatTiles tiles={tiles} />}

      <section className="card">
        <h2>Run time by strategy</h2>
        <p className="hint">Lower is better. Dashed line = ideal (total work ÷ workers). Click a bar to inspect that run below.</p>
        {runs.length
          ? <RunTimeBars runs={runs} selected={shown?.strategy ?? null} onSelect={setSelected} theme={theme} />
          : <p className="empty">No runs yet: press “Compare all strategies”.</p>}
      </section>

      <section className="card">
        <h2>Progress over time</h2>
        <p className="hint">Share of tasks finished. Steeper is faster; dots mark the adaptive controller's mode switches.</p>
        {runs.length > 0 && (
          <div className="legend">
            {runs.map((r) => (
              <span key={r.strategy} style={{ "--c": theme.strategy[r.strategy] } as CSSProperties}>
                {r.label} ({fmt(r.elapsedMs)} ms)
              </span>
            ))}
          </div>
        )}
        {runs.length ? <ProgressChart runs={runs} theme={theme} /> : <p className="empty">Run something to see progress over time.</p>}
      </section>

      <div className="grid2">
        <section className="card">
          <h2>Tasks per worker {shown && <span className="pill">{shown.label}</span>}</h2>
          <p className="hint">How evenly the work was spread. Dashed line = a perfectly even share.</p>
          {shown ? <WorkerBars run={shown} theme={theme} /> : <p className="empty">—</p>}
        </section>
        <section className="card">
          <h2>Adaptive controller</h2>
          <p className="hint">Band = which strategy was active. Line = utilization (share of workers busy), the signal it judges each trial by.</p>
          {adaptive && (
            <div className="legend box">
              {FIXED.map((s) => <span key={s} style={{ "--c": theme.strategy[s] } as CSSProperties}>{LABEL[s]}</span>)}
            </div>
          )}
          {adaptive ? <ControllerChart run={adaptive} theme={theme} /> : <p className="empty">Run the Adaptive strategy to see its decisions.</p>}
        </section>
      </div>

      {adaptive && adaptive.switches.length > 0 && (
        <section className="card">
          <h2>Controller decisions</h2>
          <p className="hint">Every switch and why. A trial is kept only if utilization did not drop; otherwise it reverts and waits longer before trying again.</p>
          <div className="table-wrap">
            <table>
              <thead><tr><th className="l">Time</th><th className="l">From</th><th className="l">To</th><th className="l">Reason</th></tr></thead>
              <tbody>
                {adaptive.switches.map((s, i) => (
                  <tr key={i}>
                    <td className="l">{fmt(s.timeMs)} ms</td>
                    <td className="l">{LABEL[MODE_OF[s.from]]}</td>
                    <td className="l">{LABEL[MODE_OF[s.to]]}</td>
                    <td className="l">{s.reason}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
    </>
  );
}
