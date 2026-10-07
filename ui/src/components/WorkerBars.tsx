import { Bar, BarChart, CartesianGrid, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import type { RunResult } from "../api";
import { fmt, type Theme } from "../theme";
import { TipBox } from "./ChartTooltip";

/** Tasks each worker executed, with a dashed line at a perfectly even share. */
export function WorkerBars({ run, theme: t }: { run: RunResult; theme: Theme }) {
  const mean = run.perWorker.reduce((a, b) => a + b, 0) / run.perWorker.length;
  const data = run.perWorker.map((v, i) => ({ worker: `w${i}`, tasks: v }));
  return (
    <ResponsiveContainer width="100%" height={230}>
      <BarChart data={data} margin={{ top: 10, right: 8, bottom: 0, left: 4 }}>
        <CartesianGrid vertical={false} stroke={t.grid} />
        <XAxis dataKey="worker" tick={{ fill: t.ink2, fontSize: 11 }} axisLine={{ stroke: t.axis }} tickLine={false} />
        <YAxis tickFormatter={(v: number) => fmt(v)} tick={{ fill: t.ink2, fontSize: 11 }} axisLine={false} tickLine={false} width={52} />
        <ReferenceLine y={mean} stroke={t.muted} strokeDasharray="4 3" />
        <Tooltip cursor={{ fill: t.grid, opacity: 0.4 }}
                 content={({ active, payload }) => {
                   if (!active || !payload?.length) return null;
                   const d = payload[0].payload as { worker: string; tasks: number };
                   return <TipBox title={`worker ${d.worker.slice(1)}`} rows={[
                     { value: fmt(d.tasks), label: "tasks run", color: t.strategy[run.strategy] },
                     { value: `${d.tasks >= mean ? "+" : ""}${fmt((100 * d.tasks) / mean - 100)}%`, label: "vs an even share" },
                   ]} />;
                 }} />
        <Bar dataKey="tasks" fill={t.strategy[run.strategy]} radius={[4, 4, 0, 0]} maxBarSize={36} isAnimationActive={false} />
      </BarChart>
    </ResponsiveContainer>
  );
}
