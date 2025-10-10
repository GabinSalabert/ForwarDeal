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
    // Provider used to fetch historical monthly returns to introduce realistic volatility paths
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

        // Map ISIN -> historical monthly return series (approx. last 10 years)
        // Note: Instrument name holds the symbol in our setup
        Map<String, List<Double>> returnsByIsin = new HashMap<>();
        for (Map.Entry<String, Instrument> e : instrumentMap.entrySet()) {
            returnsByIsin.put(e.getKey(), marketProvider.getMonthlyReturnsForSymbol(e.getValue().getName()));
        }

        // Portfolio time series for the aggregate values (total, contributed, dividends)
        List<TimePoint> portfolio = new ArrayList<>(months + 1);

        // Per-instrument time series for stacked/individual charts
        Map<String, List<InstrumentSeriesPoint>> perInstrumentPoints = new LinkedHashMap<>();
        // Units held for each instrument (starting from user-provided quantities)
        Map<String, Double> unitsHeld = new HashMap<>();
        // Current price for each instrument (evolves during the loop)
        Map<String, Double> prices = new HashMap<>();
        // For DISTRIBUTING instruments: track dividends generated within the current year (reset at year-end)
        Map<String, Double> pendingYearDivByIsin = new HashMap<>();
        // Cash attributed back to each instrument at year-end (sum of prior year distributions)
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

        for (int m = 1; m <= months; m++) {
            double monthlyDividendsGeneratedTotal = 0.0;
            // Evolve prices monthly using historical monthly returns to avoid linear paths.
            // We still apply monthly fee factor on top of the realized return.
            for (Map.Entry<String, Instrument> e : instrumentMap.entrySet()) {
                String isin = e.getKey();
                Instrument inst = e.getValue();

                List<Double> series = returnsByIsin.get(isin);
                double histMonthly = series.get((m - 1) % series.size());
                double monthlyFee = Math.pow(1.0 - annualFees, 1.0 / 12.0) - 1.0; // negative

                double newPrice = prices.get(isin) * (1.0 + histMonthly) * (1.0 + monthlyFee);

                // Dividends: distribute annual dividend yield evenly per month for a simple approximation
                double monthlyDividendYield = inst.getDividendYieldAnnual() / 12.0;
                double dividendAmountPerUnit = prices.get(isin) * monthlyDividendYield;
                double monthlyDivForInstrument = unitsHeld.get(isin) * dividendAmountPerUnit;

                if (inst.getDividendPolicy() == DividendPolicy.ACCUMULATING) {
                    // ACCUMULATING: reinvest dividends into additional units at the new price
                    double unitsFromDividend = monthlyDivForInstrument / newPrice;
                    unitsHeld.put(isin, unitsHeld.get(isin) + unitsFromDividend);
                } else {
                    // DISTRIBUTING: track dividends as cash paid out (not reinvested)
                    dividendsPaidCumulative += monthlyDivForInstrument;
                    // accumulate within the current year for this instrument; added to instrument value at year-end
                    pendingYearDivByIsin.put(isin, pendingYearDivByIsin.get(isin) + monthlyDivForInstrument);
                }

                // Track total dividends generated this month regardless of policy
                monthlyDividendsGeneratedTotal += monthlyDivForInstrument;

                prices.put(isin, newPrice);
            }

            // At each year boundary, attribute the year's paid dividends back to each DISTRIBUTING instrument's displayed value
            if (m % 12 == 0) {
                for (Map.Entry<String, Instrument> e : instrumentMap.entrySet()) {
                    String isin = e.getKey();
                    Instrument inst = e.getValue();
                    if (inst.getDividendPolicy() == DividendPolicy.DISTRIBUTING) {
                        double add = pendingYearDivByIsin.getOrDefault(isin, 0.0);
                        if (add != 0.0) {
                            attributedDivCashByIsin.put(isin, attributedDivCashByIsin.get(isin) + add);
                            pendingYearDivByIsin.put(isin, 0.0);
                        }
                    }
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

            // Total value = holdings + side capital + attributed dividend cash (added yearly to each distributing instrument)
            double totalValue = totalPortfolioValue(unitsHeld, prices) + req.sideCapital() + sumValues(attributedDivCashByIsin);
            portfolio.add(new TimePoint(m, totalValue, contributed, dividendsPaidCumulative, monthlyDividendsGeneratedTotal));
            for (String isin : perInstrumentPoints.keySet()) {
                double base = unitsHeld.get(isin) * prices.get(isin);
                double withAttributedDiv = base + attributedDivCashByIsin.getOrDefault(isin, 0.0);
                perInstrumentPoints.get(isin).add(new InstrumentSeriesPoint(m, withAttributedDiv));
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

    private static double sumValues(Map<String, Double> map) {
        double s = 0.0;
        for (double v : map.values()) s += v;
        return s;
    }
}


