import { useEffect, useMemo, useState } from 'react'
// Recharts components for responsive area chart visualization
import { Area, ComposedChart, Bar, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis, LabelList } from 'recharts'

// Instrument as returned by backend API
type Instrument = {
  isin: string
  name: string
  currentPrice: number
  totalAnnualReturnRate: number // ACGR10 from backend
  acgr10?: number
  dividendYieldAnnual: number
  dividendPolicy: 'ACCUMULATING' | 'DISTRIBUTING'
}

// Basket position and DCA schedule types
type InstrumentPosition = { isin: string; quantity: number }
type DcaSchedule = { amountPerPeriod: number; periods: number; frequency: 'MONTHLY' | 'QUARTERLY' | 'YEARLY' }

// Request payload sent to the backend simulator
type SimulationRequest = {
  positions: InstrumentPosition[]
  initialCapital: number
  sideCapital: number
  years: number
  dca?: DcaSchedule
  feesAnnualBps: number
}

// Response types used to render charts
type InstrumentSeriesPoint = { monthIndex: number; value: number }
type InstrumentSeries = { isin: string; name: string; points: InstrumentSeriesPoint[] }
type TimePoint = { monthIndex: number; totalValue: number; contributed: number; dividendsPaid: number; monthlyDividendsGenerated: number }
type SimulationResponse = { portfolio: TimePoint[]; instruments: InstrumentSeries[] }

// Row for chart data (supports dynamic keys for per-instrument series and yearlyDivs)
type ChartRow = {
  month: number
  total: number
  contributed: number
  dividends: number
  monthlyDivs: number
  yearlyDivs?: number
  cashReal?: number
  [key: string]: number | undefined
}

// Base URL for backend API (same machine during local development)
const API_BASE = 'http://localhost:8080/api'

export default function App() {
  // Remote lists and local UI state
  const [instruments, setInstruments] = useState<Instrument[]>([])
  const [basket, setBasket] = useState<InstrumentPosition[]>([])
  // initialCapital is computed from basket; the local state is unused but preserved for clarity
  const [initialCapital, setInitialCapital] = useState(1000)
  const [years, setYears] = useState(5)
  const [feesBps, setFeesBps] = useState(0)
  const [sideCapital, setSideCapital] = useState(0)
  const [dca, setDca] = useState<DcaSchedule | undefined>({ amountPerPeriod: 100, periods: 1, frequency: 'MONTHLY' })
  const [result, setResult] = useState<SimulationResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [query, setQuery] = useState('')

  // Fetch instruments once on mount
  useEffect(() => {
    fetch(`${API_BASE}/instruments`).then(r => r.json()).then(setInstruments).catch(() => setInstruments([]))
  }, [])

  // Add an instrument to the basket if not already present
  const onAdd = (isin: string) => {
    setBasket(prev => {
      const found = prev.find(p => p.isin === isin)
      if (found) return prev
      return [...prev, { isin, quantity: 1 }]
    })
  }

  // Update quantity of a given basket position
  const onQuantity = (isin: string, q: number) => {
    setBasket(prev => prev.map(p => p.isin === isin ? { ...p, quantity: q } : p))
  }

  // Remove a position from the basket
  const onRemove = (isin: string) => {
    setBasket(prev => prev.filter(p => p.isin !== isin))
  }

  // Compute current market value of all positions: sum(quantity × currentPrice)
  const basketMarketValue = useMemo(() => {
    return basket.reduce((sum, p) => sum + (instruments.find(i => i.isin === p.isin)?.currentPrice ?? 0) * p.quantity, 0)
  }, [basket, instruments])

  // Total initial (for display): basket market value + side capital
  const totalInitial = useMemo(() => basketMarketValue + sideCapital, [basketMarketValue, sideCapital])

  // Guard against invalid runs
  const canSimulate = useMemo(() => basket.length > 0 && years > 0, [basket, years])

  // Prepare chart data and compute yearly dividend sums at month boundaries (12, 24, ...)
  const chartData = useMemo(() => {
    if (!result) return [] as ChartRow[]
    const base: ChartRow[] = result.portfolio.map(p => ({
      month: p.monthIndex,
      total: p.totalValue,
      contributed: p.contributed,
      dividends: p.dividendsPaid,
      monthlyDivs: p.monthlyDividendsGenerated,
      // also include each instrument series so stacking still works
      ...Object.fromEntries(result.instruments.map(s => [s.isin, s.points[p.monthIndex]?.value ?? 0]))
    })) as ChartRow[]
    // Compute real purchasing power of a pure-cash DCA (0% nominal) under French inflation
    // Approach: degrade previous real value by monthly inflation, then add this month's DCA delta
    const annualInflation = 0.04 // approx. current FR inflation; adjust as needed
    const monthlyInflFactor = Math.pow(1 + annualInflation, 1 / 12)
    let realCash = sideCapital
    let prevContrib = result.portfolio[0]?.contributed ?? 0
    for (let idx = 0; idx < base.length; idx++) {
      const p = result.portfolio[idx]
      const deltaContrib = Math.max(0, p.contributed - prevContrib) // only new DCA adds to cash
      if (idx === 0) {
        base[idx].cashReal = sideCapital
      } else {
        realCash = realCash / monthlyInflFactor + deltaContrib
        base[idx].cashReal = realCash
      }
      prevContrib = p.contributed
    }
    const lastMonth = result.portfolio[result.portfolio.length - 1]?.monthIndex ?? 0
    const yrs = Math.floor(lastMonth / 12)
    // sum monthlyDivs over each year and annotate the year-end row
    for (let y = 1; y <= yrs; y++) {
      const end = y * 12
      const start = (y - 1) * 12 + 1
      let sum = 0
      for (let m = start; m <= end; m++) {
        const row = base[m]
        if (row) sum += row.monthlyDivs || 0
      }
      if (base[end]) base[end].yearlyDivs = sum
    }
    return base
  }, [result, sideCapital])

  // Currency formatter for axes/tooltip labels (compact, modern)
  const fmt = useMemo(() => new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }), [])

  // Submit the simulation request to the backend and store the response
  const simulate = async () => {
    if (!canSimulate) return
    setLoading(true)
    try {
      const body: SimulationRequest = { positions: basket, initialCapital: basketMarketValue, sideCapital, years, dca, feesAnnualBps: feesBps }
      const res = await fetch(`${API_BASE}/simulations`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
      })
      const data: SimulationResponse = await res.json()
      setResult(data)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen p-6 bg-slate-950 text-slate-100">
      {/* Content grid: left pane scrollable, right pane fixed chart */}
      <div className="grid gap-6 grid-cols-1 xl:grid-cols-[500px_minmax(0,1fr)]">
        {/* LEFT PANE: scrollable */}
        <div className="xl:h-[calc(100vh-3rem)] overflow-y-auto custom-scroll space-y-6 pr-1">
          <section className="bg-slate-900/70 backdrop-blur rounded-xl shadow-sm ring-1 ring-slate-700/50 p-5">
            <h2 className="font-semibold text-lg mb-4 text-slate-50">Instruments</h2>
            {/* Modern search bar */}
            <div className="relative mb-4">
              <input
                value={query}
                onChange={e => setQuery(e.target.value)}
                placeholder="Search by name, ISIN or symbol…"
                className="w-full rounded-xl bg-slate-800/80 border border-slate-700 px-4 py-2.5 pr-10 text-sm text-slate-100 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent"
              />
              <svg className="absolute right-3 top-1/2 -translate-y-1/2 h-5 w-5 text-slate-400" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="11" cy="11" r="8"></circle><path d="m21 21-4.3-4.3"></path></svg>
            </div>
            <div className="max-h-80 overflow-auto custom-scroll divide-y divide-slate-800 pr-3">
              {instruments.filter(i => {
                const q = query.trim().toLowerCase()
                if (!q) return true
                return i.name.toLowerCase().includes(q) || i.isin.toLowerCase().includes(q)
              }).map(i => (
                <div key={i.isin} className="py-3">
                  <div className="flex items-start justify-between gap-3">
                    <div>
                      <div className="font-medium text-slate-100">{i.name}</div>
                      <div className="text-xs text-slate-400">{i.isin} • Price {i.currentPrice?.toFixed(2)} • ACGR {( (i.acgr10 ?? i.totalAnnualReturnRate) * 100).toFixed(1)}% • Div {(i.dividendYieldAnnual*100).toFixed(1)}% • {i.dividendPolicy}</div>
                    </div>
                  </div>
                  {/* Second line to give space for the button, away from scrollbar */}
                  <div className="mt-2 flex justify-end">
                    <button onClick={() => onAdd(i.isin)} className="px-3 py-1.5 text-sm rounded-lg bg-indigo-500 text-white hover:bg-indigo-400 transition">Add</button>
                  </div>
                </div>
              ))}
            </div>
          </section>

          <section className="bg-slate-900/70 backdrop-blur rounded-xl shadow-sm ring-1 ring-slate-700/50 p-5 space-y-4">
            <h2 className="font-semibold text-lg text-slate-50">Basket</h2>
            {basket.length === 0 && <div className="text-sm text-slate-400">No instruments added.</div>}
            {basket.map(p => {
              const ins = instruments.find(i => i.isin === p.isin)
              return (
                <div key={p.isin} className="flex items-center gap-3">
                  <div className="flex-1">
                    <div className="font-medium text-slate-100">{ins?.name ?? p.isin}</div>
                    <div className="text-xs text-slate-400">{p.isin}</div>
                    <div className="text-xs text-slate-300 mt-1">
                      Price {ins?.currentPrice?.toFixed(2)} • Qty {p.quantity} • Value {(((ins?.currentPrice ?? 0) * p.quantity)).toFixed(2)}
                    </div>
                  </div>
                  <input type="number" min={0} step={1} value={p.quantity} onChange={e => onQuantity(p.isin, Number(e.target.value))} className="w-28 border border-slate-700 bg-slate-900 text-slate-100 rounded-lg px-2 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500" />
                  <button onClick={() => onRemove(p.isin)} className="px-3 py-2 text-sm rounded-lg bg-slate-800 text-slate-200 hover:bg-slate-700 transition">Remove</button>
                </div>
              )
            })}
          </section>

          <section className="bg-slate-900/70 backdrop-blur rounded-xl shadow-sm ring-1 ring-slate-700/50 p-5 space-y-4">
            <h2 className="font-semibold text-lg text-slate-50">Parameters</h2>
            {/* Align labels above fields, consistent spacing */}
            <div className="grid grid-cols-2 gap-5 items-start">
              <div className="flex flex-col gap-1">
                <span className="text-sm text-slate-300">Initial capital (auto from basket)</span>
                <input type="number" value={basketMarketValue} readOnly className="w-full border border-slate-700 rounded-lg px-3 py-2 text-sm bg-slate-800 text-slate-100" />
              </div>
              <div className="flex flex-col gap-1">
                <span className="text-sm text-slate-300">Years</span>
                <input type="number" value={years} onChange={e => setYears(Number(e.target.value))} className="w-full border border-slate-700 rounded-lg px-3 py-2 text-sm bg-slate-900 text-slate-100 focus:outline-none focus:ring-2 focus:ring-indigo-500" />
              </div>
              <div className="flex flex-col gap-1">
                <span className="text-sm text-slate-300">Fees (bps/yr)</span>
                <input type="number" value={feesBps} onChange={e => setFeesBps(Number(e.target.value))} className="w-full border border-slate-700 rounded-lg px-3 py-2 text-sm bg-slate-900 text-slate-100 focus:outline-none focus:ring-2 focus:ring-indigo-500" />
              </div>
              <div className="flex flex-col gap-1">
                <span className="text-sm text-slate-300">Side capital (Cash)</span>
                <input type="number" value={sideCapital} onChange={e => setSideCapital(Number(e.target.value))} className="w-full border border-slate-700 rounded-lg px-3 py-2 text-sm bg-slate-900 text-slate-100 focus:outline-none focus:ring-2 focus:ring-indigo-500" />
              </div>
              <div className="flex flex-col gap-1 col-span-2">
                <span className="text-sm text-slate-300">Total initial (basket + side)</span>
                <input type="number" value={totalInitial} readOnly className="w-full border border-slate-700 rounded-lg px-3 py-2 text-sm bg-slate-800 text-slate-100" />
              </div>
            </div>
            <div className="grid grid-cols-3 gap-5 items-end">
              <div className="flex flex-col gap-1">
                <span className="text-sm text-slate-300">DCA amount</span>
                <input type="number" value={dca?.amountPerPeriod ?? 0} onChange={e => setDca(prev => ({ amountPerPeriod: Number(e.target.value), periods: prev?.periods ?? 1, frequency: prev?.frequency ?? 'MONTHLY' }))} className="w-full border border-slate-700 rounded-lg px-3 py-2 text-sm bg-slate-900 text-slate-100 focus:outline-none focus:ring-2 focus:ring-indigo-500" />
              </div>
              <div className="flex flex-col gap-1">
                <span className="text-sm text-slate-300">Investments per period</span>
                <input title="How many times per frequency (e.g., per month) to invest" type="number" min={1} value={dca?.periods ?? 1} onChange={e => setDca(prev => ({ amountPerPeriod: prev?.amountPerPeriod ?? 0, periods: Math.max(1, Number(e.target.value)), frequency: prev?.frequency ?? 'MONTHLY' }))} className="w-full border border-slate-700 rounded-lg px-3 py-2 text-sm bg-slate-900 text-slate-100 focus:outline-none focus:ring-2 focus:ring-indigo-500" />
              </div>
              <div className="flex flex-col gap-1">
                <span className="text-sm text-slate-300">Frequency</span>
                <select value={dca?.frequency ?? 'MONTHLY'} onChange={e => setDca(prev => ({ amountPerPeriod: prev?.amountPerPeriod ?? 0, periods: prev?.periods ?? 1, frequency: e.target.value as DcaSchedule['frequency'] }))} className="w-full border border-slate-700 rounded-lg px-3 py-2 text-sm bg-slate-900 text-slate-100 focus:outline-none focus:ring-2 focus:ring-indigo-500">
                  <option value="MONTHLY">Monthly</option>
                  <option value="QUARTERLY">Quarterly</option>
                  <option value="YEARLY">Yearly</option>
                </select>
              </div>
            </div>
            <div className="pt-1">
              <button disabled={!canSimulate || loading} onClick={simulate} className="w-full px-3 py-2.5 rounded-lg bg-indigo-500 text-white hover:bg-indigo-400 disabled:opacity-50 transition">{loading ? 'Simulating…' : 'Simulate'}</button>
            </div>
          </section>
        </div>

        {/* RIGHT PANE: chart (fills viewport height) */}
        <div className="xl:h-[calc(100vh-3rem)] bg-slate-900/70 backdrop-blur rounded-xl shadow-sm ring-1 ring-slate-700/50 p-4">
          <h2 className="font-semibold text-lg mb-2 text-slate-50">Evolution</h2>
          {!result && <div className="text-sm text-slate-400">Run a simulation to see results.</div>}
          {result && (
            <div className="h-[calc(100%-1.75rem)]">
              <ResponsiveContainer width="100%" height="100%">
                {/* Dual-axis chart: left for values, right for yearly dividends bars */}
                <ComposedChart data={chartData} margin={{ top: 8, right: 16, bottom: 0, left: 0 }}>
                  {/* Gradients for modern, subtle fills */}
                  <defs>
                    <linearGradient id="grad-total" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor="#60a5fa" stopOpacity={0.32} />
                      <stop offset="100%" stopColor="#60a5fa" stopOpacity={0} />
                    </linearGradient>
                    <linearGradient id="grad-contrib" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor="#34d399" stopOpacity={0.28} />
                      <stop offset="100%" stopColor="#34d399" stopOpacity={0} />
                    </linearGradient>
                    <linearGradient id="grad-bar" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor="#a78bfa" stopOpacity={0.95} />
                      <stop offset="100%" stopColor="#8b5cf6" stopOpacity={0.5} />
                    </linearGradient>
                    {result.instruments.map((s, idx) => (
                      <linearGradient key={`g-${s.isin}`} id={`grad-${s.isin}`} x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0%" stopColor={palette[idx % palette.length]} stopOpacity={0.25} />
                        <stop offset="100%" stopColor={palette[idx % palette.length]} stopOpacity={0} />
                      </linearGradient>
                    ))}
                  </defs>

                  <CartesianGrid stroke="#1f2937" strokeOpacity={0.8} vertical={false} />
                  <XAxis dataKey="month" tickFormatter={(m) => (m % 12 === 0 ? `${m/12}y` : '')} interval={0} tick={{ fill: '#94a3b8', fontSize: 12 }} tickLine={false} axisLine={false} />
                  <YAxis yAxisId="left" tickFormatter={(v) => fmt.format(Number(v))} tick={{ fill: '#94a3b8', fontSize: 12 }} tickLine={false} axisLine={false} />
                  <YAxis yAxisId="right" orientation="right" tickFormatter={(v) => fmt.format(Number(v))} tick={{ fill: '#94a3b8', fontSize: 12 }} tickLine={false} axisLine={false} />
                  <Tooltip contentStyle={{ backgroundColor: '#0b1220', border: '1px solid #1f2937', borderRadius: 10, color: '#e5e7eb' }} labelFormatter={(m) => (Number(m) % 12 === 0 ? `${Number(m)/12} years` : `Month ${m}`)} formatter={(val: any) => fmt.format(Number(val))} />
                  <Legend iconType="circle" wrapperStyle={{ color: '#a1a1aa', fontSize: 12 }} />

                  {/* Areas: modern subtle fills, no dots */}
                  <Area yAxisId="left" type="monotone" dataKey="total" stroke="#60a5fa" strokeWidth={2} dot={false} fill="url(#grad-total)" name="Total" />
                  <Area yAxisId="left" type="monotone" dataKey="contributed" stroke="#34d399" strokeWidth={2} dot={false} fill="url(#grad-contrib)" name="Contributed" />
                  {/* Cash DCA (real, after inflation) */}
                  <Area yAxisId="left" type="monotone" dataKey="cashReal" stroke="#ef4444" strokeWidth={2} strokeDasharray="4 3" dot={false} fillOpacity={0} name="Cash (real, FR inflation)" />

                  {/* Yearly dividends (sum of monthly) shown as bars on the right axis with value labels */}
                  <Bar yAxisId="right" dataKey="yearlyDivs" name="Yearly dividends (sum)" fill="url(#grad-bar)" barSize={16} radius={[6, 6, 0, 0]}>
                    <LabelList dataKey="yearlyDivs" position="top" formatter={(v: any) => fmt.format(Number(v))} fill="#c4b5fd" />
                  </Bar>
                </ComposedChart>
              </ResponsiveContainer>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

const palette = ['#7c3aed', '#ef4444', '#0ea5e9', '#10b981', '#f59e0b', '#f472b6']
const paletteLight = ['#ddd6fe', '#fecaca', '#bae6fd', '#bbf7d0', '#fde68a', '#fbcfe8']


