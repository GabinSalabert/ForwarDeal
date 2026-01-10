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

        // Get EUR/USD exchange rate (USD per EUR, e.g., 1.10 means 1 EUR = 1.10 USD)
        // All user inputs are in EUR, but prices are in USD, so we need to convert
        double eurToUsd = marketProvider.getEurToUsdRate();
        double usdToEur = 1.0 / eurToUsd; // For converting USD values back to EUR for display

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

        // Apply FIRST DCA investment at month 0 (January of year 1) if DCA is enabled
        // This ensures the user starts investing from day 1, not from the end of month 1
        if (req.dca() != null && req.dca().amountPerPeriod() > 0) {
            int investsPerPeriod = Math.max(1, req.dca().periods());
            double investTotalEur = req.dca().amountPerPeriod() * investsPerPeriod;
            double investTotalUsd = investTotalEur * eurToUsd;
            
            double totalQtyNow = req.positions().stream().mapToDouble(InstrumentPosition::quantity).sum();
            boolean allZero = totalQtyNow == 0.0;
            int n = req.positions().size();
            
            for (InstrumentPosition p : req.positions()) {
                double share = allZero ? (n > 0 ? 1.0 / n : 0.0) : p.quantity() / totalQtyNow;
                double investUsd = investTotalUsd * share;
                double price = prices.get(p.isin());
                if (price > 0 && investUsd > 0) {
                    double addedUnits = investUsd / price;
                    unitsHeld.put(p.isin(), unitsHeld.get(p.isin()) + addedUnits);
                    contributed += investTotalEur * share;
                }
            }
        }
        
        // Add starting point (month 0) - now includes the first DCA investment
        // total = market value of basket + side capital (cash on the side)
        // All prices are in USD, so portfolio value is in USD. We convert to EUR for display.
        // Note: initialCapital from frontend is actually in USD (calculated from USD prices),
        // but sideCapital is entered by user and is in EUR, so we convert it.
        double startValueUsd = totalPortfolioValue(unitsHeld, prices) + (req.sideCapital() * eurToUsd);
        double startValueEur = startValueUsd * usdToEur;
        // initialCapital is actually in USD from frontend calculation, convert to EUR for tracking
        double initialCapitalUsd = req.initialCapital();
        double initialCapitalEur = initialCapitalUsd * usdToEur;
        // contributed already includes initial DCA from above
        portfolio.add(new TimePoint(0, startValueEur, contributed, dividendsPaidCumulative * usdToEur, 0.0));
        for (String isin : perInstrumentPoints.keySet()) {
            double valueUsd = unitsHeld.get(isin) * prices.get(isin);
            double valueEur = valueUsd * usdToEur;
            perInstrumentPoints.get(isin).add(new InstrumentSeriesPoint(0, valueEur));
        }

        // Generate realistic monthly returns with volatility that average to ACGR over the year
        // Each YEAR gets a DIFFERENT pattern, but the product of 12 months = ACGR (respected)
        // Use a seeded random for reproducibility, but vary patterns by year
        Random random = new Random(42); // Fixed seed for reproducible simulations
        
        // Pre-generate patterns for each year and each instrument
        int totalYears = (months / 12) + 1;
        Map<String, double[][]> yearlyPatternsPerInstrument = new HashMap<>();
        
        for (Map.Entry<String, Instrument> e : instrumentMap.entrySet()) {
            Instrument inst = e.getValue();
            double annualReturn = inst.getAcgr10(); // e.g., 0.10 for 10%
            
            // Create a DIFFERENT pattern for each year
            double[][] patternsForAllYears = new double[totalYears][12];
            for (int y = 0; y < totalYears; y++) {
                patternsForAllYears[y] = generateVolatileYearPattern(annualReturn, random);
            }
            yearlyPatternsPerInstrument.put(e.getKey(), patternsForAllYears);
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
            
            // First, evolve prices monthly using volatile monthly returns + fees.
            // This applies to all units held at the start of the month (from previous months).
            for (Map.Entry<String, Instrument> e : instrumentMap.entrySet()) {
                String isin = e.getKey();
                Instrument inst = e.getValue();

                double monthlyFee = Math.pow(1.0 - annualFees, 1.0 / 12.0) - 1.0; // negative
                // Get volatile monthly return from the pattern for THIS SPECIFIC YEAR
                double[][] allYearPatterns = yearlyPatternsPerInstrument.get(isin);
                int yearIndex = (m - 1) / 12; // 0 for year 1, 1 for year 2, etc.
                int monthInYear = (m - 1) % 12; // 0-11 index into pattern
                double monthlyNominal = allYearPatterns[yearIndex][monthInYear];
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
            
            // Then, apply DCA at the END of the month (after returns are applied).
            // New investments purchased this month will benefit from returns starting next month.
            // Interpretation: 'periods' = number of individual contributions per frequency window
            // Example: MONTHLY + periods=2 + amount=100 => invest 200 at each end-of-month checkpoint
            if (req.dca() != null && req.dca().amountPerPeriod() > 0 && dcaEveryMonths > 0) {
                if (m % dcaEveryMonths == 0) {
                    int investsPerPeriod = Math.max(1, req.dca().periods());
                    // User inputs are in EUR, convert to USD for purchasing shares (prices are in USD)
                    double investTotalEur = req.dca().amountPerPeriod() * investsPerPeriod;
                    double investTotalUsd = investTotalEur * eurToUsd;
                    // Allocate proportionally to the original quantity weights when they exist,
                    // otherwise distribute equally across all positions to enable starting from zero units.
                    double totalQtyNow = req.positions().stream().mapToDouble(InstrumentPosition::quantity).sum();
                    boolean allZero = totalQtyNow == 0.0;
                    int n = req.positions().size();
                    for (InstrumentPosition p : req.positions()) {
                        double share = allZero ? (n > 0 ? 1.0 / n : 0.0) : p.quantity() / totalQtyNow;
                        double investUsd = investTotalUsd * share; // Investment amount in USD
                        double price = prices.get(p.isin()); // Price is in USD
                        if (price > 0 && investUsd > 0) {
                            double addedUnits = investUsd / price;
                            unitsHeld.put(p.isin(), unitsHeld.get(p.isin()) + addedUnits);
                            // Track contributed in EUR for display consistency
                            contributed += investTotalEur * share;
                        }
                    }
                }
            }

            // Year boundary: reset yearly tracking (bars only). We do not attribute to value to avoid double counting.
            if (m % 12 == 0) {
                for (String isin : pendingYearDivByIsin.keySet()) {
                    pendingYearDivByIsin.put(isin, 0.0);
                }
            }

            // Total value = holdings + side capital (no dividend cash attribution; ACGR already includes dividends)
            // All internal calculations are in USD, but we convert to EUR for display
            double totalValueUsd = totalPortfolioValue(unitsHeld, prices) + (req.sideCapital() * eurToUsd);
            double totalValueEur = totalValueUsd * usdToEur;
            portfolio.add(new TimePoint(m, totalValueEur, contributed, dividendsPaidCumulative * usdToEur, monthlyDividendsGeneratedTotal * usdToEur));
            for (String isin : perInstrumentPoints.keySet()) {
                double baseUsd = unitsHeld.get(isin) * prices.get(isin);
                double baseEur = baseUsd * usdToEur;
                perInstrumentPoints.get(isin).add(new InstrumentSeriesPoint(m, baseEur));
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
    
    /**
     * Generates a 12-month pattern of returns that:
     * 1. Compounds to match the target annual return (ACGR)
     * 2. Includes a RANDOM number of negative months (2-5) for realism
     * 3. Has realistic volatility
     */
    private static double[] generateVolatileYearPattern(double annualReturn, Random random) {
        double[] pattern = new double[12];
        
        // Randomly decide how many negative months this year (2-5)
        int numNegativeMonths = 2 + random.nextInt(4); // 2, 3, 4, or 5
        
        // Decide which months will be negative (random positions)
        boolean[] isNegative = new boolean[12];
        int negativeCount = 0;
        while (negativeCount < numNegativeMonths) {
            int idx = random.nextInt(11); // Don't set last month as we need it for adjustment
            if (!isNegative[idx]) {
                isNegative[idx] = true;
                negativeCount++;
            }
        }
        
        // Base monthly return to achieve annual target
        double baseMonthly = Math.pow(1.0 + annualReturn, 1.0 / 12.0) - 1.0;
        
        // Typical monthly volatility for equity markets is around 4-5%
        double volatility = 0.04;
        
        // Generate returns: positive months get positive returns, negative months get negative
        double productSoFar = 1.0;
        for (int i = 0; i < 11; i++) {
            double noise = Math.abs(random.nextGaussian() * volatility);
            if (isNegative[i]) {
                // Negative month: between -1% and -6%
                pattern[i] = -(0.01 + random.nextDouble() * 0.05);
            } else {
                // Positive month: base + some noise
                pattern[i] = baseMonthly + noise;
            }
            productSoFar *= (1.0 + pattern[i]);
        }
        
        // Set the last month to make the product equal to (1 + annualReturn)
        double targetProduct = 1.0 + annualReturn;
        pattern[11] = (targetProduct / productSoFar) - 1.0;
        
        // Shuffle to mix positions further
        shuffleArray(pattern, random);
        
        return pattern;
    }
    
    private static void shuffleArray(double[] array, Random random) {
        for (int i = array.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            double temp = array[i];
            array[i] = array[j];
            array[j] = temp;
        }
    }
}


