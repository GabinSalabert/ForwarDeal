package com.example.forwardeal.repository;

import com.example.forwardeal.domain.Instrument;
import com.example.forwardeal.marketdata.MarketDataProvider;
import com.example.forwardeal.marketdata.MarketInstrumentData;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class InstrumentRepository {
    // Backing store for instruments keyed by ISIN, populated during application startup
    private final Map<String, Instrument> instrumentsByIsin = new LinkedHashMap<>();

    public InstrumentRepository(MarketDataProvider provider) {
        // Populate from market data provider with defensive fallbacks
        List<MarketInstrumentData> list = new ArrayList<>();
        try {
            list = provider.fetchInitialUniverse();
        } catch (Exception ignored) {}

        if (list.isEmpty()) {
            // Should not happen; provider returns fallback, but keep safety
            return;
        }

        for (MarketInstrumentData d : list) {
            Instrument inst = new Instrument(
                    d.getIsin(),
                    d.getName(),
                    d.getCurrentPrice(),
                    d.getAnnualReturnRate(),
                    d.getDividendYieldAnnual(),
                    d.getDividendPolicy()
            );
            instrumentsByIsin.put(inst.getIsin(), inst);
        }
    }

    // Find a single instrument by ISIN
    public Optional<Instrument> findByIsin(String isin) {
        return Optional.ofNullable(instrumentsByIsin.get(isin));
    }

    // Return all instruments in a stable iteration order
    public List<Instrument> findAll() {
        return new ArrayList<>(instrumentsByIsin.values());
    }
}


