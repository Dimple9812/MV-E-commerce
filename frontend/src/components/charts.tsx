import { useEffect, useState } from 'react';
import { Bar, BarChart, CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { money } from '@/lib/format';

/** Recharts draws SVG attributes, which cannot read CSS variables; resolve the theme tokens to concrete colours. */
function useTokens() {
  const read = () => {
    const css = getComputedStyle(document.documentElement);
    const c = (n: string) => `rgb(${css.getPropertyValue(`--${n}`).trim().split(/\s+/).join(',')})`;
    return { ink: c('ink'), line: c('line'), slate: c('slate'), tag: c('tag-deep'), leaf: c('leaf'), surface: c('surface') };
  };
  const [t, setT] = useState(read);
  useEffect(() => {
    const mq = window.matchMedia?.('(prefers-color-scheme: dark)');
    const on = () => setT(read());
    mq?.addEventListener?.('change', on);
    return () => mq?.removeEventListener?.('change', on);
  }, []);
  return t;
}

const shortDay = (d: string) => new Date(`${d}T00:00:00`).toLocaleDateString('en-US', { month: 'short', day: 'numeric' });

export function TrendChart<T extends { day: string }>({ data, valueKey, label, asMoney = false, height = 240 }: {
  data: T[]; valueKey: keyof T & string; label: string; asMoney?: boolean; height?: number;
}) {
  const t = useTokens();
  const fmt = (v: number) => (asMoney ? money(v) : String(v));
  return (
    <div style={{ height }} role="img" aria-label={`${label} per day`}>
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={data.map((d) => ({ ...d, [valueKey]: Number(d[valueKey]) }))} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
          <CartesianGrid stroke={t.line} vertical={false} />
          <XAxis dataKey="day" tickFormatter={shortDay} stroke={t.slate} fontSize={12} tickLine={false} axisLine={false} minTickGap={24} />
          <YAxis stroke={t.slate} fontSize={12} tickLine={false} axisLine={false} width={asMoney ? 64 : 32}
            tickFormatter={(v: number) => (asMoney ? `$${v >= 1000 ? `${Math.round(v / 100) / 10}k` : v}` : String(v))} allowDecimals={false} />
          <Tooltip labelFormatter={(d) => shortDay(String(d))} formatter={(v) => [fmt(Number(v)), label]}
            contentStyle={{ background: t.surface, border: `1px solid ${t.line}`, borderRadius: 8, color: t.ink }} />
          <Line type="monotone" dataKey={valueKey} stroke={t.ink} strokeWidth={2} dot={false} activeDot={{ r: 4, fill: t.tag }} />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}

export function BarList<T>({ data, nameKey, valueKey, label, asMoney = false, height = 240 }: {
  data: T[]; nameKey: keyof T & string; valueKey: keyof T & string; label: string; asMoney?: boolean; height?: number;
}) {
  const t = useTokens();
  return (
    <div style={{ height }} role="img" aria-label={label}>
      <ResponsiveContainer width="100%" height="100%">
        <BarChart layout="vertical" data={data.map((d) => ({ name: String(d[nameKey]), value: Number(d[valueKey]) }))}
          margin={{ top: 4, right: 12, bottom: 0, left: 0 }}>
          <XAxis type="number" hide />
          <YAxis type="category" dataKey="name" width={140} stroke={t.slate} fontSize={12} tickLine={false} axisLine={false} />
          <Tooltip formatter={(v) => [asMoney ? money(Number(v)) : String(v), label]} cursor={{ fill: t.line, opacity: 0.4 }}
            contentStyle={{ background: t.surface, border: `1px solid ${t.line}`, borderRadius: 8, color: t.ink }} />
          <Bar dataKey="value" fill={t.tag} radius={[0, 4, 4, 0]} barSize={18} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  );
}
