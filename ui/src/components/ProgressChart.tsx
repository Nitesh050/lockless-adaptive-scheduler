import { CartesianGrid, Line, LineChart, ReferenceDot, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { RunResult } from "../api";
import { fmt, type Theme } from "../theme";
import { TipBox } from "./ChartTooltip";

/** Linear interpolation of a [time, percent] series at time t (100% after the run ended). */
export function interp(points: [number, number][], t: number): number {
  if (t <= points[0][0]) return points[0][1];
  const last = points[points.length - 1];
  if (t >= last[0]) return last[1];
  let lo = 0, hi = points.length - 1;
  while (hi - lo > 1) {
    const m = (lo + hi) >> 1;
    if (points[m][0] <= t) lo = m; else hi = m;
  }
  const [x0, y0] = points[lo], [x1, y1] = points[hi];
  return y0 + ((y1 - y0) * (t - x0)) / (x1 - x0 || 1);
}

/** Share of tasks finished over time, one line per strategy, resampled onto a common time axis. */
export function ProgressChart({ runs, theme: t }: { runs: RunResult[]; theme: Theme }) {
  const tMax = Math.max(...runs.map((r) => r.progress[r.progress.length - 1][0]));
  const N = 240;
  const data = Array.from({ length: N + 1 }, (_, i) => {
    const time = (tMax * i) / N;
    const row: Record<string, number> = { time };
    for (const r of runs) row[r.strategy] = interp(r.progress, time);
    return row;
  });
  const adaptive = runs.find((r) => r.strategy === "adaptive");
  return (
    <ResponsiveContainer width="100%" height={320}>
      <LineChart data={data} margin={{ top: 10, right: 24, bottom: 18, left: 4 }}>
        <CartesianGrid vertical={false} stroke={t.grid} />
        <XAxis type="number" dataKey="time" domain={[0, tMax]} tickFormatter={(v: number) => `${fmt(v)} ms`}
               tick={{ fill: t.ink2, fontSize: 11 }} axisLine={{ stroke: t.axis }} tickLine={false}
               label={{ value: "time since start", position: "insideBottom", offset: -12, fill: t.ink2, fontSize: 11 }} />
        <YAxis domain={[0, 100]} ticks={[0, 25, 50, 75, 100]} tickFormatter={(v: number) => `${v}%`}
               tick={{ fill: t.ink2, fontSize: 11 }} axisLine={false} tickLine={false} width={44} />
        {runs[0].workload === "shifting" && (
          <ReferenceLine y={50} stroke={t.muted} strokeDasharray="4 3"
                         label={{ value: "workload shifts here", position: "insideBottomRight", fill: t.muted, fontSize: 11 }} />
        )}
        <Tooltip cursor={{ stroke: t.axis }}
                 content={({ active, payload, label }) => {
                   if (!active || !payload?.length) return null;
                   return <TipBox title={`at ${fmt(Number(label))} ms`} rows={runs.map((r) => ({
                     value: `${fmt(interp(r.progress, Number(label)))}%`, label: r.label, color: t.strategy[r.strategy],
                   }))} />;
                 }} />
        {runs.map((r) => (
          <Line key={r.strategy} dataKey={r.strategy} type="linear" dot={false} isAnimationActive={false}
                stroke={t.strategy[r.strategy]} strokeWidth={r.strategy === "adaptive" ? 2.75 : 2} />
        ))}
        {adaptive?.switches.map((s, i) => (
          <ReferenceDot key={i} x={s.timeMs} y={interp(adaptive.progress, s.timeMs)} r={5}
                        fill={t.strategy.adaptive} stroke={t.surface} strokeWidth={2} />
        ))}
      </LineChart>
    </ResponsiveContainer>
  );
}
