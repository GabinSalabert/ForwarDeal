# Forwardeal - ISIN Basket Simulator

Full‑stack app to simulate baskets of ISIN instruments with dividend policies (accumulating vs distributing), DCA schedules, fees, and a chart of portfolio evolution.

## Stack
- Backend: Spring Boot 3, Java 21, Spring Web, Validation, springdoc OpenAPI
- Frontend: Vite, React 18, TypeScript, Tailwind CSS, Recharts

## Prerequisites
- Java 21 (JDK)
- Maven (or run from an IDE like IntelliJ/VS Code)
- Node.js >= 18.17 and npm/pnpm/yarn

## Run backend
```bash
# from repo root
mvn spring-boot:run
# or run the Application class in com.example.forwardeal
```
OpenAPI UI: http://localhost:8080/swagger-ui/index.html

## Run frontend
```bash
cd frontend
npm install
npm run dev
# open http://localhost:5173
```

## API Endpoints
- GET /api/instruments - list instruments
- POST /api/simulations - run a simulation
Example request body:
```json
{
  "positions": [{ "isin": "IE00B4L5Y983", "quantity": 2 }],
  "initialCapital": 1000,
  "years": 5,
  "dca": { "amountPerPeriod": 100, "periods": 60, "frequency": "MONTHLY" },
  "feesAnnualBps": 0
}
```

## Notes
- Prices are normalized to 1 at t=0; initial capital allocates proportionally by quantities.
- Annual return, dividend yield, and fees are converted to monthly compounding.
- Distributing dividends are tracked as paid out; accumulating dividends are reinvested into units.

## Project Structure
- Backend: src/main/java/com/example/forwardeal
- Frontend: frontend/

License: MIT
