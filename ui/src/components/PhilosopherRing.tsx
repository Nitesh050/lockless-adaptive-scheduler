import type { DeadlockResult } from "../api";
import type { Theme } from "../theme";

/**
 * The first detected deadlock drawn as a wait-for cycle: philosophers on a ring with their
 * forks between them. Solid = holds the fork, dashed = waiting for it, red = the aborted victim.
 */
export function PhilosopherRing({ result, theme: t }: { result: DeadlockResult; theme: Theme }) {
  const n = result.philosophers;
  const cycle = result.cycles[0];
  const W = 380, H = 330, cx = W / 2, cy = H / 2 - 8, R = 118;
  const angle = (k: number) => -Math.PI / 2 + (2 * Math.PI * k) / n;
  const phil = (k: number) => [cx + R * Math.cos(angle(k)), cy + R * Math.sin(angle(k))] as const;
  // fork i is philosopher i's left fork and philosopher (i-1)'s right fork: it sits between them
  const fork = (i: number) => {
    const a = angle(i - 0.5);
    return [cx + R * 0.6 * Math.cos(a), cy + R * 0.6 * Math.sin(a)] as const;
  };
  const forkIndex = (name: string) => Number(name.split("-")[1]);

  return (
    <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Detected deadlock cycle">
      {cycle?.philosophers.map((p, j) => {
        const [fx, fy] = fork(forkIndex(cycle.resources[j]));
        const [px, py] = phil(p);
        const holder = cycle.philosophers[(j + 1) % cycle.philosophers.length];
        const [hx, hy] = phil(holder);
        return (
          <g key={j}>
            <line x1={hx} y1={hy} x2={fx} y2={fy} stroke={t.ink2} strokeWidth={2.5} />
            <line x1={px} y1={py} x2={fx} y2={fy} stroke={t.critical} strokeWidth={2} strokeDasharray="5 4" />
          </g>
        );
      })}
      {Array.from({ length: n }, (_, i) => {
        const [fx, fy] = fork(i);
        return (
          <g key={`f${i}`}>
            <rect x={fx - 20} y={fy - 10} width={40} height={20} rx={4} fill={t.surface} stroke={t.axis} />
            <text x={fx} y={fy} textAnchor="middle" dominantBaseline="middle" fontSize={10} fill={t.ink2}>fork-{i}</text>
          </g>
        );
      })}
      {Array.from({ length: n }, (_, k) => {
        const [px, py] = phil(k);
        const victim = cycle?.victim === k;
        return (
          <g key={`p${k}`}>
            <circle cx={px} cy={py} r={22} fill={victim ? t.critical : t.accent} stroke={t.surface} strokeWidth={2} />
            <text x={px} y={py} textAnchor="middle" dominantBaseline="middle" fontSize={12} fontWeight={700} fill="#fff">P{k}</text>
          </g>
        );
      })}
      <text x={cx} y={H - 6} textAnchor="middle" fontSize={11} fill={t.ink2}>
        {cycle ? `P${cycle.victim} was aborted (red): it puts its fork down and retries` : "No deadlock formed this run: try a longer hold time"}
      </text>
    </svg>
  );
}
