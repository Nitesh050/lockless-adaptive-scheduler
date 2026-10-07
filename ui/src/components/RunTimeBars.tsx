import { Bar, BarChart, CartesianGrid, Cell, LabelList, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { RunResult, Strategy } from "../api";
import { fmt, type Theme } from "../theme";
import { TipBox } from "./ChartTooltip";

interface Props { runs: RunResult[]; selected: Strategy | null; onSelect: (s: Strategy) => void; theme: Theme }

/** Run time per strategy: horizontal bars, ideal as a dashed reference, click to inspect. */
export function RunTimeBars({ runs, selected, onSelect, theme: t }: Props) {
  const ideal = runs[0].idealMs;
  const max = Math.max(ideal, ...runs.map((r) => r.elapsedMs)) * 1.05;
  const data = runs.map((r) => ({ ...r, ratio: r.elapsedMs / ideal }));
  return (
    <ResponsiveContainer width="100%" height={46 * runs.length + 50}>
      <BarChart layout="vertical" data={data} margin={{ top: 22, right: 150, bottom: 4, left: 8 }}>
        <CartesianGrid horizontal={false} stroke={t.grid} />
        <XAxis type="number" domain={[0, max]} tickFormatter={(v: number) => `${fmt(v)} ms`}
               tick={{ fill: t.ink2, fontSize: 11 }} axisLine={{ stroke: t.axis }} tickLine={false} />
        <YAxis type="category" dataKey="label" width={150} tick={{ fill: t.ink, fontSize: 12 }}
               axisLine={false} tickLine={false} />
        <ReferenceLine x={ideal} stroke={t.muted} strokeDasharray="4 3"
                       label={{ value: `ideal ${fmt(ideal)} ms`, position: "top", fill: t.muted, fontSize: 11 }} />
        <Tooltip cursor={{ fill: t.grid, opacity: 0.4 }}
                 content={({ active, payload }) => {
                   if (!active || !payload?.length) return null;
                   const r = payload[0].payload as RunResult & { ratio: number };
                   return <TipBox title={r.label} rows={[
                     { value: `${fmt(r.elapsedMs, 1)} ms`, label: "run time", color: t.strategy[r.strategy] },
                     { value: `${fmt(r.ratio, 2)}×`, label: "of ideal" },
                     { value: r.stealAttempts ? `${fmt(r.stealSuccesses)} / ${fmt(r.stealAttempts)}` : "—", label: "steals ok / tried" },
                     { value: r.clean ? "✓" : "✗", label: "every task exactly once" },
                   ]} />;
                 }} />
        <Bar dataKey="elapsedMs" barSize={20} radius={[0, 4, 4, 0]} isAnimationActive={false}
             onClick={(d) => onSelect((d as unknown as RunResult).strategy)} style={{ cursor: "pointer" }}>
          {data.map((r) => (
            <Cell key={r.strategy} fill={t.strategy[r.strategy]}
                  stroke={r.strategy === selected ? t.ink : "none"} strokeWidth={1.5} />
          ))}
          <LabelList dataKey="elapsedMs" position="right" fill={t.ink} fontSize={12}
                     formatter={(v: unknown) => `${fmt(Number(v))} ms  ·  ${fmt(Number(v) / ideal, 2)}× ideal`} />
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  );
}
