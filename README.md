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
1. Add “iShares MSCI ACWI ETF” and “iShares MSCI World ETF”.
2. Set DCA amount = 1200, investments per period = 1, frequency = Monthly, horizon = 15 years.
3. Leave Fees (%/yr) auto‑filled. Click “Simulate”.
4. Toggle REAL to visualize purchasing‑power effects.

![Simulation example](https://i.postimg.cc/YCkqK3vy/Capture-d-e-cran-2025-10-12-a-18-44-07.png)

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
