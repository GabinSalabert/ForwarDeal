package com.example.forwardeal.marketdata;

import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility to load a curated list of symbols from classpath CSV files.
 * CSV format: isin,symbol,name
 * - Header is optional
 * - Lines starting with '#' are ignored
 * - If ISIN is blank, the loader will substitute the symbol as the identifier
 */
public final class UniverseListLoader {

    public record SymbolItem(String isin, String symbol, String name, Double acgrHint) {}

    public static List<SymbolItem> load(String resourcePath) {
        List<SymbolItem> items = new ArrayList<>();
        try {
            ClassPathResource res = new ClassPathResource(resourcePath);
            try (BufferedReader br = new BufferedReader(new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                boolean first = true;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    // Optional header: detect common header names and skip first row if present
                    if (first && (line.toLowerCase().startsWith("isin,") || line.toLowerCase().startsWith("symbol,"))) {
                        first = false;
                        continue;
                    }
                    first = false;
                    String[] parts = line.split(",");
                    if (parts.length < 2) continue;
                    String isin = parts[0].trim();
                    String symbol = parts[1].trim();
                    String name = parts.length >= 3 ? parts[2].trim() : symbol;
                    Double acgrHint = null;
                    if (parts.length >= 4) {
                        try {
                            String v = parts[3].trim();
                            if (!v.isEmpty()) acgrHint = Double.parseDouble(v);
                        } catch (Exception ignored) {}
                    }
                    if (isin.isEmpty()) isin = symbol; // fallback: use symbol as identifier when ISIN not provided
                    if (!symbol.isEmpty()) {
                        items.add(new SymbolItem(isin, symbol, name.isEmpty() ? symbol : name, acgrHint));
                    }
                }
            }
        } catch (Exception ignored) {
            // On any error, return what we collected (possibly empty) to avoid crashing startup
        }
        return items;
    }
}


