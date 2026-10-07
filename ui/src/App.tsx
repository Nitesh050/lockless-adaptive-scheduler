import { useEffect, useState } from "react";
import { api, type Options } from "./api";
import { DeadlockView } from "./components/DeadlockView";
import { ResultsView } from "./components/ResultsView";
import { RunView } from "./components/RunView";
import { useTheme } from "./theme";

type Tab = "run" | "deadlock" | "results";
const TABS: { id: Tab; label: string }[] = [
  { id: "run", label: "Run & compare" },
  { id: "deadlock", label: "Deadlock demo" },
  { id: "results", label: "Saved results (710 runs)" },
];

export default function App() {
  const theme = useTheme();
  const [tab, setTab] = useState<Tab>("run");
  const [options, setOptions] = useState<Options | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => { api.options().then(setOptions).catch((e: Error) => setError(e.message)); }, []);

  return (
    <>
      <header>
        <h1>Adaptive Lock-Free Scheduler</h1>
        <p className="sub">Runs the real scheduler on this machine{options ? ` (${options.cores} CPU cores)` : ""}.
          Pick a workload, the number of workers and a strategy, then run it.</p>
      </header>
      <main>
        <nav role="tablist">
          {TABS.map((t) => (
            <button key={t.id} role="tab" aria-selected={tab === t.id} onClick={() => setTab(t.id)}>{t.label}</button>
          ))}
        </nav>
        {error && <p className="status error">Cannot reach the scheduler backend: {error}. Start it with
          <code> java -jar sched-cli/target/scheduler.jar --ui</code>.</p>}
        {/* keep each tab mounted so its runs survive switching tabs */}
        <div hidden={tab !== "run"}>{options && <RunView options={options} theme={theme} />}</div>
        <div hidden={tab !== "deadlock"}><DeadlockView theme={theme} /></div>
        {tab === "results" && <ResultsView theme={theme} />}
      </main>
    </>
  );
}
