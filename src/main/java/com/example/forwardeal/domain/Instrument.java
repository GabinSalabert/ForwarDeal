package com.example.forwardeal.domain;

/**
 * Immutable domain object representing a tradable instrument identified by ISIN.
 *
 * Each instrument carries:
 * - a human-readable name (as supplied by the data provider),
 * - a current market price (used to value existing holdings at t=0),
 * - an estimated total annual return rate (used to evolve price in the simulator),
 * - an annual dividend yield (portion of total return paid out as dividends), and
 * - a dividend policy (ACCUMULATING or DISTRIBUTING) guiding dividend treatment.
 */
public class Instrument {
    // International Securities Identification Number (unique id across markets)
    private final String isin;
    // Provider-supplied display name (ticker-like string for simplicity)
    private final String name;
    // Latest known market price used to value current holdings at simulation start
    private final double currentPrice; // latest market price in quote currency
    // Estimated long-run ACGR (10-year Compound Annual Growth Rate) as decimal (e.g., 0.10 for 10%)
    private final double totalAnnualReturnRate; // kept for backward-compat; represents ACGR10
    // Expected annual dividend yield (e.g., 0.02 for 2%); split equally across months in simulator
    private final double dividendYieldAnnual;   // portion of total return paid as dividends, e.g., 0.02
    // Policy: ACCUMULATING => reinvest dividends; DISTRIBUTING => pay cash out
    private final DividendPolicy dividendPolicy;

    /**
     * Construct a fully-initialized instrument. All fields are required and immutable.
     */
    public Instrument(String isin, String name, double currentPrice, double totalAnnualReturnRate, double dividendYieldAnnual, DividendPolicy dividendPolicy) {
        this.isin = isin;
        this.name = name;
        this.currentPrice = currentPrice;
        this.totalAnnualReturnRate = totalAnnualReturnRate;
        this.dividendYieldAnnual = dividendYieldAnnual;
        this.dividendPolicy = dividendPolicy;
    }

    /** @return unique ISIN identifier */
    public String getIsin() {
        return isin;
    }

    /** @return display name */
    public String getName() {
        return name;
    }

    /** @return latest market price used for initial valuation */
    public double getCurrentPrice() {
        return currentPrice;
    }

    /** @return estimated total annual return rate (as decimal) — ACGR10 */
    public double getTotalAnnualReturnRate() {
        return totalAnnualReturnRate;
    }

    /** @return ACGR (10-year compound annual growth rate) as decimal, alias for totalAnnualReturnRate */
    public double getAcgr10() {
        return totalAnnualReturnRate;
    }

    /** @return expected annual dividend yield (as decimal) */
    public double getDividendYieldAnnual() {
        return dividendYieldAnnual;
    }

    /** @return dividend policy determining how dividends are handled in the simulation */
    public DividendPolicy getDividendPolicy() {
        return dividendPolicy;
    }
}


