package com.example.forwardeal.marketdata;

import java.util.List;

/**
 * Abstraction for a component that can fetch an initial universe of market instruments
 * along with their key attributes (price, return estimates, dividend policy).
 */
public interface MarketDataProvider {
    /**
     * Returns a list of instruments to populate the in-memory universe at startup.
     * Implementations should be defensive and return reasonable fallbacks if remote calls fail.
     */
    List<MarketInstrumentData> fetchInitialUniverse();
}


