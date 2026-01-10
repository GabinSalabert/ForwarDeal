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

### Realistic simulation
- 📈 **ACGR-based growth** (10-year compound annual growth rate)
- 📊 **Realistic volatility**: ~3 down months per year while respecting the ACGR
- 💸 **DCA**: amount, frequency, investments per period (first investment from month 0)
- 🪙 **Dividends**: Accumulating vs Distributing (bars for yearly sums)
- 🧾 **Fees**: value-weighted (%/yr) auto-filled from expense ratios
- 🌓 **Nominal vs Real**: toggle to see FR inflation impact

### Pedagogical interface (for beginners)
- 💡 **Detailed explanations** for each calculation (return, gains, dividends)
- 📊 **Monthly table** with variation, DCA contributions, cumulative gains
- 🎯 **Clear distinction**: yearly gains vs cumulative gains since start
- 📈📉 **Visual counter**: X months up / Y months down per year
- 💜 **Reinvested dividends** explained in each year's header
- ⚠️ **Educational warnings**: why DCA return ≠ market ACGR

### Search & display
- 🔍 Search and sort instruments (by ACGR / A-Z)
- 💰 Real-time prices, automatic EUR/USD conversion

## 🏗️ Architecture
**Backend** (Java 21, Spring Boot 3)
- `GET /api/instruments` → curated universe with `currentPrice`, `acgr10`, `dividendYieldAnnual`, `expenseRatioAnnual`, `dividendPolicy`.
- `POST /api/simulations` → monthly time series for portfolio and each instrument.
- Market data via `YahooFinanceProvider`: price (Yahoo/Stooq, FX aware), ACGR from ~10y monthly log‑returns with fallbacks + CSV hints, dividend yield and expense ratio with conservative defaults on rate‑limit.

**Frontend** (Vite + React + TypeScript + TailwindCSS + Recharts)
- Left panel (scrollable): instruments, basket, parameters.
- Right panel (fixed): interactive composed chart with gradients, dashed cash curve, yearly dividend bars.

## 🧠 Simulation model

### Realistic volatility
Monthly returns are **not constant** — they simulate real market volatility:
- Each year generates 12 monthly returns with ~3 negative months
- Monthly volatility is around 4% (typical for equity markets)
- The product of 12 monthly returns = exactly the annual ACGR

```
Example for ACGR = 10%/yr:
Jan: +2.1%, Feb: -1.8%, Mar: +3.2%, Apr: -0.5%, May: +1.9%, ...
→ Final product = 1.10 (10% annual respected)
```

### Formulas
- **Monthly nominal return**: \( r_m = (1+ACGR)^{1/12} - 1 \) (base, then variance added)
- **REAL mode**: \( r_m^{real} = \frac{1+r_m}{1+i_m} - 1 \) with \( i_m = (1+inflation)^{1/12} - 1 \)
- **Monthly fees**: \( f_m = (1 - fee_{yr})^{1/12} - 1 \)
- **Price update**: `price *= (1 + r_volatile) * (1 + f_m)`

### DCA (Dollar-Cost Averaging)
- The **first DCA investment is made at month 0** (January of year 1)
- Then, investments according to chosen frequency (monthly/quarterly/yearly)
- Equal split if initial quantities = 0, otherwise proportional to quantities

### Dividends
- ACGR already includes total return (price + dividends)
- For **accumulating** funds: dividends automatically reinvested (included in value)
- For **distributing** funds: dividends tracked separately for display

### Pedagogical display
- **Yearly gains** vs **Cumulative gains** clearly distinguished
- Explanation of each metric calculation
- Up/down months visually counted

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

### Prerequisites
- **Java 21** — [Download here](https://www.oracle.com/java/technologies/downloads/#java21)
- **Maven** or **Maven Daemon (mvnd)** — [mvnd recommended](https://github.com/apache/maven-mvnd/releases) (2-10x faster)
- **Node 18+** — for frontend build

### Launch (monolithic architecture)
The frontend is compiled and served directly by the Spring Boot backend on the **same port**.

```bash
# With Maven Daemon (recommended, faster)
mvnd spring-boot:run

# OR with classic Maven
mvn -q -DskipTests spring-boot:run
```

📍 **Application available at**: http://localhost:8080

> The React frontend is automatically copied to `target/classes/static/` during Maven build.

### Frontend build (if modifications)
```bash
cd frontend
npm install        # first time only
npm run build      # generates dist/
```
Then restart `mvnd spring-boot:run` to integrate changes.

### Swagger UI (API documentation)
http://localhost:8080/swagger-ui/index.html

## 🧰 Tooling — what and why
- Maven / Maven Daemon (backend build & run)
  - What it is: the de‑facto Java build tool and dependency manager. **Maven Daemon (mvnd)** is an optimized version that keeps a JVM running in background for 2-10x faster builds.
  - Why we use it: it fetches Spring/HTTP/validation libraries, compiles the code, and runs the app via the Spring Boot Maven Plugin.
  - Typical commands: `mvnd spring-boot:run` (recommended) or `mvn -q -DskipTests spring-boot:run`, `mvn package`.
  - Installation mvnd: [github.com/apache/maven-mvnd/releases](https://github.com/apache/maven-mvnd/releases)

- Node.js (frontend runtime & tooling)
  - What it is: a JavaScript runtime used to execute tooling (npm) and dev servers.
  - Why we use it: to install packages (`npm install`), run the Vite dev server (`npm run dev`) and build the React UI for production.
  - Typical commands: `npm install`, `npm run dev`, `npm run build` (if configured).

- Swagger / OpenAPI (API docs & testing)
  - What it is: interactive documentation UI generated from the backend’s OpenAPI spec.
  - Why we use it: to explore and test REST endpoints without writing a client; it shows request/response schemas and sample payloads.
  - How to open: `http://localhost:8080/swagger-ui/index.html` once the backend is running.

- Spring Web (REST layer)
  - What it is: the Spring MVC stack for building HTTP APIs (controllers, routing, JSON serialization).
  - Why we use it: to expose `/api/instruments` and `/api/simulations`, handle validation errors, and configure CORS for the frontend.
  - Where: `com.example.forwardeal.api.*` controllers.

- Validation (Jakarta Bean Validation)
  - What it is: annotation‑based validation (`@NotBlank`, `@PositiveOrZero`, `@Valid`, etc.).
  - Why we use it: to validate `SimulationRequest` and nested records safely before running the simulation.
  - Benefit: concise, declarative constraints with automatic 400 responses on violations.

- springdoc OpenAPI (spec generation)
  - What it is: a Spring integration that generates an OpenAPI spec from controllers and schemas.
  - Why we use it: to drive Swagger UI and keep API docs in sync with the code.
  - Result: typed request/response models based on our Java records.

- Vite (frontend dev server & bundler)
  - What it is: a fast dev server with HMR and a modern bundling pipeline.
  - Why we use it: instant feedback during development and optimized builds for production.
  - Commands: `npm run dev`, `npm run build`.

- TypeScript (typing for the UI)
  - What it is: a typed superset of JavaScript.
  - Why we use it: to model API DTOs, chart rows, and component props with compile‑time safety.
  - Outcome: fewer runtime bugs and clearer contracts between UI and API.

- Tailwind CSS (styling)
  - What it is: a utility‑first CSS framework.
  - Why we use it: to implement a minimal, modern dark UI quickly (spacing, colors, typography, responsive) without writing custom CSS files.
  - Extras: custom scrollbars and gradients via utilities.

- Recharts (charting)
  - What it is: a React chart library based on SVG.
  - Why we use it: to compose Area + Bar series, custom gradients, dual axes, and rich tooltips for the portfolio evolution.
  - In this app: Total & Contributed (areas), Cash (dashed), Yearly dividends (bars).

## 📁 Structure
```
src/main/java/com/example/forwardeal   # Backend (API, domain, services, provider)
src/main/resources/universe/           # Curated instrument CSVs with optional ACGR hints
frontend/                              # React + Vite UI
docs/                                  # Documentation assets (add simulation-example.png here)
```

## 🧭 Example scenario

### Simulation: €2,000/month on MSCI World for 4 years

1. **Add** "iShares MSCI World ETF" to basket (initial quantity = 0)
2. **Configure**:
   - DCA: €2,000/month
   - Frequency: Monthly
   - Horizon: 4 years
3. **Run** the simulation

### Expected result (ACGR ~10%/yr)

| Metric | Value |
|--------|-------|
| 💰 Total invested | €96,000 |
| 🎯 Final value | ~€116,000 |
| ✨ Total gains | ~€20,000 (+21%) |
| 📈 Average annual return | ~4.8%/yr* |

> *Personal return (~4.8%) is lower than market ACGR (10%) because with DCA, your money isn't invested for the entire duration. The first contribution benefits from 4 years of growth, but the last one only a few months.

### A realistic chart, with the option to account for inflation (Nominal => Real)!

- 📊 **Realistic volatility**: ~3 down months per year (red)
- 💜 **Reinvested dividends**: displayed in each year's header
- 📈 **Cumulative gains** vs **Yearly gains**: clearly distinguished
- 💡 **Pedagogical explanations**: detailed calculations for each metric

![Simulation example](https://i.postimg.cc/nzRgt2YT/image.png)

### A perfectly detailed data grid for each year!

1. Header - Global
![Simulation example](https://i.postimg.cc/3xNPnPX8/image.png)

2. Year 1 - Detailed:
![Simulation example](https://i.postimg.cc/nrnW75Vm/image.png)

3. Year 4 - Bottom
![Simulation example](https://i.postimg.cc/x1X4KLQt/image.png)

## ⚖️ License
MIT

---

## 🖥️ Desktop packaging (.exe) — How it works

This project ships as a Spring Boot backend plus a React frontend. We can package both into a single desktop application for Windows using `jpackage` (Java 21+) and Maven.

### What the .exe does
- Starts an embedded Java runtime with your Spring Boot server (port 8080 by default).
- The React frontend is prebuilt and copied into Spring’s `static/`, so it’s served directly by the backend at `http://localhost:8080/`.
- You can create a desktop shortcut and a start menu entry with a custom icon.

### Build steps (Windows)
Prereqs: Java 21 with jpackage (included in recent JDKs), Maven, Node 18+

1) Build the backend and frontend together
```bash
mvn -q -DskipTests package
```
This will:
- run the frontend build (`frontend/` → `dist/`)
- copy the `dist/` assets into `target/classes/static`
- assemble a Spring Boot runnable jar at `target/forwardeal-<version>.jar`

2) Produce the Windows installer (.exe) with jpackage
```bash
mvn -P windows -Dicon="path/to/icon.ico" org.panteleyev:jpackage-maven-plugin:jpackage
```
Outputs an installer under `target/jpackage/Forwardeal-<version>.exe`.

3) Install & launch
- Run the generated `.exe` and follow the wizard.
- A “Forwardeal” shortcut will be added to the desktop/start menu.
- Double‑click to launch. The app starts the backend and serves the UI at `http://localhost:8080/` in your default browser. You can pin the shortcut to the taskbar if you wish.

### Notes & customization
- Port: change the server port by editing the jpackage `jvmArgs` in `pom.xml` or by providing `-Dserver.port=XXXX`.
- Icon: pass `-Dicon=...` to the jpackage command; must be `.ico` on Windows.
- Auto‑open browser: you can create a small `startup.bat` that first starts the exe then opens the browser to `http://localhost:8080/` if you want the browser to pop automatically.
- Mac/Linux: similar packaging is possible by changing the jpackage type (`dmg`, `pkg`, `deb`, `rpm`).
