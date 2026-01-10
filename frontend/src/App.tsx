import { useEffect, useMemo, useRef, useState } from 'react'
// Recharts components for responsive area chart visualization
import { Area, ComposedChart, Bar, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis, LabelList } from 'recharts'
import html2canvas from 'html2canvas'
import jsPDF from 'jspdf'

// Instrument as returned by backend API
type Instrument = {
  isin: string
  name: string
  currentPrice: number
  totalAnnualReturnRate: number // ACGR10 from backend
  acgr10?: number
  dividendYieldAnnual: number
  expenseRatioAnnual?: number
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
  const [showReal, setShowReal] = useState(false)
  const [inflation, setInflation] = useState(0.03)
  const [sortMode, setSortMode] = useState<'ALPHA' | 'ACGR'>('ALPHA')
  const [usdToEur, setUsdToEur] = useState(0.92) // Default fallback rate (approximate)
  const chartRef = useRef<HTMLDivElement | null>(null)
  const pdfNominalRef = useRef<HTMLDivElement | null>(null)
  const pdfRealRef = useRef<HTMLDivElement | null>(null)
  const [activeTab, setActiveTab] = useState<'chart' | 'data'>('chart')

  // Fetch instruments and FX rate once on mount
  useEffect(() => {
    fetch(`${API_BASE}/instruments`).then(r => r.json()).then(setInstruments).catch(() => setInstruments([]))
    fetch(`${API_BASE}/instruments/fx-rate`).then(r => r.json()).then((data: { usdToEur: number }) => {
      if (data.usdToEur > 0) setUsdToEur(data.usdToEur)
    }).catch(() => {}) // Use default fallback if API call fails
  }, [])

  // Filter + sort instruments for display
  const visibleInstruments = useMemo(() => {
    const q = query.trim().toLowerCase()
    const filtered = instruments.filter(i => {
      if (!q) return true
      return i.name.toLowerCase().includes(q) || i.isin.toLowerCase().includes(q)
    })
    const byAlpha = (a: Instrument, b: Instrument) => a.name.localeCompare(b.name)
    const toAcgr = (i: Instrument) => (i.acgr10 ?? i.totalAnnualReturnRate ?? 0)
    const byAcgrDesc = (a: Instrument, b: Instrument) => (toAcgr(b) - toAcgr(a)) || byAlpha(a, b)
    return [...filtered].sort(sortMode === 'ACGR' ? byAcgrDesc : byAlpha)
  }, [instruments, query, sortMode])

  // Add an instrument to the basket if not already present
  const onAdd = (isin: string) => {
    setBasket(prev => {
      const found = prev.find(p => p.isin === isin)
      if (found) return prev
      return [...prev, { isin, quantity: 0 }]
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
  // Prices are in USD, so we convert to EUR for display and sending to backend
  const basketMarketValueUsd = useMemo(() => {
    return basket.reduce((sum, p) => sum + (instruments.find(i => i.isin === p.isin)?.currentPrice ?? 0) * p.quantity, 0)
  }, [basket, instruments])
  
  const basketMarketValue = useMemo(() => {
    return basketMarketValueUsd * usdToEur // Convert USD to EUR for display
  }, [basketMarketValueUsd, usdToEur])

  // Auto-compute fees (bps) from basket instruments' expense ratios
  const autoFeesBps = useMemo(() => {
    if (basket.length === 0) return 0
    const items = basket.map(p => {
      const ins = instruments.find(i => i.isin === p.isin)
      const price = ins?.currentPrice ?? 0
      const value = price * p.quantity
      const er = ins?.expenseRatioAnnual ?? 0
      return { value, er }
    })
    const totalVal = items.reduce((s, it) => s + it.value, 0)
    if (totalVal > 0) {
      const weightedEr = items.reduce((s, it) => s + it.er * it.value, 0) / totalVal
      return Math.round(weightedEr * 10000)
    }
    const ers = basket.map(p => instruments.find(i => i.isin === p.isin)?.expenseRatioAnnual ?? 0)
    const avgEr = ers.length ? (ers.reduce((s, e) => s + e, 0) / ers.length) : 0
    return Math.round(avgEr * 10000)
  }, [basket, instruments])

  // Keep fees field in sync with auto-computed value
  useEffect(() => { setFeesBps(autoFeesBps) }, [autoFeesBps])

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
    // Compute cash as pure accumulation: side capital + contributions (no interest, no inflation)
    const annualInflation = inflation
    const monthlyInflFactor = Math.pow(1 + annualInflation, 1 / 12)
    let realCash = sideCapital
    let prevContrib = result.portfolio[0]?.contributed ?? 0
    for (let idx = 0; idx < base.length; idx++) {
      const p = result.portfolio[idx]
      const deltaContrib = Math.max(0, p.contributed - prevContrib) // only new DCA adds to cash
      if (idx === 0) {
        base[idx].cashReal = sideCapital
      } else {
        realCash = realCash + deltaContrib
        base[idx].cashReal = showReal ? realCash / Math.pow(monthlyInflFactor, idx) : realCash
      }
      prevContrib = p.contributed
    }
    // Optional real-terms view: deflate basket totals by cumulative inflation (cash handled above; contributed stays nominal)
    if (showReal) {
      for (let idx = 0; idx < base.length; idx++) {
        const cf = Math.pow(monthlyInflFactor, idx)
        base[idx].total = base[idx].total / cf
      }
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
  }, [result, sideCapital, showReal])

  // Currency formatter for axes/tooltip labels (compact, modern)
  const fmt = useMemo(() => new Intl.NumberFormat(undefined, { notation: 'compact', maximumFractionDigits: 1 }), [])
  const fmtPct = useMemo(() => new Intl.NumberFormat(undefined, { style: 'percent', maximumFractionDigits: 2 }), [])
  const fmtCur = useMemo(() => new Intl.NumberFormat(undefined, { style: 'currency', currency: 'EUR', maximumFractionDigits: 0 }), [])

  // Submit the simulation request to the backend and store the response
  const simulate = async () => {
    if (!canSimulate) return
    setLoading(true)
    try {
      // Send initialCapital in USD (as calculated from USD prices), backend will handle conversion
      // DCA amounts and sideCapital are in EUR (user input), backend will convert them
      const body: any = { 
        positions: basket, 
        initialCapital: basketMarketValueUsd, // Send USD value, backend converts to EUR
        sideCapital, // User input in EUR
        years, 
        dca, // DCA amounts are in EUR (user input)
        feesAnnualBps: feesBps, 
        realTerms: showReal, 
        inflationAnnual: inflation 
      }
      const res = await fetch(`${API_BASE}/simulations`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
      })
      const data: SimulationResponse = await res.json()
      setResult(data)
    } finally {
      setLoading(false)
    }
  }

  // Export a styled, modern PDF with key KPIs and per-instrument yearly evolution
  const downloadPdf = async () => {
    if (!result) return
    // Snapshot chart
    const container = chartRef.current
    let chartImg: string | null = null
    if (container) {
      const canvas = await html2canvas(container, { backgroundColor: '#0b1220', scale: 2 })
      chartImg = canvas.toDataURL('image/png')
    }

    const doc = new jsPDF({ orientation: 'landscape', unit: 'pt', format: 'a4' })
    const pageW = doc.internal.pageSize.getWidth()
    const pageH = doc.internal.pageSize.getHeight()
    const margin = 36
    let y = margin

    // Header
    doc.setFillColor(17, 24, 39) // slate-900
    doc.setTextColor(255, 255, 255)
    doc.roundedRect(margin, y, pageW - margin * 2, 40, 8, 8, 'F')
    doc.setFont('helvetica', 'bold')
    doc.setFontSize(16)
    doc.text('Forwardeal — Simulation Report', margin + 16, y + 26)
    y += 56

    // Helpers to avoid locale Unicode separators that some PDF fonts misrender
    const formatPdfNumber = (n: number) => new Intl.NumberFormat('en-US', { maximumFractionDigits: 0 }).format(Math.round(n))
    const formatPdfCurrency = (n: number) => `${formatPdfNumber(n)} EUR`

    // KPI cards: Initial capital (basket), Final capital, Period
    const finalTotal = result.portfolio[result.portfolio.length - 1]?.totalValue ?? 0
    const kpiW = (pageW - margin * 2 - 24) / 3
    const kpiH = 72
    const kpiTitles = ['Initial capital', 'Final capital', 'Period']
    const kpiValues = [formatPdfCurrency(basketMarketValue), formatPdfCurrency(finalTotal), `${years} years`]
    for (let i = 0; i < 3; i++) {
      const x = margin + i * (kpiW + 12)
      doc.setFillColor(241, 245, 249) // slate-100
      doc.roundedRect(x, y, kpiW, kpiH, 10, 10, 'F')
      doc.setTextColor(51, 65, 85) // slate-700
      doc.setFont('helvetica', 'bold')
      doc.setFontSize(11)
      doc.text(kpiTitles[i], x + 14, y + 22)
      doc.setFont('helvetica', 'normal')
      doc.setFontSize(16)
      // Avoid exotic separators; using en-US already yields commas
      doc.text(kpiValues[i], x + 14, y + 46)
    }
    y += kpiH + 20

    // Chart image (styled)
    if (chartImg) {
      const imgW = pageW - margin * 2
      const imgH = imgW * 0.45
      if (y + imgH > pageH - margin) { doc.addPage(); y = margin }
      doc.roundedRect(margin - 4, y - 4, imgW + 8, imgH + 8, 8, 8, 'S')
      doc.addImage(chartImg, 'PNG', margin, y, imgW, imgH, undefined, 'FAST')
      y += imgH + 16
    }

    // Styled HTML content snapshot: Basket table + Per-instrument yearly evolution tables
    const addHtmlBlock = async (ref: React.RefObject<HTMLDivElement>) => {
      const block = ref.current
      if (!block) return
      const blockCanvas = await html2canvas(block, { scale: 2, backgroundColor: '#ffffff' })
      const imgW = pageW - margin * 2
      const scale = imgW / blockCanvas.width
      const firstAvailablePts = pageH - margin - y
      const pageAvailablePts = pageH - margin * 2
      let pxY = 0
      let first = true
      while (pxY < blockCanvas.height) {
        const availablePts = first ? firstAvailablePts : pageAvailablePts
        const availablePx = Math.max(50, Math.floor(availablePts / scale))
        const slicePx = Math.min(availablePx, blockCanvas.height - pxY)
        const sliceCanvas = document.createElement('canvas')
        sliceCanvas.width = blockCanvas.width
        sliceCanvas.height = slicePx
        const ctx = sliceCanvas.getContext('2d')!
        ctx.drawImage(blockCanvas, 0, pxY, blockCanvas.width, slicePx, 0, 0, blockCanvas.width, slicePx)
        const sliceImg = sliceCanvas.toDataURL('image/png')
        const slicePts = slicePx * scale
        const drawY = first ? y : margin
        if (!first) doc.addPage()
        doc.addImage(sliceImg, 'PNG', margin, drawY, imgW, slicePts, undefined, 'FAST')
        pxY += slicePx
        first = false
      }
      y = margin
    }

    // Nominal tables
    doc.setFont('helvetica', 'bold')
    doc.setFontSize(12)
    doc.text('Nominal (tables)', margin, y)
    y += 14
    await addHtmlBlock(pdfNominalRef)

    // Real tables
    doc.setFont('helvetica', 'bold')
    doc.setFontSize(12)
    doc.text('Real (tables, FR inflation deflated)', margin, y)
    y += 14
    await addHtmlBlock(pdfRealRef)

    doc.save(`forwardeal-report-${Date.now()}.pdf`)
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
            {/* Sort controls */}
            <div className="mb-3 flex gap-2">
              <button
                onClick={() => setSortMode('ALPHA')}
                className={`px-3 py-1.5 text-xs rounded-full border transition ${sortMode === 'ALPHA' ? 'bg-indigo-500 text-white border-indigo-400' : 'bg-slate-800/70 text-slate-200 border-slate-700 hover:bg-slate-700/60'}`}
                title="Trier par ordre alphabétique"
              >
                A → Z
              </button>
              <button
                onClick={() => setSortMode('ACGR')}
                className={`px-3 py-1.5 text-xs rounded-full border transition ${sortMode === 'ACGR' ? 'bg-indigo-500 text-white border-indigo-400' : 'bg-slate-800/70 text-slate-200 border-slate-700 hover:bg-slate-700/60'}`}
                title="Trier par ACGR (décroissant)"
              >
                ACGR
              </button>
            </div>
            <div className="max-h-80 overflow-auto custom-scroll divide-y divide-slate-800 pr-3">
              {visibleInstruments.map(i => (
                <div key={i.isin} className="py-3">
                  <div className="flex items-start justify-between gap-3">
                    <div>
                      <div className="font-medium text-slate-100">{i.name}</div>
                      <div className="text-xs text-slate-400">{i.isin} • Price {i.currentPrice?.toFixed(2)} • ACGR {((i.acgr10 ?? i.totalAnnualReturnRate) * 100).toFixed(1)}% • Div {(i.dividendYieldAnnual*100).toFixed(1)}% • Fees {(((i.expenseRatioAnnual ?? 0)*100)).toFixed(2)}% • {i.dividendPolicy}</div>
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
                <span className="text-sm text-slate-300">Fees (%/yr)</span>
                <input
                  type="number"
                  step={0.01}
                  value={Number((feesBps / 100).toFixed(2))}
                  onChange={e => setFeesBps(Number(e.target.value) * 100)}
                  className="w-full border border-slate-700 rounded-lg px-3 py-2 text-sm bg-slate-900 text-slate-100 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                />
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
          <div className="mb-2 flex items-center justify-between">
            <h2 className="font-semibold text-lg text-slate-50">Evolution</h2>
            <div className="flex items-center gap-2">
              {/* Tabs: Chart | Data */}
              <div className="flex items-center bg-slate-800/50 rounded-lg p-0.5 ring-1 ring-slate-700/60">
                <button
                  onClick={() => setActiveTab('chart')}
                  className={`px-3 h-8 rounded-md text-xs font-medium transition ${activeTab === 'chart' ? 'bg-slate-700 text-white' : 'text-slate-300 hover:text-white'}`}
                >
                  Chart
                </button>
                <button
                  onClick={() => setActiveTab('data')}
                  className={`px-3 h-8 rounded-md text-xs font-medium transition ${activeTab === 'data' ? 'bg-slate-700 text-white' : 'text-slate-300 hover:text-white'}`}
                >
                  Data
                </button>
              </div>
              {/* Small square PDF button */}
              <button
                onClick={downloadPdf}
                title="Download PDF"
                className="inline-flex h-8 w-8 items-center justify-center rounded-lg bg-slate-700/70 text-slate-100 ring-1 ring-slate-600 hover:bg-slate-600 transition"
              >
                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6z" stroke="currentColor" strokeWidth="1.5" fill="none"/>
                  <path d="M14 2v6h6" stroke="currentColor" strokeWidth="1.5" fill="none"/>
                  <path d="M7.5 16h2a1.5 1.5 0 0 0 0-3h-2v3z" stroke="currentColor" strokeWidth="1.5" fill="none"/>
                  <path d="M12 13v3m0 0h1.5M12 16h1a1 1 0 0 0 0-2h-1" stroke="currentColor" strokeWidth="1.5"/>
                  <path d="M16 13h2m-2 3h2" stroke="currentColor" strokeWidth="1.5"/>
                </svg>
              </button>
              {/* Modern toggle to switch NOMINAL vs REAL (FR inflation) */}
              <button
                onClick={() => setShowReal(v => !v)}
                className={`relative inline-flex h-8 w-[220px] items-center rounded-full transition ${showReal ? 'bg-indigo-500/90' : 'bg-slate-700/70'}`}
                title="Basculer NOMINAL / REAL (inflation FR)"
              >
                <span
                  className={`absolute left-1 top-1 h-6 w-6 rounded-full bg-white transition-transform ${showReal ? 'translate-x-[184px]' : 'translate-x-0'}`}
                />
                <span className="w-full text-center text-xs font-medium text-slate-100">
                  {showReal ? 'REAL' : 'NOMINAL'}
                </span>
              </button>
            </div>
          </div>
          {!result && <div className="text-sm text-slate-400">Run a simulation to see results.</div>}
          {result && activeTab === 'chart' && (
            <>
              <div className="h-[calc(100%-3.5rem)]" ref={chartRef}>
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
              {/* Off-screen HTML blocks to be captured into the PDF as styled images */}
              {/* NOMINAL */}
              <div ref={pdfNominalRef} className="fixed -left-[9999px] top-0 w-[1060px] bg-white text-slate-800 p-6">
                {/* Basket table */}
                <div className="mb-6">
                  <div className="text-sm font-semibold text-slate-700 mb-2">Basket</div>
                  <table className="w-full text-sm border border-slate-200 rounded-lg overflow-hidden">
                    <thead className="bg-slate-100 text-slate-700">
                      <tr>
                        <th className="text-left px-3 py-2">Instrument</th>
                        <th className="text-left px-3 py-2">ISIN</th>
                        <th className="text-right px-3 py-2">Qty</th>
                        <th className="text-right px-3 py-2">Price</th>
                        <th className="text-right px-3 py-2">Value</th>
                      </tr>
                    </thead>
                    <tbody>
                      {basket.map(p => {
                        const ins = instruments.find(i => i.isin === p.isin)
                        const price = ins?.currentPrice ?? 0
                        const val = price * p.quantity
                        return (
                          <tr key={p.isin} className="odd:bg-white even:bg-slate-50">
                            <td className="px-3 py-2">{ins?.name ?? p.isin}</td>
                            <td className="px-3 py-2 text-slate-600">{p.isin}</td>
                            <td className="px-3 py-2 text-right">{p.quantity}</td>
                            <td className="px-3 py-2 text-right">{fmtCur.format(price)}</td>
                            <td className="px-3 py-2 text-right">{fmtCur.format(val)}</td>
                          </tr>
                        )
                      })}
                    </tbody>
                  </table>
                </div>
                {/* Per-instrument yearly evolution */}
                <div className="space-y-6">
                  {result.instruments.map(s => (
                    <div key={s.isin}>
                      <div className="text-sm font-semibold text-slate-700 mb-2">{s.name} <span className="text-slate-500 font-normal">({s.isin})</span></div>
                      <table className="w-full text-sm border border-slate-200 rounded-lg overflow-hidden">
                        <thead className="bg-slate-100 text-slate-700">
                          <tr>
                            <th className="text-left px-3 py-2">Year</th>
                            <th className="text-right px-3 py-2">Value</th>
                          </tr>
                        </thead>
                        <tbody>
                          {Array.from({ length: years }).map((_, idx) => {
                            const year = idx + 1
                            const pointIdx = Math.min(year * 12, s.points.length - 1)
                            const val = s.points[pointIdx]?.value ?? 0
                            return (
                              <tr key={year} className="odd:bg-white even:bg-slate-50">
                                <td className="px-3 py-2">{year}</td>
                                <td className="px-3 py-2 text-right">{fmtCur.format(val)}</td>
                              </tr>
                            )
                          })}
                        </tbody>
                      </table>
                    </div>
                  ))}
                </div>
              </div>
              {/* REAL (deflated) */}
              <div ref={pdfRealRef} className="fixed -left-[9999px] top-0 w-[1060px] bg-white text-slate-800 p-6">
                {/* Per-instrument yearly evolution (real) */}
                <div className="space-y-6">
                  {result.instruments.map(s => (
                    <div key={s.isin}>
                      <div className="text-sm font-semibold text-slate-700 mb-2">{s.name} <span className="text-slate-500 font-normal">({s.isin})</span> — Real</div>
                      <table className="w-full text-sm border border-slate-200 rounded-lg overflow-hidden">
                        <thead className="bg-slate-100 text-slate-700">
                          <tr>
                            <th className="text-left px-3 py-2">Year</th>
                            <th className="text-right px-3 py-2">Value (real)</th>
                          </tr>
                        </thead>
                        <tbody>
                          {Array.from({ length: years }).map((_, idx) => {
                            const year = idx + 1
                            const endIdx = Math.min(year * 12, s.points.length - 1)
                            const inflMonthly = Math.pow(1 + inflation, 1 / 12)
                            const endRaw = s.points[endIdx]?.value ?? 0
                            const endReal = endRaw / Math.pow(inflMonthly, endIdx)
                            return (
                              <tr key={year} className="odd:bg-white even:bg-slate-50">
                                <td className="px-3 py-2">{year}</td>
                                <td className="px-3 py-2 text-right">{fmtCur.format(endReal)}</td>
                              </tr>
                            )
                          })}
                        </tbody>
                      </table>
                    </div>
                  ))}
                </div>
              </div>
            </>
          )}
          {result && activeTab === 'data' && (
            <div className="h-[calc(100%-3.5rem)] overflow-y-auto custom-scroll space-y-6">
              {/* Global Summary Card */}
              {(() => {
                const totalStart = result.portfolio[0]?.totalValue ?? 0
                const totalEnd = result.portfolio[result.portfolio.length - 1]?.totalValue ?? 0
                const totalContributed = result.portfolio[result.portfolio.length - 1]?.contributed ?? 0
                const totalGain = totalEnd - totalContributed
                const totalGainPct = totalContributed > 0 ? totalGain / totalContributed : 0
                
                // Calculate weighted average ACGR from result instruments
                const weightedAcgr = (() => {
                  if (!result.instruments || result.instruments.length === 0) return 0
                  // Use instruments from result and find their ACGR from the instruments list
                  const acgrs = result.instruments.map(s => {
                    const ins = instruments.find(i => i.isin === s.isin)
                    return ins?.acgr10 ?? ins?.totalAnnualReturnRate ?? 0
                  })
                  // Simple average if we can't get weights
                  if (acgrs.length === 0) return 0
                  return acgrs.reduce((sum, a) => sum + a, 0) / acgrs.length
                })()
                
                // Average investment duration with DCA (in years)
                // First euro invested for full period, last euro for 0 time
                // Average = years / 2 approximately
                const avgInvestmentDuration = years / 2
                const hasDca = dca && dca.amountPerPeriod > 0
                
                return (
                  <div className="rounded-2xl bg-gradient-to-br from-indigo-900/40 to-slate-900/60 ring-1 ring-indigo-500/30 p-6">
                    <h3 className="text-lg font-bold text-white mb-4 flex items-center gap-2">
                      <span className="text-2xl">📊</span> Résumé de votre investissement
                    </h3>
                    <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
                      <div className="bg-slate-800/50 rounded-xl p-4">
                        <div className="text-xs text-slate-400 mb-1">💰 Total investi</div>
                        <div className="text-xl font-bold text-white">{fmtCur.format(totalContributed)}</div>
                      </div>
                      <div className="bg-slate-800/50 rounded-xl p-4">
                        <div className="text-xs text-slate-400 mb-1">🎯 Valeur finale</div>
                        <div className="text-xl font-bold text-emerald-400">{fmtCur.format(totalEnd)}</div>
                      </div>
                      <div className="bg-slate-800/50 rounded-xl p-4">
                        <div className="text-xs text-slate-400 mb-1">✨ Gains totaux</div>
                        <div className={`text-xl font-bold ${totalGain >= 0 ? 'text-emerald-400' : 'text-rose-400'}`}>
                          {totalGain >= 0 ? '+' : ''}{fmtCur.format(totalGain)}
                        </div>
                        <div className={`text-sm ${totalGain >= 0 ? 'text-emerald-300' : 'text-rose-300'}`}>
                          soit +{(totalGainPct * 100).toFixed(1)}% sur le capital investi
                        </div>
                      </div>
                      <div className="bg-slate-800/50 rounded-xl p-4">
                        <div className="text-xs text-slate-400 mb-1">📈 Rendement du marché (ACGR)</div>
                        <div className="text-xl font-bold text-amber-400">
                          +{(weightedAcgr * 100).toFixed(1)}%/an
                        </div>
                        <div className="text-xs text-slate-500">moyenne historique sur 10 ans</div>
                      </div>
                    </div>
                    
                    {/* Explanation box */}
                    <div className="bg-slate-800/30 rounded-lg p-4 text-sm space-y-3">
                      <div className="text-slate-300">
                        <span className="text-indigo-400 font-medium">💡 Intérêts composés :</span> Vos gains génèrent eux-mêmes des gains ! 
                        C'est pour cela que votre investissement de <span className="text-white font-medium">{fmtCur.format(totalContributed)}</span> vaut maintenant <span className="text-emerald-400 font-medium">{fmtCur.format(totalEnd)}</span>.
                      </div>
                      
                      {hasDca && (
                        <div className="bg-amber-900/20 border border-amber-700/30 rounded-lg p-3 text-amber-200">
                          <span className="font-medium">⚠️ Pourquoi mes gains ({(totalGainPct * 100).toFixed(1)}%) semblent plus bas que l'ACGR ({(weightedAcgr * 100).toFixed(1)}%/an) ?</span>
                          <p className="mt-1 text-amber-100/80">
                            Avec le <strong>DCA</strong> (investissement progressif), votre argent n'est pas investi pendant toute la durée :
                          </p>
                          <ul className="mt-2 text-xs space-y-1 text-amber-100/70">
                            <li>• Votre 1er versement a profité de {years} ans de croissance</li>
                            <li>• Votre dernier versement n'a eu que quelques mois</li>
                            <li>• <strong>En moyenne</strong>, votre argent a été investi ~{avgInvestmentDuration.toFixed(1)} ans</li>
                          </ul>
                          <p className="mt-2 text-amber-100/80">
                            → C'est <strong>normal</strong> ! Le marché a bien fait +{(weightedAcgr * 100).toFixed(1)}%/an, mais tout votre argent n'en a pas profité pendant {years} ans.
                          </p>
                        </div>
                      )}
                    </div>
                  </div>
                )
              })()}

              {/* Per-instrument detailed breakdown */}
              {result.instruments.map((s, idx) => {
                const color = palette[idx % palette.length]
                const ins = instruments.find(i => i.isin === s.isin)
                const acgr = (ins?.acgr10 ?? ins?.totalAnnualReturnRate ?? 0) * 100
                const start0 = s.points[0]?.value ?? 0
                const endVal = s.points[s.points.length - 1]?.value ?? 0
                
                // Get total contributed for this instrument to calculate real gains
                const totalContributedInstr = result.portfolio[result.portfolio.length - 1]?.contributed ?? 0
                const totalGainInstr = endVal - totalContributedInstr
                const totalGainPctInstr = totalContributedInstr > 0 ? totalGainInstr / totalContributedInstr : 0
                
                return (
                  <div key={s.isin} className="rounded-xl ring-1 ring-slate-700/50 bg-slate-900/60 p-5">
                    {/* Header with summary */}
                    <div className="flex items-start justify-between mb-4">
                      <div className="flex items-center gap-3">
                        <span className="inline-block h-4 w-4 rounded-full" style={{ backgroundColor: color }} />
                        <div>
                          <div className="text-base font-semibold text-slate-100">{s.name}</div>
                          <div className="text-xs text-slate-400">{s.isin}</div>
                        </div>
                      </div>
                      <div className="text-right">
                        <div className={`text-lg font-bold ${totalGainInstr >= 0 ? 'text-emerald-400' : 'text-rose-400'}`}>
                          {totalGainInstr >= 0 ? '+' : ''}{fmtCur.format(totalGainInstr)}
                        </div>
                        <div className={`text-sm ${totalGainPctInstr >= 0 ? 'text-emerald-300' : 'text-rose-300'}`}>
                          {totalGainPctInstr >= 0 ? '+' : ''}{(totalGainPctInstr * 100).toFixed(1)}% sur {years} ans
                        </div>
                      </div>
                    </div>

                    {/* Info card about ACGR */}
                    <div className="bg-slate-800/40 rounded-lg p-3 mb-4 text-sm">
                      <span className="text-amber-400 font-medium">📊 Rendement historique moyen :</span>{' '}
                      <span className="text-white font-bold">{acgr.toFixed(1)}%/an</span>
                      <span className="text-slate-400 ml-2">
                        (moyenne sur 10 ans — certaines années peuvent être négatives, mais sur la durée c'est positif)
                      </span>
                    </div>

                    {/* Yearly breakdown with months */}
                    <div className="space-y-4">
                      {Array.from({ length: years }).map((_, yIdx) => {
                        const year = yIdx + 1
                        const startIdx = yIdx * 12
                        const endIdx = Math.min(year * 12, s.points.length - 1)
                        const yearStartVal = s.points[startIdx]?.value ?? 0
                        const yearEndVal = s.points[endIdx]?.value ?? 0
                        
                        // Get contributed amounts from portfolio data
                        const yearStartContrib = result.portfolio[startIdx]?.contributed ?? 0
                        const yearEndContrib = result.portfolio[endIdx]?.contributed ?? 0
                        const yearDcaAdded = yearEndContrib - yearStartContrib
                        
                        // Pure gain = value change - new contributions (this is the "variation" due to market)
                        const yearMarketGain = (yearEndVal - yearStartVal) - yearDcaAdded
                        
                        // Calculate yearly dividends (sum of monthly dividends for this year)
                        let yearDividends = 0
                        for (let m = 0; m < 12; m++) {
                          const mIdx = startIdx + m
                          if (mIdx >= result.portfolio.length) break
                          yearDividends += result.portfolio[mIdx]?.monthlyDividendsGenerated ?? 0
                        }
                        
                        // Calculate year return % based on average capital during the year
                        // More accurate than just using start value (which could be 0 with DCA)
                        const avgCapitalDuringYear = (yearStartVal + yearEndVal) / 2
                        const yearGainPct = avgCapitalDuringYear > 0 ? yearMarketGain / avgCapitalDuringYear : 0
                        
                        // Get monthly data for this year (starting from month 1, not 0)
                        const monthlyData = []
                        for (let m = 0; m < 12; m++) {
                          const mIdx = startIdx + m
                          if (mIdx >= s.points.length) break
                          
                          // For month calculations, compare with previous month
                          const prevIdx = mIdx > 0 ? mIdx - 1 : 0
                          const prevVal = mIdx > 0 ? (s.points[prevIdx]?.value ?? 0) : 0
                          const curVal = s.points[mIdx]?.value ?? 0
                          
                          // Get contributions to calculate DCA vs interest
                          const prevContrib = mIdx > 0 ? (result.portfolio[prevIdx]?.contributed ?? 0) : 0
                          const curContrib = result.portfolio[mIdx]?.contributed ?? 0
                          const dcaThisMonth = curContrib - prevContrib
                          
                          // Interest = total change - DCA contribution
                          // For first data point (month 0), the value IS the DCA, so interest is 0
                          const totalChange = curVal - prevVal
                          const interestEarned = mIdx === 0 ? 0 : totalChange - dcaThisMonth
                          
                          // Interest % relative to the capital at start of month
                          const capitalAtStartOfMonth = prevVal > 0 ? prevVal : (curContrib - dcaThisMonth)
                          const interestPct = capitalAtStartOfMonth > 0 ? interestEarned / capitalAtStartOfMonth : 0
                          
                          // Cumulative: total interest earned since start
                          const totalInvested = curContrib
                          const totalInterestEarned = curVal - totalInvested
                          const totalReturnPct = totalInvested > 0 ? totalInterestEarned / totalInvested : 0
                          
                          monthlyData.push({
                            month: m + 1,
                            value: curVal,
                            dcaAdded: dcaThisMonth,
                            interestEarned: interestEarned,
                            interestPct: interestPct,
                            totalInvested: totalInvested,
                            totalInterestEarned: totalInterestEarned,
                            totalReturnPct: totalReturnPct
                          })
                        }
                        
                        // Count positive/negative months (skip month 0 which has no interest yet)
                        const monthsWithData = monthlyData.slice(1) // Skip first month (initial investment)
                        const positiveMonths = monthsWithData.filter(m => m.interestEarned > 0.01).length
                        const negativeMonths = monthsWithData.filter(m => m.interestEarned < -0.01).length
                        const neutralMonths = monthsWithData.filter(m => Math.abs(m.interestEarned) <= 0.01).length
                        
                        // Monthly rate for explanation
                        const monthlyRate = acgr / 12
                        
                        // Total months actually shown this year
                        const totalMonthsThisYear = monthlyData.length
                        
                        // Calculate cumulative gains at end of year (for comparison)
                        const cumulativeGainsEndOfYear = yearEndVal - yearEndContrib
                        
                        return (
                          <div key={year} className="rounded-lg ring-1 ring-slate-700/40 overflow-hidden">
                            {/* Year header */}
                            <div className={`px-4 py-3 ${yearMarketGain >= 0 ? 'bg-emerald-900/20' : 'bg-rose-900/20'}`}>
                              <div className="flex items-center justify-between">
                                <div className="flex items-center gap-3">
                                  <span className="text-lg font-bold text-white">Année {year}</span>
                                  <span className="text-xs text-slate-400">
                                    📈 {positiveMonths} mois en hausse • 📉 {negativeMonths} mois en baisse
                                    {neutralMonths > 0 && ` • ➡️ ${neutralMonths} stables`}
                                  </span>
                                </div>
                                <div className="text-right">
                                  <div className={`font-bold ${yearMarketGain >= 0 ? 'text-emerald-400' : 'text-rose-400'}`}>
                                    {yearMarketGain >= 0 ? '↗' : '↘'} {yearMarketGain >= 0 ? '+' : ''}{fmtCur.format(yearMarketGain)} cette année
                                  </div>
                                  <div className="text-xs text-slate-400">
                                    + {fmtCur.format(yearDcaAdded)} investis • {yearGainPct >= 0 ? '+' : ''}{(yearGainPct * 100).toFixed(1)}% de rendement annuel
                                  </div>
                                  <div className="text-xs text-amber-400 mt-1">
                                    📊 Gains cumulés fin d'année : {fmtCur.format(cumulativeGainsEndOfYear)}
                                  </div>
                                </div>
                              </div>
                              {/* Calculation explanation */}
                              <div className="mt-2 text-xs text-slate-500 border-t border-slate-700/50 pt-2 space-y-1">
                                <div>
                                  💡 <span className="text-slate-400">Calcul des gains de l'année :</span>{' '}
                                  ({fmtCur.format(yearEndVal)} fin − {fmtCur.format(yearStartVal)} début) − {fmtCur.format(yearDcaAdded)} versés = <span className="text-white">{fmtCur.format(yearMarketGain)}</span>
                                </div>
                                {yearDividends > 0 && (
                                  <div>
                                    💜 <span className="text-purple-400">Dividendes réinvestis cette année : {fmtCur.format(yearDividends)}</span>
                                    <span className="text-slate-500 ml-1">(inclus dans les gains, réinvestis automatiquement par le fonds)</span>
                                  </div>
                                )}
                              </div>
                            </div>
                            
                            {/* Monthly details */}
                            <div className="overflow-x-auto">
                              <table className="min-w-full text-sm">
                                <thead className="bg-slate-800/40 text-slate-400">
                                  <tr>
                                    <th className="text-left px-3 py-2 text-xs">Mois</th>
                                    <th className="text-right px-3 py-2 text-xs">💰 Valeur totale</th>
                                    <th className="text-right px-3 py-2 text-xs">➕ Versement DCA</th>
                                    <th className="text-right px-3 py-2 text-xs">📊 Variation</th>
                                    <th className="text-right px-3 py-2 text-xs">📈 Rendement</th>
                                    <th className="text-right px-3 py-2 text-xs">💵 Investi (cumulé)</th>
                                    <th className="text-right px-3 py-2 text-xs">🎯 Gains (cumulés)</th>
                                    <th className="text-right px-3 py-2 text-xs">📈 Rendement total</th>
                                  </tr>
                                </thead>
                                <tbody>
                                  {monthlyData.map((m, mIdx) => {
                                    const monthNames = ['Jan', 'Fév', 'Mar', 'Avr', 'Mai', 'Juin', 'Juil', 'Août', 'Sep', 'Oct', 'Nov', 'Déc']
                                    const globalMonthIdx = startIdx + mIdx
                                    return (
                                      <tr key={m.month} className={mIdx % 2 === 0 ? 'bg-slate-900/30' : 'bg-slate-900/10'}>
                                        <td className="px-3 py-2 text-slate-200 font-medium">
                                          {monthNames[m.month - 1]}
                                        </td>
                                        <td className="px-3 py-2 text-right text-white font-bold">
                                          {fmtCur.format(m.value)}
                                        </td>
                                        <td className="px-3 py-2 text-right">
                                          {m.dcaAdded > 0 ? (
                                            <span className="text-blue-400">+{fmtCur.format(m.dcaAdded)}</span>
                                          ) : (
                                            <span className="text-slate-500">—</span>
                                          )}
                                        </td>
                                        <td className={`px-3 py-2 text-right font-medium ${m.interestEarned >= 0 ? 'text-emerald-400' : 'text-rose-400'}`}>
                                          {globalMonthIdx === 0 ? (
                                            <span className="text-slate-500">—</span>
                                          ) : (
                                            <span>{m.interestEarned >= 0 ? '+' : ''}{fmtCur.format(m.interestEarned)}</span>
                                          )}
                                        </td>
                                        <td className={`px-3 py-2 text-right ${m.interestPct >= 0 ? 'text-emerald-300' : 'text-rose-300'}`}>
                                          {globalMonthIdx === 0 ? (
                                            <span className="text-slate-500">—</span>
                                          ) : (
                                            <span>{m.interestPct >= 0 ? '+' : ''}{(m.interestPct * 100).toFixed(2)}%</span>
                                          )}
                                        </td>
                                        <td className="px-3 py-2 text-right text-slate-300">
                                          {fmtCur.format(m.totalInvested)}
                                        </td>
                                        <td className={`px-3 py-2 text-right font-medium ${m.totalInterestEarned >= 0 ? 'text-amber-400' : 'text-rose-400'}`}>
                                          {m.totalInterestEarned >= 0 ? '+' : ''}{fmtCur.format(m.totalInterestEarned)}
                                        </td>
                                        <td className="px-3 py-2 text-right">
                                          <span className={`font-bold ${m.totalReturnPct >= 0 ? 'text-emerald-400' : 'text-rose-400'}`}>
                                            {m.totalReturnPct >= 0 ? '+' : ''}{(m.totalReturnPct * 100).toFixed(2)}%
                                          </span>
                                        </td>
                                      </tr>
                                    )
                                  })}
                                </tbody>
                              </table>
                            </div>
                            
                            {/* Year summary explanation */}
                            <div className="px-4 py-3 bg-slate-800/30 text-xs text-slate-400 space-y-2">
                              <div className="flex items-start gap-2">
                                <span className="text-lg">💡</span>
                                <div>
                                  <span className="text-slate-300 font-medium">Comment lire ce tableau :</span>
                                  <ul className="mt-1 space-y-1 text-slate-400">
                                    <li>• <span className="text-blue-400">Versement DCA</span> = l'argent que VOUS ajoutez ce mois</li>
                                    <li>• <span className="text-emerald-400">Variation</span> = hausse/baisse du marché ce mois (≈{monthlyRate.toFixed(2)}%/mois en moyenne)</li>
                                    <li>• <span className="text-amber-400">Gains (cumulés)</span> = tous vos gains depuis le début (pas juste cette année !)</li>
                                  </ul>
                                  <div className="mt-2 text-xs text-slate-500 border-l-2 border-slate-600 pl-2">
                                    ⚠️ Ne confondez pas : l'en-tête montre les <span className="text-white">gains de l'année</span>, 
                                    la colonne montre les <span className="text-amber-400">gains cumulés</span> depuis le début.
                                  </div>
                                </div>
                              </div>
                              <div className="border-t border-slate-700/50 pt-2">
                                {yearMarketGain >= 0 ? (
                                  <span>
                                    ✅ <span className="text-emerald-400">Bonne année !</span> Le marché vous a rapporté <span className="text-white font-medium">{fmtCur.format(yearMarketGain)}</span> de gains 
                                    ({(yearGainPct * 100).toFixed(2)}% de rendement sur votre capital).
                                    {negativeMonths > 0 && <span className="text-slate-500"> Malgré {negativeMonths} mois de baisse, le bilan annuel reste positif.</span>}
                                  </span>
                                ) : (
                                  <span>
                                    ⚠️ <span className="text-rose-400">Année difficile.</span> Le marché a fait perdre <span className="text-white font-medium">{fmtCur.format(Math.abs(yearMarketGain))}</span> à votre portefeuille.
                                    <span className="text-amber-400"> C'est normal ! Les marchés fluctuent, mais sur le long terme ({acgr.toFixed(1)}%/an en moyenne), la tendance est positive.</span>
                                  </span>
                                )}
                              </div>
                            </div>
                          </div>
                        )
                      })}
                    </div>

                    {/* Final compound interest explanation */}
                    <div className="mt-4 bg-gradient-to-r from-indigo-900/30 to-purple-900/30 rounded-lg p-4 text-sm">
                      <div className="font-medium text-indigo-300 mb-2">🧮 Comment fonctionnent les intérêts composés ?</div>
                      <div className="text-slate-300 space-y-1">
                        <p>• <span className="text-white">Mois 1</span> : Votre capital de départ génère des intérêts</p>
                        <p>• <span className="text-white">Mois 2</span> : Ces intérêts sont ajoutés à votre capital → le nouveau total génère plus d'intérêts</p>
                        <p>• <span className="text-white">Et ainsi de suite...</span> Chaque mois, vous gagnez des intérêts sur vos intérêts précédents !</p>
                        <p className="mt-2 text-indigo-200">
                          📌 Résultat : <span className="font-bold">{fmtCur.format(totalContributedInstr)}</span> investi → <span className="font-bold text-emerald-400">{fmtCur.format(endVal)}</span> final
                          {' '}({totalGainInstr >= 0 ? '+' : ''}{fmtCur.format(totalGainInstr)} de gains, soit {totalGainPctInstr >= 0 ? '+' : ''}{(totalGainPctInstr * 100).toFixed(1)}%)
                        </p>
                        <p className="text-xs text-slate-400 mt-1">
                          Rendement du marché (ACGR) : <span className="text-amber-400 font-medium">+{acgr.toFixed(1)}%/an</span> en moyenne sur 10 ans
                        </p>
                      </div>
                    </div>
                  </div>
                )
              })}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

const palette = ['#7c3aed', '#ef4444', '#0ea5e9', '#10b981', '#f59e0b', '#f472b6']
const paletteLight = ['#ddd6fe', '#fecaca', '#bae6fd', '#bbf7d0', '#fde68a', '#fbcfe8']


