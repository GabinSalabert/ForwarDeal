package com.example.forwardeal.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public class SimulationDtos {
    // Identifies a holding (by ISIN) and the number of units owned at t=0
    public record InstrumentPosition(
            @NotBlank String isin,
            @PositiveOrZero double quantity
    ) {}

    // Dollar-cost-averaging schedule: add amountPerPeriod, multiple times per frequency window
    public record DcaSchedule(
            @PositiveOrZero double amountPerPeriod,
            @Positive int periods,
            @NotBlank String frequency // MONTHLY, QUARTERLY, YEARLY
    ) {}

    // Primary input for a simulation request
    public record SimulationRequest(
            @NotNull List<InstrumentPosition> positions,
            @PositiveOrZero double initialCapital,
            @PositiveOrZero double sideCapital,
            @Positive int years,
            @Valid DcaSchedule dca,
            @PositiveOrZero double feesAnnualBps // 0-10000 bps
    ) {}

    // Aggregate portfolio point at a given month
    public record TimePoint(
            int monthIndex,
            double totalValue,
            double contributed,
            double dividendsPaid,
            double monthlyDividendsGenerated // includes both distributing (paid) and accumulating (reinvested) dividends
    ) {}

    // Per-instrument value point (units × price) at a given month
    public record InstrumentSeriesPoint(
            int monthIndex,
            double value
    ) {}

    // Describes a per-instrument time series for chart stacking
    public record InstrumentSeries(
            String isin,
            String name,
            List<InstrumentSeriesPoint> points
    ) {}

    // Top-level response: aggregate time series + per-instrument series
    public record SimulationResponse(
            List<TimePoint> portfolio,
            List<InstrumentSeries> instruments
    ) {}
}


