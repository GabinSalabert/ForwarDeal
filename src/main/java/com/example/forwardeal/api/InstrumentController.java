package com.example.forwardeal.api;

import com.example.forwardeal.domain.Instrument;
import com.example.forwardeal.marketdata.YahooFinanceProvider;
import com.example.forwardeal.repository.InstrumentRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/instruments")
public class InstrumentController {
    // Read-only repository serving the current in-memory universe of instruments
    private final InstrumentRepository repository;
    private final YahooFinanceProvider marketProvider;

    public InstrumentController(InstrumentRepository repository, YahooFinanceProvider marketProvider) {
        this.repository = repository;
        this.marketProvider = marketProvider;
    }

    // Returns the full list of instruments available for selection on the frontend
    @GetMapping
    public List<Instrument> list() {
        return repository.findAll();
    }

    // Returns the EUR/USD exchange rate for currency conversion
    @GetMapping("/fx-rate")
    public Map<String, Double> getFxRate() {
        double rate = marketProvider.getEurToUsdRate();
        return Map.of("eurToUsd", rate, "usdToEur", 1.0 / rate);
    }
}


