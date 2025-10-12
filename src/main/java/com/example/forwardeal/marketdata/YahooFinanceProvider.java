package com.example.forwardeal.marketdata;

import com.example.forwardeal.domain.DividendPolicy;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Component
public class YahooFinanceProvider implements MarketDataProvider {
    // HTTP client configured with realistic headers to reduce chances of being blocked
    private final RestClient http = RestClient.builder()
            .defaultHeader(HttpHeaders.USER_AGENT, "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
            .defaultHeader(HttpHeaders.ACCEPT, "application/json")
            .build();

    // Cache of monthly returns per symbol (most recent ~10 years)
    private final Map<String, List<Double>> monthlyReturnsCache = new ConcurrentHashMap<>();
    // Cache for FX rates to USD by currency code (e.g., EUR -> EURUSD=X)
    private final Map<String, Double> fxToUsdCache = new ConcurrentHashMap<>();
    // Cache for prices during a universe build to reduce duplicate lookups when symbols repeat
    private final Map<String, Double> priceCache = new ConcurrentHashMap<>();

    // CSV-driven universes for deterministic membership; contents curated under src/main/resources/universe
    private static final String CSV_US = "universe/nasdaq_top.csv";
    private static final String CSV_PEA = "universe/pea_eligible.csv";

    @Override
    public List<MarketInstrumentData> fetchInitialUniverse() {
        // Load curated symbols from CSVs, preserving file order for a stable UI
        List<UniverseListLoader.SymbolItem> us = UniverseListLoader.load(CSV_US);
        List<UniverseListLoader.SymbolItem> pea = UniverseListLoader.load(CSV_PEA);
        List<UniverseListLoader.SymbolItem> all = new ArrayList<>(us.size() + pea.size());
        all.addAll(us);
        all.addAll(pea);

        // Limit concurrency to reduce API rate-limit issues and improve reliability
        int poolSize = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        try {
            List<Callable<MarketInstrumentData>> tasks = new ArrayList<>(all.size());
            for (UniverseListLoader.SymbolItem item : all) {
                tasks.add(() -> {
                    try {
                        return fetchFor(item.isin(), item.symbol(), item.name(), item.acgrHint());
                    } catch (Exception ignored) {
                        double est = estimateTenYearCagr(item.symbol());
                        if (item.acgrHint() != null && item.acgrHint() > -0.5 && item.acgrHint() < 1.0) {
                            est = item.acgrHint();
                        }
                        double yield = defaultYieldForSymbol(item.symbol());
                        DividendPolicy policy = yield > 0.0001 ? DividendPolicy.DISTRIBUTING : DividendPolicy.ACCUMULATING;
                        double er = defaultExpenseRatio(item.symbol(), item.name());
                        return new MarketInstrumentData(item.isin(), item.name(), item.symbol(), 1.0, est, yield, er, policy);
                    }
                });
            }
            List<Future<MarketInstrumentData>> futures = pool.invokeAll(tasks);
            List<MarketInstrumentData> ordered = new ArrayList<>(futures.size());
            for (Future<MarketInstrumentData> f : futures) {
                try {
                    ordered.add(f.get());
                } catch (ExecutionException e) {
                    // Should not happen due to try/catch in task
                }
            }
            return ordered;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        } finally {
            pool.shutdownNow();
        }
    }

    private MarketInstrumentData fetchFor(String isin, String symbol, String displayName, Double acgrHint) {
        // Current price and ACGR10 (10-year CAGR)
        double price = fetchCurrentPrice(symbol);
        double tenYearCagr = computeTenYearCagrFromMonthly(symbol);
        if (!Double.isFinite(tenYearCagr)) tenYearCagr = estimateTenYearCagr(symbol);
        // Use curated hint when online data is missing, clearly default-like, or implausible
        if (acgrHint != null && acgrHint > -0.5 && acgrHint < 1.0) {
            boolean defaultLike = Math.abs(tenYearCagr - 0.06) < 1e-6;
            boolean implausible = !Double.isFinite(tenYearCagr) || tenYearCagr < -0.2 || tenYearCagr > 0.25;
            boolean farOff = Double.isFinite(tenYearCagr) && Math.abs(tenYearCagr - acgrHint) > 0.20;
            if (defaultLike || implausible || farOff) {
                tenYearCagr = acgrHint;
            }
        }
        // Final clamp to conservative bounds
        if (Double.isFinite(tenYearCagr)) {
            if (tenYearCagr < -0.5) tenYearCagr = -0.5;
            if (tenYearCagr > 0.4) tenYearCagr = 0.4; // cap at 40% to avoid outliers like 100%
        } else {
            tenYearCagr = (acgrHint != null) ? acgrHint : 0.06;
        }
        double dividendYield;
        try {
            // Fetch current dividend yield when available
            dividendYield = fetchDividendYield(symbol);
        } catch (Exception ignored) {
            dividendYield = defaultYieldForSymbol(symbol);
        }
        double expenseRatio = fetchExpenseRatio(symbol);
        if (!Double.isFinite(expenseRatio) || expenseRatio < 0) {
            expenseRatio = defaultExpenseRatio(symbol, displayName);
        }
        DividendPolicy policy = dividendYield > 0.0001 ? DividendPolicy.DISTRIBUTING : DividendPolicy.ACCUMULATING;
        String name = (displayName == null || displayName.isBlank()) ? symbol : displayName;
        return new MarketInstrumentData(isin, name, symbol, price, tenYearCagr, dividendYield, expenseRatio, policy);
    }

    private String fetchDisplayName(String symbol) {
        try {
            String url = "https://query1.finance.yahoo.com/v7/finance/quote?symbols=" + symbol;
            var resp = http.get().uri(url).retrieve().body(Map.class);
            Map<?,?> result = ((List<Map<?,?>>)((Map<?,?>)resp.get("quoteResponse")).get("result")).get(0);
            Object longName = result.get("longName");
            if (longName instanceof String s && !s.isBlank()) return s;
            Object shortName = result.get("shortName");
            if (shortName instanceof String s2 && !s2.isBlank()) return s2;
        } catch (Exception ignored) {}
        return symbol;
    }

    private static final class ScoredSymbol {
        final String symbol;
        final double cagr;
        ScoredSymbol(String symbol, double cagr) { this.symbol = symbol; this.cagr = cagr; }
    }

    /**
     * Compute ACGR (approx 10-year CAGR) from monthly returns using log-returns for numerical stability.
     * - Use up to the last 120 months when available (>= 12 months required)
     * - Annualize via exp(meanMonthlyLog * 12) - 1
     */
    private double computeTenYearCagrFromMonthly(String symbol) {
        try {
            List<Double> r = getMonthlyReturnsForSymbol(symbol);
            if (r == null) return Double.NaN;
            int n = Math.min(120, r.size());
            if (n < 12) return Double.NaN;
            // If the series looks like the synthetic 6% flat default, prefer to fallback estimation
            if (looksLikeDefaultMonthly(r)) return Double.NaN;
            double sumLog = 0.0;
            int count = 0;
            for (int i = r.size() - n; i < r.size(); i++) {
                double rm = r.get(i);
                // Guard against pathological data; clip monthly return to [-80%, +80%]
                if (rm < -0.8) rm = -0.8; else if (rm > 0.8) rm = 0.8;
                double onePlus = 1.0 + rm;
                if (onePlus <= 0.0) continue;
                sumLog += Math.log(onePlus);
                count++;
            }
            if (count < 12) return Double.NaN;
            double meanMonthlyLog = sumLog / count;
            return Math.exp(meanMonthlyLog * 12.0) - 1.0;
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private boolean looksLikeDefaultMonthly(List<Double> returns) {
        if (returns.isEmpty()) return true;
        // default monthly used in fallback
        double m = Math.pow(1.06, 1.0 / 12.0) - 1.0;
        double eps = 1e-6;
        double mean = 0.0;
        for (double v : returns) mean += v;
        mean /= returns.size();
        // if all points are very close to a constant (especially ~m), flag as default-like
        double var = 0.0;
        for (double v : returns) {
            double d = v - mean;
            var += d * d;
            if (Math.abs(v - m) > 0.1) {
                // Allow big deviations to quickly escape default detection
            }
        }
        var /= returns.size();
        boolean nearConst = var < eps;
        boolean nearDefault = Math.abs(mean - m) < 1e-5;
        return nearConst && nearDefault;
    }

    private double fetchCurrentPrice(String symbol) {
        // Cached price within this run
        Double cached = priceCache.get(symbol);
        if (cached != null && cached > 0) return cached;

        // 1) Stooq first for plain US tickers (robust, USD by construction)
        Double stooqFirst = fetchPriceFromStooq(symbol);
        if (stooqFirst != null && stooqFirst > 0) { priceCache.put(symbol, stooqFirst); return stooqFirst; }

        // 2) Yahoo quote with currency → convert to USD if needed
        try {
            String url = "https://query1.finance.yahoo.com/v7/finance/quote?symbols=" + symbol;
            var resp = http.get().uri(url).retrieve().body(Map.class);
            Map<?,?> result = ((List<Map<?,?>>)((Map<?,?>)resp.get("quoteResponse")).get("result")).get(0);
            String currency = null;
            Object curObj = result.get("currency");
            if (curObj instanceof String s) currency = s;
            Object p = result.get("regularMarketPrice");
            if (p instanceof Number n) { double v = ensureUsd(n.doubleValue(), currency); priceCache.put(symbol, v); return v; }
            Object prev = result.get("regularMarketPreviousClose");
            if (prev instanceof Number n2) { double v = ensureUsd(n2.doubleValue(), currency); priceCache.put(symbol, v); return v; }
            Object post = result.get("postMarketPrice");
            if (post instanceof Number n3) { double v = ensureUsd(n3.doubleValue(), currency); priceCache.put(symbol, v); return v; }
        } catch (Exception ignored) {}

        // 3) Chart adjusted close with meta.currency → convert to USD
        Double adjUsd = fetchLastAdjCloseUsdFromChart(symbol);
        if (adjUsd != null && adjUsd > 0) { priceCache.put(symbol, adjUsd); return adjUsd; }

        // 4) Last resort
        return 1.0;
    }

    private Double fetchLastAdjCloseUsdFromChart(String symbol) {
        // Pull last 30 days of daily candles and choose the last non-null adjusted close, convert to USD
        long now = Instant.now().getEpochSecond();
        long thirtyDaysAgo = now - 30L * 24L * 3600L;
        String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + symbol + "?period1=" + thirtyDaysAgo + "&period2=" + now + "&interval=1d";
        try {
            var resp = http.get().uri(url).retrieve().body(Map.class);
            Map<?,?> chart = (Map<?,?>) resp.get("chart");
            List<Map<?,?>> results = (List<Map<?,?>>) chart.get("result");
            Map<?,?> first = results.get(0);
            Map<?,?> meta = (Map<?,?>) first.get("meta");
            String currency = null;
            if (meta != null) {
                Object cur = meta.get("currency");
                if (cur instanceof String s) currency = s;
            }
            Map<?,?> indicators = (Map<?,?>) first.get("indicators");
            List<?> closes;
            if (indicators.containsKey("adjclose")) {
                List<Map<?,?>> adjclose = (List<Map<?,?>>) indicators.get("adjclose");
                closes = (List<?>) adjclose.get(0).get("adjclose");
            } else {
                List<Map<?,?>> quote = (List<Map<?,?>>) indicators.get("quote");
                closes = (List<?>) quote.get(0).get("close");
            }
            for (int i = closes.size() - 1; i >= 0; i--) {
                Object v = closes.get(i);
                if (v instanceof Number n) return ensureUsd(n.doubleValue(), currency);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private double ensureUsd(double price, String currency) {
        if (price <= 0) return price;
        if (currency == null || currency.equalsIgnoreCase("USD")) return price;
        double fx = fetchFxToUsdRate(currency);
        if (!Double.isFinite(fx) || fx <= 0) return price; // fallback: assume already USD
        return price * fx;
    }

    private double fetchFxToUsdRate(String currency) {
        try {
            return fxToUsdCache.computeIfAbsent(currency.toUpperCase(Locale.ROOT), cur -> {
                try {
                    String pair;
                    switch (cur) {
                        case "USD": return 1.0;
                        case "EUR": pair = "EURUSD=X"; break;
                        case "GBP": pair = "GBPUSD=X"; break;
                        case "CHF": pair = "CHFUSD=X"; break;
                        case "CAD": pair = "CADUSD=X"; break;
                        case "JPY": pair = "JPYUSD=X"; break;
                        case "SEK": pair = "SEKUSD=X"; break;
                        case "NOK": pair = "NOKUSD=X"; break;
                        case "DKK": pair = "DKKUSD=X"; break;
                        default: return Double.NaN;
                    }
                    String url = "https://query1.finance.yahoo.com/v7/finance/quote?symbols=" + pair;
                    var resp = http.get().uri(url).retrieve().body(Map.class);
                    Map<?,?> result = ((List<Map<?,?>>)((Map<?,?>)resp.get("quoteResponse")).get("result")).get(0);
                    Object p = result.get("regularMarketPrice");
                    if (p instanceof Number n) return n.doubleValue();
                } catch (Exception ignored) {}
                return Double.NaN;
            });
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    // Public accessor used by the simulator to introduce realistic volatility
    public List<Double> getMonthlyReturnsForSymbol(String symbol) {
        if (symbol == null || symbol.isEmpty()) return defaultFlatMonthlyReturns();
        return monthlyReturnsCache.computeIfAbsent(symbol, s -> {
            List<Double> r = fetchMonthlyReturnsSeries(s);
            if (r.size() >= 12) return r;
            // Fallback: attempt via Yahoo spark API
            List<Double> spark = fetchMonthlyReturnsFromSpark(s);
            if (spark.size() >= 12) return spark;
            // Fallback 2: Stooq monthly for US tickers
            List<Double> stooq = fetchMonthlyReturnsFromStooq(s);
            if (stooq.size() >= 12) return stooq;
            return defaultFlatMonthlyReturns();
        });
    }

    private List<Double> fetchMonthlyReturnsSeries(String symbol) {
        try {
            long now = Instant.now().getEpochSecond();
            long tenYearsAgo = now - (long) (10 * 365.25 * 24 * 3600);
            String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + symbol + "?period1=" + tenYearsAgo + "&period2=" + now + "&interval=1mo";
            var resp = http.get().uri(url).retrieve().body(Map.class);
            Map<?,?> chart = (Map<?,?>) resp.get("chart");
            List<Map<?,?>> results = (List<Map<?,?>>) chart.get("result");
            Map<?,?> first = results.get(0);
            Map<?,?> indicators = (Map<?,?>) first.get("indicators");
            List<?> closes;
            if (indicators.containsKey("adjclose")) {
                List<Map<?,?>> adjclose = (List<Map<?,?>>) indicators.get("adjclose");
                closes = (List<?>) adjclose.get(0).get("adjclose");
            } else {
                List<Map<?,?>> quote = (List<Map<?,?>>) indicators.get("quote");
                closes = (List<?>) quote.get(0).get("close");
            }
            List<Double> returns = new ArrayList<>();
            Double prev = null;
            for (Object o : closes) {
                Double c = (o instanceof Number n) ? n.doubleValue() : null;
                if (c != null && c > 0) {
                    if (prev != null && prev > 0) {
                        returns.add(c / prev - 1.0);
                    }
                    prev = c;
                }
            }
            if (returns.size() >= 12) return returns;
        } catch (Exception ignored) {}
        return defaultFlatMonthlyReturns();
    }

    private List<Double> fetchMonthlyReturnsFromSpark(String symbol) {
        try {
            String url = "https://query1.finance.yahoo.com/v8/finance/spark?symbols=" + symbol + "&range=10y&interval=1mo";
            var resp = http.get().uri(url).retrieve().body(Map.class);
            List<Map<?,?>> result = (List<Map<?,?>>) ((Map<?,?>) resp.get("spark")) .get("result");
            Map<?,?> first = result.get(0);
            Map<?,?> response = (Map<?,?>) first.get("response");
            // Some responses nest differently; try 'response'[0]['indicators']['quote'][0]['close']
            List<?> respArr = (List<?>) first.get("response");
            if (respArr != null && !respArr.isEmpty()) {
                Map<?,?> r0 = (Map<?,?>) respArr.get(0);
                Map<?,?> indicators = (Map<?,?>) r0.get("indicators");
                if (indicators != null) {
                    List<Map<?,?>> quote = (List<Map<?,?>>) indicators.get("quote");
                    if (quote != null && !quote.isEmpty()) {
                        List<?> closes = (List<?>) quote.get(0).get("close");
                        List<Double> returns = new ArrayList<>();
                        Double prev = null;
                        for (Object o : closes) {
                            Double c = (o instanceof Number n) ? n.doubleValue() : null;
                            if (c != null && c > 0) {
                                if (prev != null && prev > 0) returns.add(c / prev - 1.0);
                                prev = c;
                            }
                        }
                        return returns;
                    }
                }
            }
        } catch (Exception ignored) {}
        return Collections.emptyList();
    }

    private List<Double> fetchMonthlyReturnsFromStooq(String symbol) {
        String s = toStooqSymbol(symbol);
        if (s == null) return Collections.emptyList();
        String url = "https://stooq.com/q/d/l/?s=" + s + "&i=m";
        try {
            String body = http.get().uri(url).retrieve().body(String.class);
            if (body == null) return Collections.emptyList();
            String[] lines = body.trim().split("\n");
            double prev = -1.0;
            List<Double> returns = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) { // skip header
                String[] parts = lines[i].split(",");
                if (parts.length < 6) continue;
                String closeStr = parts[5].trim();
                if (closeStr.equalsIgnoreCase("N/D")) continue;
                double c = Double.parseDouble(closeStr);
                if (c > 0) {
                    if (prev > 0) returns.add(c / prev - 1.0);
                    prev = c;
                }
            }
            return returns;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private List<Double> defaultFlatMonthlyReturns() {
        // Fallback: 10y of flat monthly returns from 6% annualized
        double m = Math.pow(1.06, 1.0 / 12.0) - 1.0;
        List<Double> r = new ArrayList<>();
        for (int i = 0; i < 120; i++) r.add(m);
        return r;
    }

    private Double fetchPriceFromStooq(String symbol) {
        // Simple CSV endpoint: we parse the second line and extract the Close column
        String s = toStooqSymbol(symbol);
        if (s == null) return null;
        String url = "https://stooq.com/q/l/?s=" + s + "&f=sd2t2ohlcv&h&e=csv";
        try {
            String body = http.get().uri(url).retrieve().body(String.class);
            if (body == null) return null;
            String[] lines = body.trim().split("\n");
            if (lines.length < 2) return null;
            String[] parts = lines[1].split(",");
            if (parts.length < 7) return null;
            String closeStr = parts[6].trim();
            if (closeStr.equalsIgnoreCase("N/D")) return null;
            return Double.parseDouble(closeStr);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String toStooqSymbol(String symbol) {
        // Basic mapping: plain US tickers → ticker.us (e.g., AAPL → aapl.us). Return null if non-US or contains venue suffixes.
        if (symbol != null && symbol.matches("^[A-Z]{1,5}$")) {
            return symbol.toLowerCase(Locale.ROOT) + ".us";
        }
        return null;
    }

    private double estimateTenYearCagr(String symbol) {
        // Try Yahoo chart monthly
        try {
            long now = Instant.now().getEpochSecond();
            long tenYearsAgo = now - (long) (10 * 365.25 * 24 * 3600);
            String url = "https://query1.finance.yahoo.com/v8/finance/chart/" + symbol + "?period1=" + tenYearsAgo + "&period2=" + now + "&interval=1mo";
            var resp = http.get().uri(url).retrieve().body(Map.class);
            Map<?,?> chart = (Map<?,?>) resp.get("chart");
            List<Map<?,?>> results = (List<Map<?,?>>) chart.get("result");
            Map<?,?> first = results.get(0);
            Map<?,?> indicators = (Map<?,?>) first.get("indicators");
            List<?> closes;
            if (indicators.containsKey("adjclose")) {
                List<Map<?,?>> adjclose = (List<Map<?,?>>) indicators.get("adjclose");
                closes = (List<?>) adjclose.get(0).get("adjclose");
            } else {
                List<Map<?,?>> quote = (List<Map<?,?>>) indicators.get("quote");
                closes = (List<?>) quote.get(0).get("close");
            }
            Double start = firstNonNull(closes);
            Double end = lastNonNull(closes);
            // If less than ~9 years of data, adjust years accordingly
            int nonNullCount = 0;
            for (Object o : closes) if (o instanceof Number) nonNullCount++;
            double years = Math.max(1.0, nonNullCount / 12.0);
            if (start != null && end != null && start > 0) return powCagr(start, end, years);
        } catch (Exception ignored) {}
        // Try Yahoo spark
        try {
            String url = "https://query1.finance.yahoo.com/v8/finance/spark?symbols=" + symbol + "&range=10y&interval=1mo";
            var resp = http.get().uri(url).retrieve().body(Map.class);
            List<Map<?,?>> result = (List<Map<?,?>>) ((Map<?,?>) resp.get("spark")) .get("result");
            Map<?,?> first = result.get(0);
            List<?> respArr = (List<?>) first.get("response");
            if (respArr != null && !respArr.isEmpty()) {
                Map<?,?> r0 = (Map<?,?>) respArr.get(0);
                Map<?,?> indicators = (Map<?,?>) r0.get("indicators");
                List<Map<?,?>> quote = (List<Map<?,?>>) indicators.get("quote");
                if (quote != null && !quote.isEmpty()) {
                    List<?> closes = (List<?>) quote.get(0).get("close");
                    Double start = firstNonNull(closes);
                    Double end = lastNonNull(closes);
                    int nonNullCount = 0; for (Object o : closes) if (o instanceof Number) nonNullCount++;
                    double years = Math.max(1.0, nonNullCount / 12.0);
                    if (start != null && end != null && start > 0) return powCagr(start, end, years);
                }
            }
        } catch (Exception ignored) {}
        // Try Stooq monthly for US tickers
        try {
            String s = toStooqSymbol(symbol);
            if (s != null) {
                String url = "https://stooq.com/q/d/l/?s=" + s + "&i=m";
                String body = http.get().uri(url).retrieve().body(String.class);
                String[] lines = body.trim().split("\n");
                Double start = null, end = null;
                for (int i = 1; i < lines.length; i++) {
                    String[] parts = lines[i].split(",");
                    if (parts.length < 6) continue;
                    String closeStr = parts[5].trim();
                    if (closeStr.equalsIgnoreCase("N/D")) continue;
                    double c = Double.parseDouble(closeStr);
                    if (start == null) start = c;
                    end = c;
                }
                if (start != null && end != null && start > 0) return powCagr(start, end, 10.0);
            }
        } catch (Exception ignored) {}
        return 0.06;
    }

    private Double firstNonNull(List<?> closes) {
        for (Object o : closes) { if (o instanceof Number n) return n.doubleValue(); }
        return null;
    }

    private Double lastNonNull(List<?> closes) {
        for (int i = closes.size() - 1; i >= 0; i--) { Object o = closes.get(i); if (o instanceof Number n) return n.doubleValue(); }
        return null;
    }

    private double powCagr(double start, double end, double years) {
        return Math.pow(end / start, 1.0 / years) - 1.0;
    }

    private Double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return null;
    }

    private double fetchDividendYield(String symbol) {
        String url = "https://query1.finance.yahoo.com/v10/finance/quoteSummary/" + symbol + "?modules=summaryDetail";
        var resp = http.get().uri(url).retrieve().body(Map.class);
        try {
            Map<?,?> quoteSummary = (Map<?,?>) resp.get("quoteSummary");
            List<Map<?,?>> results = (List<Map<?,?>>) quoteSummary.get("result");
            Map<?,?> summaryDetail = (Map<?,?>) results.get(0).get("summaryDetail");
            Map<?,?> yieldObj = (Map<?,?>) summaryDetail.get("dividendYield");
            Object f = yieldObj.get("raw");
            if (f instanceof Number n) return n.doubleValue();
        } catch (Exception ignored) {}
        // Fallback to a conservative default when blocked
        return defaultYieldForSymbol(symbol);
    }

    private double fetchExpenseRatio(String symbol) {
        try {
            String url = "https://query1.finance.yahoo.com/v10/finance/quoteSummary/" + symbol + "?modules=fundProfile,defaultKeyStatistics,summaryDetail";
            var resp = http.get().uri(url).retrieve().body(Map.class);
            Map<?,?> qs = (Map<?,?>) resp.get("quoteSummary");
            List<Map<?,?>> results = qs != null ? (List<Map<?,?>>) qs.get("result") : null;
            Map<?,?> root = (results != null && !results.isEmpty()) ? results.get(0) : null;
            if (root != null) {
                try {
                    Map<?,?> fundProfile = (Map<?,?>) root.get("fundProfile");
                    if (fundProfile != null) {
                        Map<?,?> fees = (Map<?,?>) fundProfile.get("feesExpensesInvestment");
                        if (fees != null) {
                            Map<?,?> arr = (Map<?,?>) fees.get("annualReportExpenseRatio");
                            Object raw = arr != null ? arr.get("raw") : null;
                            if (raw instanceof Number n) return n.doubleValue();
                        }
                    }
                } catch (Exception ignored) {}
                try {
                    Map<?,?> stats = (Map<?,?>) root.get("defaultKeyStatistics");
                    if (stats != null) {
                        Map<?,?> er = (Map<?,?>) stats.get("expenseRatio");
                        Object raw = er != null ? er.get("raw") : null;
                        if (raw instanceof Number n) return n.doubleValue();
                    }
                } catch (Exception ignored) {}
                try {
                    Map<?,?> sd = (Map<?,?>) root.get("summaryDetail");
                    if (sd != null) {
                        Map<?,?> er = (Map<?,?>) sd.get("expenseRatio");
                        Object raw = er != null ? er.get("raw") : null;
                        if (raw instanceof Number n) return n.doubleValue();
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return Double.NaN;
    }

    private double defaultExpenseRatio(String symbol, String displayName) {
        String name = displayName != null ? displayName : symbol;
        String upper = name.toUpperCase(Locale.ROOT);
        // Heuristic: ETFs typically charge around 5–30 bps; stocks ~0.
        if (upper.contains("ETF") || upper.contains("UCITS") || upper.contains("INDEX") || symbol.equals(symbol.toUpperCase(Locale.ROOT)) && symbol.length() >= 3) {
            return 0.002; // 20 bps default for funds/ETFs
        }
        return 0.0; // stocks
    }

    private double defaultYieldForSymbol(String symbol) {
        // Conservative defaults by category when Yahoo blocks the endpoint
        if (symbol == null) return 0.0;
        if (symbol.equals("AAPL") || symbol.equals("MSFT") || symbol.equals("GOOGL") || symbol.equals("META") || symbol.equals("TSLA")) return 0.005; // ~0.5%
        if (symbol.endsWith(".L") || symbol.endsWith(".DE") || symbol.endsWith(".AS")) return 0.02; // ETF defaults ~2%
        return 0.0;
    }
}


