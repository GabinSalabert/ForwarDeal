<div align="center">

# Forwardeal — Investment Basket Simulator

<br />

<img src="https://img.shields.io/badge/Java-21-1f2937?logo=openjdk&logoColor=white" alt="Java 21" />
<img src="https://img.shields.io/badge/Spring%20Boot-3-1f2937?logo=springboot&logoColor=6db33f" alt="Spring Boot" />
<img src="https://img.shields.io/badge/React-18-1f2937?logo=react&logoColor=61dafb" alt="React 18" />
<img src="https://img.shields.io/badge/Vite-5-1f2937?logo=vite&logoColor=646cff" alt="Vite" />
<img src="https://img.shields.io/badge/TypeScript-1f2937?logo=typescript&logoColor=3178c6" alt="TS" />
<img src="https://img.shields.io/badge/Tailwind-1f2937?logo=tailwindcss&logoColor=06b6d4" alt="Tailwind" />
<img src="https://img.shields.io/badge/Recharts-1f2937" alt="Recharts" />

<br /><br />

Simulate long‑term investments on baskets of ISINs (stocks/ETFs) with DCA, dividend policies, fees and a modern chart. Clean, minimal, and fast.

<br />

</div>

---

## 🔎 Table of contents
- ✨ Features
- 🏗️ Architecture
- 🧠 Simulation model
- 🎛️ Parameters
- 🔁 Data flow
- 🚀 Run locally
- 📁 Structure
- 🧭 Example scenario
- ⚖️ License

## ✨ Features
- 📈 ACGR‑driven growth per instrument (10‑year compound annual growth rate)
- 💸 DCA: amount, frequency, and investments per period
- 🪙 Dividend policies: Accumulating vs Distributing (bars for yearly sums)
- 🧾 Fees: value‑weighted (%/yr) auto‑filled from instruments’ expense ratios
- 🌓 Nominal vs Real (inflation FR) toggle
- 🔍 Search and sort instruments (by ACGR / A‑Z), real‑time price snapshot

## 🏗️ Architecture
**Backend** (Java 21, Spring Boot 3)
- `GET /api/instruments` → curated universe with `currentPrice`, `acgr10`, `dividendYieldAnnual`, `expenseRatioAnnual`, `dividendPolicy`.
- `POST /api/simulations` → monthly time series for portfolio and each instrument.
- Market data via `YahooFinanceProvider`: price (Yahoo/Stooq, FX aware), ACGR from ~10y monthly log‑returns with fallbacks + CSV hints, dividend yield and expense ratio with conservative defaults on rate‑limit.

**Frontend** (Vite + React + TypeScript + TailwindCSS + Recharts)
- Left panel (scrollable): instruments, basket, parameters.
- Right panel (fixed): interactive composed chart with gradients, dashed cash curve, yearly dividend bars.

## 🧠 Simulation model
- Monthly nominal return per instrument: \( r_m = (1+ACGR)^{1/12} - 1 \)
- REAL mode: \( r_m^{real} = \frac{1+r_m}{1+i_m} - 1 \) with \( i_m = (1+inflation)^{1/12} - 1 \)
- Fees monthly factor: \( f_m = (1 - fee_{yr})^{1/12} - 1 \)
- Price update: `price *= (1 + r_effective) * (1 + f_m)`
- Dividends: ACGR already includes total return. To avoid double counting we do NOT add dividends to value (accumulating does not add units; distributing tracked only for yearly bars).
- DCA: at each checkpoint, invest `amountPerPeriod × periods` (equal split if starting quantities are all 0, else proportional to initial quantities).
- Contributed stays nominal (including in REAL mode). Cash curve is deflated by cumulative FR inflation.

## 🎛️ Parameters
- Basket (ISIN + quantity at t0, can be 0)
- Years (horizon)
- DCA schedule: amount / frequency (M/Q/Y) / investments per period
- Fees (%/yr): basket‑level; UI auto‑fills with weighted average of instruments’ expense ratios
- Side capital (cash): included in Total; not invested; shown as real cash curve
- Real vs Nominal toggle

## 🔁 Data flow
1) UI calls `GET /api/instruments` → shows list with live prices/metrics.
2) User configures basket + parameters → `POST /api/simulations`.
3) Backend returns monthly points for portfolio and instruments.
4) UI renders Total, Contributed, Cash (real), and yearly dividend bars.

## 🚀 Run locally
Prereqs: Java 21 • Maven • Node 18+

Backend
```bash
mvn -q -DskipTests spring-boot:run
# Swagger UI → http://localhost:8080/swagger-ui/index.html
```

Frontend
```bash
cd frontend
npm install
npm run dev -- --port 5210 --strictPort --host
# Open http://localhost:5210/
```

> CORS is enabled for `http://localhost:*` and `http://127.0.0.1:*` during development.

## 🧰 Tooling — what and why
- Maven (backend build & run)
  - What it is: the de‑facto Java build tool and dependency manager.
  - Why we use it: it fetches Spring/HTTP/validation libraries, compiles the code, and runs the app via the Spring Boot Maven Plugin.
  - Typical commands: `mvn -q -DskipTests spring-boot:run`, `mvn package`.

- Node.js (frontend runtime & tooling)
  - What it is: a JavaScript runtime used to execute tooling (npm) and dev servers.
  - Why we use it: to install packages (`npm install`), run the Vite dev server (`npm run dev`) and build the React UI for production.
  - Typical commands: `npm install`, `npm run dev`, `npm run build` (if configured).

- Swagger / OpenAPI (API docs & testing)
  - What it is: interactive documentation UI generated from the backend’s OpenAPI spec.
  - Why we use it: to explore and test REST endpoints without writing a client; it shows request/response schemas and sample payloads.
  - How to open: `http://localhost:8080/swagger-ui/index.html` once the backend is running.

## 📁 Structure
```
src/main/java/com/example/forwardeal   # Backend (API, domain, services, provider)
src/main/resources/universe/           # Curated instrument CSVs with optional ACGR hints
frontend/                              # React + Vite UI
docs/                                  # Documentation assets (add simulation-example.png here)
```

## 🧭 Example scenario
1. Add “iShares MSCI ACWI ETF” and “iShares MSCI World ETF”.
2. Set DCA amount = 1200, investments per period = 1, frequency = Monthly, horizon = 15 years.
3. Leave Fees (%/yr) auto‑filled. Click “Simulate”.
4. Toggle REAL to visualize purchasing‑power effects.

![Simulation example](docs/simulation-example.png)

## ⚖️ License
MIT
