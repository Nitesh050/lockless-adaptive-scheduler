import { useEffect, useState } from "react";
import { api, type Results, type SummaryRow } from "../api";
import { fmt, mix, signed, type Theme } from "../theme";

const WL: Record<string, string> = { uniform: "Uniform", fibonacci: "Fibonacci tree", shifting: "Shifting" };

/** The Phase 4 results (710 runs) from docs/report/figures/summary.csv. */
export function ResultsView({ theme: t }: { theme: Theme }) {
  const [data, setData] = useState<Results | null>(null);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { api.results().then(setData).catch((e: Error) => setError(e.message)); }, []);

  if (error) return <p className="status error">Error: {error}</p>;
  if (!data) return <p className="status">Loading…</p>;
  if (!data.available) {
    return <p className="empty">No saved results found. Start the dashboard from the project folder, after
      <code> scripts/plot_results.py</code> has written docs/report/figures/summary.csv.</p>;
  }

  const main: Record<string, Record<string, SummaryRow>> = {};
  const vars: Record<string, Record<string, number>> = {};
  for (const r of data.rows) {
    if (r.kind === "main") (main[`${r.workload}|${r.workers}`] ??= {})[r.mode] = r;
    if (r.kind === "var") (vars[`${r.mult}|${Number(r.shift)}`] ??= {})[r.mode] = Number(r.median_ms);
  }
  const s8 = main["shifting|8"] ?? {};
  vars["10|0.5"] = Object.fromEntries(["static", "wait", "nowait", "adaptive"].map((m) => [m, Number(s8[m]?.median_ms)]));

  return (
    <>
      <p className="desc">Phase 4 experiments: every configuration ran 2 warm-up runs and 10 recorded runs. Values are
        median run times in ms; lower is better. <b>Bold</b> = best fixed strategy. All 710 runs were checked for lost,
        duplicated or failed tasks.</p>
      <section className="card">
        <h2>Run time, all configurations</h2>
        <div className="table-wrap">
          <table>
            <thead><tr>
              <th className="l">Workload</th><th>Workers</th><th>Ideal</th><th>Round-robin</th><th>Wait steal-1</th>
              <th>No-wait steal-1</th><th>Adaptive</th><th>Steal-half*</th><th>Adaptive vs best fixed</th>
            </tr></thead>
            <tbody>
              {["uniform", "fibonacci", "shifting"].flatMap((wl) => ["2", "4", "8"].map((n) => {
                const g = main[`${wl}|${n}`];
                if (!g?.adaptive) return null;
                const fixed = ["static", "wait", "nowait"].filter((m) => g[m]);
                const best = fixed.reduce((a, b) => (Number(g[a].median_ms) < Number(g[b].median_ms) ? a : b));
                const gain = (100 * (Number(g[best].median_ms) - Number(g.adaptive.median_ms))) / Number(g[best].median_ms);
                return (
                  <tr key={`${wl}${n}`}>
                    <td className="l">{WL[wl]}</td><td>{n}</td><td>{fmt(Number(g.adaptive.ideal_ms))}</td>
                    {["static", "wait", "nowait", "adaptive"].map((m) => (
                      <td key={m} className={m === best ? "best" : ""}>{g[m] ? fmt(Number(g[m].median_ms)) : "—"}</td>
                    ))}
                    <td>{g["nowait-half"] ? fmt(Number(g["nowait-half"].median_ms)) : "—"}</td>
                    <td className={gain > 1 ? "pos" : ""}>{signed(gain)}</td>
                  </tr>
                );
              }))}
            </tbody>
          </table>
        </div>
        <p className="hint">* Steal-half moves half the victim's queue per steal. It is not one of the paper's strategies;
          it is reported as a separate finding (8 workers only).</p>
      </section>
      <section className="card">
        <h2>Shifting variants (8 workers): adaptive vs best fixed strategy</h2>
        <p className="hint">+ means adaptive is faster. Rows: how much heavier the heavy tasks are. Columns: where in the run the workload shifts.</p>
        <div className="heat">
          <div />
          {[0.3, 0.5, 0.7].map((s) => <div key={s} className="heat-h">shift at {s * 100}%</div>)}
          {[20, 10, 5].flatMap((m) => [
            <div key={`h${m}`} className="heat-h left">{m}× heavier</div>,
            ...[0.3, 0.5, 0.7].map((s) => {
              const v = vars[`${m}|${s}`];
              if (!v?.adaptive) return <div key={`${m}${s}`} className="heat-cell">—</div>;
              const best = Math.min(v.static, v.wait, v.nowait);
              const g = (100 * (best - v.adaptive)) / best;
              const f = Math.min(1, Math.abs(g) / 25);
              return (
                <div key={`${m}${s}`} className="heat-cell"
                     style={{ background: mix(t.divMid, g >= 0 ? t.divPos : t.divNeg, f), color: f > 0.55 ? "#fff" : t.ink }}
                     title={`adaptive ${fmt(v.adaptive)} ms vs best fixed ${fmt(best)} ms`}>
                  {signed(g)}
                </div>
              );
            }),
          ])}
        </div>
      </section>
    </>
  );
}
