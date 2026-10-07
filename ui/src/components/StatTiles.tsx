export interface Tile { label: string; value: string; note?: string; tone?: "good" | "bad" }

export function StatTiles({ tiles }: { tiles: Tile[] }) {
  return (
    <div className="tiles">
      {tiles.map((t) => (
        <div className="tile" key={t.label}>
          <div className="tile-label">{t.label}</div>
          <div className={`tile-value ${t.tone ?? ""}`}>{t.value}</div>
          {t.note && <div className="tile-note">{t.note}</div>}
        </div>
      ))}
    </div>
  );
}
