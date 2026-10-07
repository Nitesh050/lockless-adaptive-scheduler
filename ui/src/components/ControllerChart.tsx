import { CartesianGrid, ComposedChart, Line, ReferenceArea, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { RunResult } from "../api";
import { fmt, LABEL, MODE_OF, type Theme } from "../theme";
import { TipBox } from "./ChartTooltip";

/**
 * The adaptive controller over time: a band showing which strategy was active (top), and
 * worker utilization, the signal it judges each trial by (line).
 */
export function ControllerChart({ run, theme: t }: { run: RunResult; theme: Theme }) {
  const samples = run.samples;
  // merge consecutive samples in the same mode into segments for the band
  const segments: { from: number; to: number; mode: keyof typeof MODE_OF }[] = [];
  samples.forEach((s, i) => {
    const from = i === 0 ? 0 : samples[i - 1].timeMs;
    const last = segments[segments.length - 1];
    if (last && last.mode === s.mode) last.to = s.timeMs;
    else segments.push({ from, to: s.timeMs, mode: s.mode });
  });
  const data = samples.map((s) => ({ time: s.timeMs, utilization: s.utilization, imbalance: s.imbalance, mode: s.mode }));
  const end = Math.max(run.elapsedMs, samples.length ? samples[samples.length - 1].timeMs : 0);
  return (
    <ResponsiveContainer width="100%" height={230}>
      <ComposedChart data={data} margin={{ top: 4, right: 8, bottom: 0, left: 4 }}>
        <CartesianGrid vertical={false} stroke={t.grid} />
        <XAxis type="number" dataKey="time" domain={[0, end]} tickFormatter={(v: number) => `${fmt(v)} ms`}
               tick={{ fill: t.ink2, fontSize: 11 }} axisLine={{ stroke: t.axis }} tickLine={false} />
        <YAxis domain={[0, 1.25]} ticks={[0, 0.5, 1]} tickFormatter={(v: number) => `${v * 100}%`}
               tick={{ fill: t.ink2, fontSize: 11 }} axisLine={false} tickLine={false} width={44} />
        {segments.map((s, i) => (
          <ReferenceArea key={i} x1={s.from} x2={s.to} y1={1.08} y2={1.25} fill={t.strategy[MODE_OF[s.mode]]}
                         fillOpacity={1} stroke={t.surface} strokeWidth={1} ifOverflow="hidden" />
        ))}
        <Tooltip cursor={{ stroke: t.axis }}
                 content={({ active, payload }) => {
                   if (!active || !payload?.length) return null;
                   const d = payload[0].payload as (typeof data)[number];
                   const m = MODE_OF[d.mode];
                   return <TipBox title={`at ${fmt(d.time)} ms`} rows={[
                     { value: LABEL[m], label: "active strategy", color: t.strategy[m] },
                     { value: `${fmt(100 * d.utilization)}%`, label: "workers busy" },
                     { value: fmt(d.imbalance, 1), label: "queue imbalance (max ÷ mean)" },
                   ]} />;
                 }} />
        <Line dataKey="utilization" type="stepAfter" dot={false} stroke={t.ink2} strokeWidth={1.75} isAnimationActive={false} />
      </ComposedChart>
    </ResponsiveContainer>
  );
}
