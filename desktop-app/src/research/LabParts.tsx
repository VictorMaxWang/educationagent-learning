import type { ReactNode } from 'react';

export function LabQuestion({ number, title, children }: { number: string; title: string; children: ReactNode }) {
  return <div className="tr-question"><span className="tr-number">{number}</span><div><h2>{title}</h2><p>{children}</p></div></div>;
}

export function LabSource({ children }: { children: ReactNode }) {
  return <details className="tr-source"><summary>公式、约定与团队成果来源</summary><div>{children}</div></details>;
}

export function RangeControl({ label, value, min, max, step = 1, display, onChange }: {
  label: string; value: number; min: number; max: number; step?: number; display: string; onChange(value: number): void;
}) {
  return <label className="tr-control"><span>{label}<output>{display}</output></span>
    <input type="range" min={min} max={max} step={step} value={value} onChange={event => onChange(Number(event.currentTarget.value))} />
  </label>;
}
