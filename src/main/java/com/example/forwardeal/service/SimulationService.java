package com.example.forwardeal.service;

import com.example.forwardeal.api.dto.SimulationDtos.*;
import com.example.forwardeal.domain.DividendPolicy;
import com.example.forwardeal.domain.Instrument;
import com.example.forwardeal.repository.InstrumentRepository;
import com.example.forwardeal.marketdata.YahooFinanceProvider;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class SimulationService {
    // Repository that gives us read-only access to instruments resolved at application start
    private final InstrumentRepository instrumentRepository;
    // Market data provider retained for future extensions (kept for constructor wiring)
    private final YahooFinanceProvider marketProvider;

    public SimulationService(InstrumentRepository instrumentRepository, YahooFinanceProvider marketProvider) {
        this.instrumentRepository = instrumentRepository;
        this.marketProvider = marketProvider;
    }

    public SimulationResponse simulate(SimulationRequest req) {
        // Convert requested horizon in years to months. All computations below are done monthly
        int months = req.years() * 12;
        // Convert annual fees in basis points to an annual rate (e.g., 30 bps => 0.003)
        double annualFees = req.feesAnnualBps() / 10000.0; // convert bps to rate

        // Resolve every requested ISIN to a known Instrument, fail fast if any is missing
        Map<String, Instrument> instrumentMap = new HashMap<>();
        for (InstrumentPosition p : req.positions()) {
            instrumentMap.put(p.isin(), instrumentRepository.findByIsin(p.isin()).orElseThrow());
        }

        // Portfolio time series for the aggregate values (total, contributed, dividends)
        List<TimePoint> portfolio = new ArrayList<>(months + 1);

        // Per-instrument time series for stacked/individual charts
        Map<String, List<InstrumentSeriesPoint>> perInstrumentPoints = new LinkedHashMap<>();
        // Units held for each instrument (starting from user-provided quantities)
        Map<String, Double> unitsHeld = new HashMap<>();
        // Current price for each instrument (evolves during the loop)
        Map<String, Double> prices = new HashMap<>();
        // Legacy holders for dividend attribution (no longer added to value to avoid double counting with ACGR)
        Map<String, Double> pendingYearDivByIsin = new HashMap<>();
        Map<String, Double> attributedDivCashByIsin = new HashMap<>();

        // Initialize units from user-provided quantities and seed prices with current market prices
        for (InstrumentPosition p : req.positions()) {
            unitsHeld.put(p.isin(), p.quantity());
            Instrument instrument = instrumentMap.get(p.isin());
            double startPrice = instrument != null ? instrument.getCurrentPrice() : 1.0;
            prices.put(p.isin(), startPrice);
            perInstrumentPoints.put(p.isin(), new ArrayList<>());
            pendingYearDivByIsin.put(p.isin(), 0.0);
            attributedDivCashByIsin.put(p.isin(), 0.0);
        }

        // Cumulative amount the user "contributed" to the portfolio; at t=0 this equals basket value + side capital
        double contributed = 0.0;
        // Cumulative dividends paid out when instruments are DISTRIBUTING
        double dividendsPaidCumulative = 0.0;

        // No additional initial allocation: initial capital equals current holdings' market value

        // DCA schedule handling (frequency: MONTHLY/QUARTERLY/YEARLY)
        int dcaEveryMonths = frequencyToMonths(req.dca() != null ? req.dca().frequency() : null);

        // Add starting point (month 0)
        // total = market value of basket + side capital (cash on the side)
        // contributed = initial capital only (basket value at t=0), excluding side capital
        double startValue = totalPortfolioValue(unitsHeld, prices) + req.sideCapital();
        contributed = req.initialCapital();
        portfolio.add(new TimePoint(0, startValue, contributed, dividendsPaidCumulative, 0.0));
        for (String isin : perInstrumentPoints.keySet()) {
            perInstrumentPoints.get(isin).add(new InstrumentSeriesPoint(0, unitsHeld.get(isin) * prices.get(isin)));
        }

        // Nominal monthly return from ACGR (deterministic growth path per instrument)
        Map<String, Double> monthlyNominalReturnByIsin = new HashMap<>();
        for (Map.Entry<String, Instrument> e : instrumentMap.entrySet()) {
            Instrument inst = e.getValue();
            double monthlyNominal = Math.pow(1.0 + inst.getAcgr10(), 1.0 / 12.0) - 1.0;
            monthlyNominalReturnByIsin.put(e.getKey(), monthlyNominal);
        }

        // If real-terms simulation is requested, convert monthly nominal returns to real by removing inflation
        // Determine REAL vs NOMINAL simulation behavior. In REAL mode, apply real monthly returns.
        boolean realTerms = false;
        double monthlyInflation = 0.0;
        try {
            realTerms = req.realTerms();
            monthlyInflation = Math.pow(1.0 + req.inflationAnnual(), 1.0 / 12.0) - 1.0;
        } catch (Throwable ignored) {}

        for (int m = 1; m <= months; m++) {
            double monthlyDividendsGeneratedTotal = 0.0;
            // Evolve prices monthly using ACGR-derived monthly returns + fees.
            for (Map.Entry<String, Instrument> e : instrumentMap.entrySet()) {
                String isin = e.getKey();
                Instrument inst = e.getValue();

                double monthlyFee = Math.pow(1.0 - annualFees, 1.0 / 12.0) - 1.0; // negative
                double monthlyNominal = monthlyNominalReturnByIsin.get(isin);
                // Convert to real monthly return if requested
                double monthlyEffective = realTerms
                        ? ((1.0 + monthlyNominal) / (1.0 + monthlyInflation)) - 1.0
                        : monthlyNominal;
                double newPrice = prices.get(isin) * (1.0 + monthlyEffective) * (1.0 + monthlyFee);

                // Dividends: distribute annual dividend yield evenly per month for a simple approximation
                // If an instrument is ACCUMULATING and reports ~0 dividend yield (common for accumulating share classes),
                // we apply a conservative fallback yield (1.5%/yr) purely to expose the amount that is implicitly
                // generated and reinvested. This keeps yearly dividend bars visible even when everything is accumulating.
                double annualYield = inst.getDividendYieldAnnual();
                if (annualYield <= 0.0 && inst.getDividendPolicy() == DividendPolicy.ACCUMULATING) {
                    annualYield = 0.015; // 1.5% fallback for accumulating instruments lacking a reported yield
                }
                double monthlyDividendYield = annualYield / 12.0;
                double dividendAmountPerUnit = prices.get(isin) * monthlyDividendYield;
                double monthlyDivForInstrument = unitsHeld.get(isin) * dividendAmountPerUnit;

                if (inst.getDividendPolicy() == DividendPolicy.ACCUMULATING) {
                    // Do not reinvest into additional units: ACGR already reflects total return including dividends.
                } else {
                    // DISTRIBUTING: track dividends as paid for reporting, but do not add to instrument value.
                    dividendsPaidCumulative += monthlyDivForInstrument;
                    // Keep yearly sum for bar display only (not added to value)
                    pendingYearDivByIsin.put(isin, pendingYearDivByIsin.get(isin) + monthlyDivForInstrument);
                }

                // Track total dividends generated this month regardless of policy
                monthlyDividendsGeneratedTotal += monthlyDivForInstrument;

                prices.put(isin, newPrice);
            }

            // Year boundary: reset yearly tracking (bars only). We do not attribute to value to avoid double counting.
            if (m % 12 == 0) {
                for (String isin : pendingYearDivByIsin.keySet()) {
                    pendingYearDivByIsin.put(isin, 0.0);
                }
            }

            // Apply DCA at the end of each frequency period.
            // Interpretation: 'periods' = number of individual contributions per frequency window
            // Example: MONTHLY + periods=2 + amount=100 => invest 200 at each end-of-month checkpoint
            if (req.dca() != null && req.dca().amountPerPeriod() > 0 && dcaEveryMonths > 0) {
                if (m % dcaEveryMonths == 0) {
                    int investsPerPeriod = Math.max(1, req.dca().periods());
                    double investTotalThisEvent = req.dca().amountPerPeriod() * investsPerPeriod;
                    // Allocate proportionally to the original quantity weights when they exist,
                    // otherwise distribute equally across all positions to enable starting from zero units.
                    double totalQtyNow = req.positions().stream().mapToDouble(InstrumentPosition::quantity).sum();
                    boolean allZero = totalQtyNow == 0.0;
                    int n = req.positions().size();
                    for (InstrumentPosition p : req.positions()) {
                        double share = allZero ? (n > 0 ? 1.0 / n : 0.0) : p.quantity() / totalQtyNow;
                        double invest = investTotalThisEvent * share;
                        double price = prices.get(p.isin());
                        if (price > 0 && invest > 0) {
                            double addedUnits = invest / price;
                            unitsHeld.put(p.isin(), unitsHeld.get(p.isin()) + addedUnits);
                            contributed += invest;
                        }
                    }
                }
            }

            // Total value = holdings + side capital (no dividend cash attribution; ACGR already includes dividends)
            double totalValue = totalPortfolioValue(unitsHeld, prices) + req.sideCapital();
            portfolio.add(new TimePoint(m, totalValue, contributed, dividendsPaidCumulative, monthlyDividendsGeneratedTotal));
            for (String isin : perInstrumentPoints.keySet()) {
                double base = unitsHeld.get(isin) * prices.get(isin);
                perInstrumentPoints.get(isin).add(new InstrumentSeriesPoint(m, base));
            }
        }

        List<InstrumentSeries> series = new ArrayList<>();
        for (Map.Entry<String, List<InstrumentSeriesPoint>> e : perInstrumentPoints.entrySet()) {
            Instrument inst = instrumentMap.get(e.getKey());
            series.add(new InstrumentSeries(e.getKey(), inst != null ? inst.getName() : e.getKey(), e.getValue()));
        }

        return new SimulationResponse(portfolio, series);
    }

    private static int frequencyToMonths(String f) {
        // Convert textual frequency to a number of months per checkpoint
        if (f == null) return 0;
        return switch (f.toUpperCase()) {
            case "MONTHLY" -> 1;
            case "QUARTERLY" -> 3;
            case "YEARLY" -> 12;
            default -> 0;
        };
    }

    private static double totalPortfolioValue(Map<String, Double> units, Map<String, Double> prices) {
        // Compute the sum over instruments of (units × price)
        double v = 0.0;
        for (String isin : units.keySet()) {
            v += units.get(isin) * prices.get(isin);
        }
        return v;
    }

    // sumValues retained for potential future use (e.g., when attributing dividend cash)
}


