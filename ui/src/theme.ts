import { useEffect, useState } from "react";
import type { ModeEnum, Strategy } from "./api";

// Validated categorical palette (light / dark steps of the same hues). Colour follows the
// strategy, never its rank, so a strategy keeps its colour on every chart.
const LIGHT = {
  surface: "#fcfcfb", ink: "#0b0b0b", ink2: "#52514e", muted: "#898781", grid: "#e1e0d9", axis: "#c3c2b7",
  good: "#006300", critical: "#d03b3b", accent: "#2a78d6",
  strategy: { adaptive: "#2a78d6", static: "#eb6834", wait: "#1baf7a", nowait: "#eda100", "nowait-half": "#e87ba4" },
  divNeg: "#e34948", divMid: "#f0efec", divPos: "#2a78d6",
};
const DARK: typeof LIGHT = {
  surface: "#1a1a19", ink: "#ffffff", ink2: "#c3c2b7", muted: "#898781", grid: "#2c2c2a", axis: "#383835",
  good: "#0ca30c", critical: "#e66767", accent: "#3987e5",
  strategy: { adaptive: "#3987e5", static: "#d95926", wait: "#199e70", nowait: "#c98500", "nowait-half": "#d55181" },
  divNeg: "#e66767", divMid: "#383835", divPos: "#3987e5",
};
export type Theme = typeof LIGHT;

export const LABEL: Record<Strategy, string> = {
  static: "Round-robin",
  wait: "Wait-based steal-1",
  nowait: "No-wait steal-1",
  adaptive: "Adaptive",
  "nowait-half": "No-wait steal-half",
};
export const ORDER: Strategy[] = ["static", "wait", "nowait", "adaptive", "nowait-half"];
export const FIXED: Strategy[] = ["static", "wait", "nowait"];
export const MODE_OF: Record<ModeEnum, Strategy> = {
  STATIC_ROUND_ROBIN: "static",
  WAIT_BASED_STEAL: "wait",
  NO_WAIT_STEAL: "nowait",
};

const DARK_QUERY = window.matchMedia("(prefers-color-scheme: dark)");

/** The palette for the current OS colour scheme; re-renders when the user switches it. */
export function useTheme(): Theme {
  const [dark, setDark] = useState(DARK_QUERY.matches);
  useEffect(() => {
    const on = (e: MediaQueryListEvent) => setDark(e.matches);
    DARK_QUERY.addEventListener("change", on);
    return () => DARK_QUERY.removeEventListener("change", on);
  }, []);
  return dark ? DARK : LIGHT;
}

export const fmt = (v: number, digits = 0) =>
  v.toLocaleString(undefined, { maximumFractionDigits: digits, minimumFractionDigits: digits });

export const signed = (v: number, digits = 1) => `${v >= 0 ? "+" : ""}${fmt(v, digits)}%`;

/** Mixes two #rrggbb colours; f = 0 gives a, f = 1 gives b. */
export function mix(a: string, b: string, f: number): string {
  const p = (h: string) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
  const A = p(a), B = p(b);
  return `rgb(${A.map((v, i) => Math.round(v + (B[i] - v) * f)).join(",")})`;
}
