package com.example.forwardeal.marketdata;

import com.example.forwardeal.domain.DividendPolicy;

/**
 * Raw market data snapshot for a single instrument, as produced by a provider.
 * This is an intermediate structure used at startup to build domain {@code Instrument}s.
 */
public class MarketInstrumentData {
    // Unique ISIN for the instrument
    private final String isin;
    // Provider display name (may be a symbol)
    private final String name;
    // Provider symbol (ticker)
    private final String symbol;
    // Latest price in quote currency
    private final double currentPrice;
    // 10-year Compound Annual Growth Rate (ACGR) estimate
    private final double annualReturnRate; // ACGR10
    // Estimated annual dividend yield (as decimal)
    private final double dividendYieldAnnual;
    // Dividend policy inferred from yield or known fund/share class policy
    private final DividendPolicy dividendPolicy;

    public MarketInstrumentData(String isin,
                                String name,
                                String symbol,
                                double currentPrice,
                                double annualReturnRate,
                                double dividendYieldAnnual,
                                DividendPolicy dividendPolicy) {
        this.isin = isin;
        this.name = name;
        this.symbol = symbol;
        this.currentPrice = currentPrice;
        this.annualReturnRate = annualReturnRate;
        this.dividendYieldAnnual = dividendYieldAnnual;
        this.dividendPolicy = dividendPolicy;
    }

    public String getIsin() { return isin; }
    public String getName() { return name; }
    public String getSymbol() { return symbol; }
    public double getCurrentPrice() { return currentPrice; }
    public double getAnnualReturnRate() { return annualReturnRate; }
    public double getAcgr10() { return annualReturnRate; }
    public double getDividendYieldAnnual() { return dividendYieldAnnual; }
    public DividendPolicy getDividendPolicy() { return dividendPolicy; }
}


