package com.example.forwardeal.api;

import com.example.forwardeal.domain.Instrument;
import com.example.forwardeal.repository.InstrumentRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/instruments")
public class InstrumentController {
    // Read-only repository serving the current in-memory universe of instruments
    private final InstrumentRepository repository;

    public InstrumentController(InstrumentRepository repository) {
        this.repository = repository;
    }

    // Returns the full list of instruments available for selection on the frontend
    @GetMapping
    public List<Instrument> list() {
        return repository.findAll();
    }
}


