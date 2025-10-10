package com.example.forwardeal.api;

import com.example.forwardeal.api.dto.SimulationDtos.SimulationRequest;
import com.example.forwardeal.api.dto.SimulationDtos.SimulationResponse;
import com.example.forwardeal.service.SimulationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/simulations")
public class SimulationController {
    // Application service that performs the portfolio simulation
    private final SimulationService simulationService;

    public SimulationController(SimulationService simulationService) {
        this.simulationService = simulationService;
    }

    // Accepts a request payload with positions and simulation parameters
    // and returns a computed time series for the aggregate portfolio and each instrument
    @PostMapping
    public SimulationResponse simulate(@Valid @RequestBody SimulationRequest request) {
        return simulationService.simulate(request);
    }
}


