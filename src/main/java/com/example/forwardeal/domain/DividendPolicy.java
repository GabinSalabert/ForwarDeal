package com.example.forwardeal.domain;

/**
 * Indicates how dividends are treated by a given instrument.
 * ACCUMULATING: dividends are automatically reinvested into more units.
 * DISTRIBUTING: dividends are paid out as cash (tracked, not reinvested).
 */
public enum DividendPolicy {
    ACCUMULATING, // reinvest dividends
    DISTRIBUTING  // pay out dividends, not reinvested
}


