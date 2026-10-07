// One tooltip style for every chart: the value leads, the label follows, a short stroke keys the series.
export interface TipRow { value: string; label: string; color?: string }

export function TipBox({ title, rows }: { title: string; rows: TipRow[] }) {
  return (
    <div className="tip">
      <div className="tip-title">{title}</div>
      {rows.map((r, i) => (
        <div className="tip-row" key={i}>
          <i style={{ background: r.color ?? "transparent" }} />
          <b>{r.value}</b>
          <span>{r.label}</span>
        </div>
      ))}
    </div>
  );
}
