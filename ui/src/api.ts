// Types and calls for the Java backend (sched-cli Dashboard).

export type Strategy = "static" | "wait" | "nowait" | "adaptive" | "nowait-half";
export type WorkloadName = "uniform" | "fibonacci" | "shifting";
export type ModeEnum = "STATIC_ROUND_ROBIN" | "WAIT_BASED_STEAL" | "NO_WAIT_STEAL";

export interface Options {
  strategies: Record<Strategy, string>;
  workloads: Record<WorkloadName, Record<string, string>>;
  cores: number;
}

export interface Switch { timeMs: number; from: ModeEnum; to: ModeEnum; reason: string }
export interface Sample { timeMs: number; mode: ModeEnum; utilization: number; imbalance: number }

export interface RunResult {
  workload: WorkloadName;
  workloadDescription: string;
  workers: number;
  strategy: Strategy;
  label: string;
  elapsedMs: number;
  idealMs: number;
  tasks: number;
  expectedTasks: number;
  clean: boolean;
  duplicates: number;
  failed: number;
  perWorker: number[];
  inline: number;
  stealAttempts: number;
  stealSuccesses: number;
  progress: [number, number][];
  switches: Switch[];
  samples: Sample[];
  loadAverage: number;
}

export interface DeadlockCycle { philosophers: number[]; resources: string[]; victim: number }
export interface DeadlockResult {
  philosophers: number;
  meals: number;
  elapsedMs: number;
  completed: boolean;
  deadlocksDetected: number;
  mealsEaten: number;
  mealsExpected: number;
  cycles: DeadlockCycle[];
}

export interface SummaryRow {
  config: string; kind: "main" | "var"; workload: string; workers: string; mode: string;
  mult: string; shift: string; median_ms: string; q1_ms: string; q3_ms: string; ideal_ms: string;
  repeats: string; clean: string;
}
export interface Results { available: boolean; rows: SummaryRow[] }

async function call<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, body === undefined ? {} : {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  const data = await res.json().catch(() => ({ error: res.statusText }));
  if (!res.ok) throw new Error((data as { error?: string }).error ?? res.statusText);
  return data as T;
}

export const api = {
  options: () => call<Options>("/api/options"),
  run: (workload: WorkloadName, workers: number, strategy: Strategy) =>
    call<RunResult>("/api/run", { workload, workers, strategy }),
  deadlock: (philosophers: number, meals: number, holdMicros: number) =>
    call<DeadlockResult>("/api/deadlock", { philosophers, meals, holdMicros }),
  results: () => call<Results>("/api/results"),
};
