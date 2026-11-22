package com.fintech.platform.data;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Data access module.
 *
 * Main class: DataManager (facade used by the rest of the platform).
 * Nested classes:
 *   - ApiClient (abstract), WorldBankClient, IMFClient : fetch public data
 *   - CacheManager : local cache of API responses (folder: cache/)
 *   - JSONManager, CSVManager : read/write files (cache and results)
 *
 * Flow: request -> fresh cache? -> yes: return cache
 *                               -> no : call API -> save cache -> return
 *       If the API fails and an old cache exists, the old cache is returned.
 */
@Component
public class DataManager {

    private static final Logger log = LoggerFactory.getLogger(DataManager.class);

    private static final Pattern COUNTRY_CODE = Pattern.compile("^[A-Za-z]{3}$");
    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final Set<String> RESULT_CATEGORIES = Set.of("analysis", "forecasting", "simulation");

    // =====================================================================
    // Indicator catalog
    // =====================================================================

    public enum Source {
        WORLD_BANK("World Bank"),
        IMF("IMF");

        private final String displayName;

        Source(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** Every indicator the platform can request, mapped to its API code. */
    public enum Indicator {
        // --- World Bank: national overview ---
        GDP(Source.WORLD_BANK, "NY.GDP.MKTP.CD", "GDP (current US$)", "USD"),
        GDP_GROWTH(Source.WORLD_BANK, "NY.GDP.MKTP.KD.ZG", "GDP growth (annual %)", "%"),
        GDP_PER_CAPITA(Source.WORLD_BANK, "NY.GDP.PCAP.CD", "GDP per capita (current US$)", "USD"),
        INFLATION(Source.WORLD_BANK, "FP.CPI.TOTL.ZG", "Inflation, consumer prices (annual %)", "%"),
        UNEMPLOYMENT(Source.WORLD_BANK, "SL.UEM.TOTL.ZS", "Unemployment (% of labor force)", "%"),
        DEBT_PCT_GDP(Source.WORLD_BANK, "GC.DOD.TOTL.GD.ZS", "Central government debt (% of GDP)", "% of GDP"),
        GOV_EXPENDITURE_PCT_GDP(Source.WORLD_BANK, "NE.CON.GOVT.ZS", "Government final consumption (% of GDP)", "% of GDP"),
        INVESTMENT_PCT_GDP(Source.WORLD_BANK, "NE.GDI.TOTL.ZS", "Gross capital formation (% of GDP)", "% of GDP"),
        FDI_NET_INFLOWS(Source.WORLD_BANK, "BX.KLT.DINV.CD.WD", "FDI, net inflows (current US$)", "USD"),
        FDI_PCT_GDP(Source.WORLD_BANK, "BX.KLT.DINV.WD.GD.ZS", "FDI, net inflows (% of GDP)", "% of GDP"),
        EXPORTS(Source.WORLD_BANK, "NE.EXP.GNFS.CD", "Exports of goods and services (current US$)", "USD"),
        IMPORTS(Source.WORLD_BANK, "NE.IMP.GNFS.CD", "Imports of goods and services (current US$)", "USD"),
        EXPORTS_PCT_GDP(Source.WORLD_BANK, "NE.EXP.GNFS.ZS", "Exports (% of GDP)", "% of GDP"),
        IMPORTS_PCT_GDP(Source.WORLD_BANK, "NE.IMP.GNFS.ZS", "Imports (% of GDP)", "% of GDP"),

        // --- World Bank: sector contribution (closest public data to industry analysis) ---
        AGRICULTURE_PCT_GDP(Source.WORLD_BANK, "NV.AGR.TOTL.ZS", "Agriculture value added (% of GDP)", "% of GDP"),
        INDUSTRY_PCT_GDP(Source.WORLD_BANK, "NV.IND.TOTL.ZS", "Industry incl. construction value added (% of GDP)", "% of GDP"),
        MANUFACTURING_PCT_GDP(Source.WORLD_BANK, "NV.IND.MANF.ZS", "Manufacturing value added (% of GDP)", "% of GDP"),
        SERVICES_PCT_GDP(Source.WORLD_BANK, "NV.SRV.TOTL.ZS", "Services value added (% of GDP)", "% of GDP"),
        MINERAL_RENTS_PCT_GDP(Source.WORLD_BANK, "NY.GDP.MINR.RT.ZS", "Mineral rents (% of GDP)", "% of GDP"),
        TOURISM_RECEIPTS(Source.WORLD_BANK, "ST.INT.RCPT.CD", "International tourism receipts (current US$)", "USD"),
        EMPLOYMENT_AGRICULTURE_PCT(Source.WORLD_BANK, "SL.AGR.EMPL.ZS", "Employment in agriculture (% of total)", "%"),
        EMPLOYMENT_INDUSTRY_PCT(Source.WORLD_BANK, "SL.IND.EMPL.ZS", "Employment in industry (% of total)", "%"),
        EMPLOYMENT_SERVICES_PCT(Source.WORLD_BANK, "SL.SRV.EMPL.ZS", "Employment in services (% of total)", "%"),

        // --- IMF DataMapper (includes official projections) ---
        IMF_GDP_GROWTH(Source.IMF, "NGDP_RPCH", "Real GDP growth (annual %)", "%"),
        IMF_INFLATION(Source.IMF, "PCPIPCH", "Inflation, average consumer prices (annual %)", "%"),
        IMF_UNEMPLOYMENT(Source.IMF, "LUR", "Unemployment rate (%)", "%"),
        IMF_DEBT_PCT_GDP(Source.IMF, "GGXWDG_NGDP", "General government gross debt (% of GDP)", "% of GDP"),
        IMF_GDP_USD_BILLIONS(Source.IMF, "NGDPD", "GDP, current prices (billion US$)", "billion USD");

        private final Source source;
        private final String code;
        private final String label;
        private final String unit;

        Indicator(Source source, String code, String label, String unit) {
            this.source = source;
            this.code = code;
            this.label = label;
            this.unit = unit;
        }

        public Source source() {
            return source;
        }

        public String code() {
            return code;
        }

        public String label() {
            return label;
        }

        public String unit() {
            return unit;
        }

        /** Parses a user supplied key such as "gdp_growth" (case-insensitive). */
        public static Indicator fromKey(String key) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Indicator must not be empty");
            }
            try {
                return valueOf(key.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown indicator: " + key);
            }
        }
    }

    /** One value of one indicator for one country and year. */
    public record DataPoint(
            String country,
            String indicator,
            int year,
            double value,
            String dataType,   // historical | estimated | forecasted
            String source) {
    }

    // =====================================================================
    // API clients (abstraction + polymorphism)
    // =====================================================================

    public abstract static class ApiClient {

        private final RestClient restClient;
        protected final ObjectMapper mapper;
        protected final String baseUrl;

        protected ApiClient(RestClient restClient, ObjectMapper mapper, String baseUrl) {
            this.restClient = restClient;
            this.mapper = mapper;
            this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        }

        public abstract Source source();

        /** Fetches the full time series of one indicator for one country. */
        public abstract List<DataPoint> fetchSeries(String countryCode, Indicator indicator);

        /** Network or parsing problems are reported as IllegalStateException. */
        protected JsonNode getJson(String url) {
            try {
                String body = restClient.get().uri(URI.create(url)).retrieve().body(String.class);
                if (body == null || body.isBlank()) {
                    throw new IllegalStateException("Empty response from " + url);
                }
                return mapper.readTree(body);
            } catch (RestClientException | IOException e) {
                throw new IllegalStateException("Request failed: " + url + " (" + e.getMessage() + ")", e);
            }
        }

        protected static Integer parseYear(String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    public static class WorldBankClient extends ApiClient {

        public WorldBankClient(RestClient restClient, ObjectMapper mapper, String baseUrl) {
            super(restClient, mapper, baseUrl);
        }

        @Override
        public Source source() {
            return Source.WORLD_BANK;
        }

        @Override
        public List<DataPoint> fetchSeries(String countryCode, Indicator indicator) {
            String url = baseUrl + "/country/" + countryCode + "/indicator/" + indicator.code()
                    + "?format=json&per_page=1000&date=1990:" + Year.now().getValue();

            // Response shape: [ {paging info}, [ {date, value, ...}, ... ] ]
            JsonNode root = getJson(url);
            if (!root.isArray() || root.size() < 2) {
                String detail = root.path(0).path("message").path(0).path("value").asText("unexpected response");
                throw new IllegalArgumentException("World Bank rejected " + indicator.name() + " / "
                        + countryCode + ": " + detail);
            }

            JsonNode rows = root.get(1);
            List<DataPoint> points = new ArrayList<>();
            if (rows == null || !rows.isArray()) {
                return points; // valid request but no data
            }

            for (JsonNode row : rows) {
                JsonNode value = row.get("value");
                Integer year = parseYear(row.path("date").asText(""));
                if (value == null || value.isNull() || year == null) {
                    continue; // missing observation
                }
                points.add(new DataPoint(countryCode, indicator.name(), year, value.asDouble(),
                        "historical", source().displayName()));
            }
            points.sort(Comparator.comparingInt(DataPoint::year));
            return points;
        }
    }

    public static class IMFClient extends ApiClient {

        public IMFClient(RestClient restClient, ObjectMapper mapper, String baseUrl) {
            super(restClient, mapper, baseUrl);
        }

        @Override
        public Source source() {
            return Source.IMF;
        }

        @Override
        public List<DataPoint> fetchSeries(String countryCode, Indicator indicator) {
            String url = baseUrl + "/" + indicator.code() + "/" + countryCode;

            // Response shape: { "values": { "<indicator>": { "<country>": { "<year>": value } } } }
            JsonNode node = getJson(url).path("values").path(indicator.code()).path(countryCode);

            List<DataPoint> points = new ArrayList<>();
            if (!node.isObject()) {
                return points; // valid request but no data
            }

            Iterator<String> years = node.fieldNames();
            while (years.hasNext()) {
                String yearText = years.next();
                JsonNode value = node.get(yearText);
                Integer year = parseYear(yearText);
                if (value == null || !value.isNumber() || year == null) {
                    continue;
                }
                points.add(new DataPoint(countryCode, indicator.name(), year, value.asDouble(),
                        dataTypeOf(year), source().displayName()));
            }
            points.sort(Comparator.comparingInt(DataPoint::year));
            return points;
        }

        /** IMF series mix actuals, current-year estimates and projections. */
        private static String dataTypeOf(int year) {
            int current = Year.now().getValue();
            if (year > current) {
                return "forecasted";
            }
            return year == current ? "estimated" : "historical";
        }
    }

    // =====================================================================
    // File managers
    // =====================================================================

    public static class JSONManager {

        private final ObjectMapper mapper;

        public JSONManager(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        public <T> T read(Path file, TypeReference<T> type) {
            try {
                return mapper.readValue(file.toFile(), type);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read JSON: " + file, e);
            }
        }

        public void write(Path file, Object data) {
            try {
                Files.createDirectories(file.toAbsolutePath().getParent());
                mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot write JSON: " + file, e);
            }
        }
    }

    public static class CSVManager {

        public void write(Path file, List<String> header, List<List<Object>> rows) {
            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setHeader(header.toArray(new String[0]))
                    .build();
            try {
                Files.createDirectories(file.toAbsolutePath().getParent());
                try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
                        CSVPrinter printer = new CSVPrinter(writer, format)) {
                    for (List<Object> row : rows) {
                        printer.printRecord(row);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot write CSV: " + file, e);
            }
        }

        public List<Map<String, String>> read(Path file) {
            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .build();
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
                    CSVParser parser = format.parse(reader)) {
                List<Map<String, String>> rows = new ArrayList<>();
                for (CSVRecord record : parser) {
                    rows.add(record.toMap());
                }
                return rows;
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read CSV: " + file, e);
            }
        }
    }

    // =====================================================================
    // Cache
    // =====================================================================

    public static class CacheManager {

        private final Path cacheDir;
        private final Duration ttl;
        private final JSONManager json;

        public CacheManager(Path cacheDir, Duration ttl, JSONManager json) {
            this.cacheDir = cacheDir;
            this.ttl = ttl;
            this.json = json;
        }

        public Path fileFor(Indicator indicator, String countryCode) {
            String name = indicator.source().name().toLowerCase() + "_" + indicator.code()
                    + "_" + countryCode + ".json";
            return cacheDir.resolve(name);
        }

        public boolean isFresh(Path file) {
            try {
                if (!Files.exists(file)) {
                    return false;
                }
                Instant modified = Files.getLastModifiedTime(file).toInstant();
                return modified.plus(ttl).isAfter(Instant.now());
            } catch (IOException e) {
                return false;
            }
        }

        /** Returns empty when the file is missing or corrupted. */
        public Optional<List<DataPoint>> read(Path file) {
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            try {
                return Optional.of(json.read(file, new TypeReference<List<DataPoint>>() {
                }));
            } catch (UncheckedIOException e) {
                log.warn("Ignoring unreadable cache file {}", file);
                return Optional.empty();
            }
        }

        public void write(Path file, List<DataPoint> points) {
            json.write(file, points);
        }

        /** Deletes all cached .json files and returns how many were removed. */
        public int clear() {
            if (!Files.isDirectory(cacheDir)) {
                return 0;
            }
            int removed = 0;
            try (Stream<Path> files = Files.list(cacheDir)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".json")).toList()) {
                    Files.deleteIfExists(file);
                    removed++;
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot clear cache: " + cacheDir, e);
            }
            return removed;
        }
    }

    // =====================================================================
    // DataManager facade
    // =====================================================================

    private final Map<Source, ApiClient> clients = new EnumMap<>(Source.class);
    private final CacheManager cache;
    private final JSONManager jsonManager;
    private final CSVManager csvManager = new CSVManager();
    private final Path resultsDir;

    public DataManager(
            ObjectMapper mapper,
            @Value("${platform.cache.dir}") String cacheDir,
            @Value("${platform.results.dir}") String resultsDir,
            @Value("${platform.cache.ttl-hours}") long ttlHours,
            @Value("${platform.api.worldbank.base-url}") String worldBankUrl,
            @Value("${platform.api.imf.base-url}") String imfUrl,
            @Value("${platform.http.connect-timeout-ms}") int connectTimeoutMs,
            @Value("${platform.http.read-timeout-ms}") int readTimeoutMs) {

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMs));

        RestClient restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.USER_AGENT, "NationalFinancialIntelligencePlatform/0.1")
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();

        this.jsonManager = new JSONManager(mapper);
        this.cache = new CacheManager(
                Path.of(cacheDir).toAbsolutePath().normalize(),
                Duration.ofHours(ttlHours),
                jsonManager);
        this.resultsDir = Path.of(resultsDir).toAbsolutePath().normalize();

        clients.put(Source.WORLD_BANK, new WorldBankClient(restClient, mapper, worldBankUrl));
        clients.put(Source.IMF, new IMFClient(restClient, mapper, imfUrl));
    }

    /** Returns the time series (sorted by year) of one indicator for one country. */
    public List<DataPoint> getSeries(String countryCode, Indicator indicator) {
        String country = normalizeCountry(countryCode);
        Path file = cache.fileFor(indicator, country);

        if (cache.isFresh(file)) {
            Optional<List<DataPoint>> cached = cache.read(file);
            if (cached.isPresent()) {
                return cached.get();
            }
        }

        try {
            List<DataPoint> fresh = clients.get(indicator.source()).fetchSeries(country, indicator);
            cache.write(file, fresh);
            return fresh;
        } catch (IllegalStateException e) {
            // Network problem: fall back to an old cache if there is one.
            Optional<List<DataPoint>> stale = cache.read(file);
            if (stale.isPresent()) {
                log.warn("API unavailable, using stale cache for {} / {}: {}", indicator, country, e.getMessage());
                return stale.get();
            }
            throw e;
        }
    }

    /** Converts a series to year -> value. Use historicalOnly=true as input for forecasting. */
    public static Map<Integer, Double> toYearMap(List<DataPoint> points, boolean historicalOnly) {
        Map<Integer, Double> map = new TreeMap<>();
        for (DataPoint point : points) {
            if (historicalOnly && !"historical".equals(point.dataType())) {
                continue;
            }
            map.put(point.year(), point.value());
        }
        return map;
    }

    /** Lists all supported indicators (for the API and the frontend). */
    public List<Map<String, String>> listIndicators() {
        List<Map<String, String>> list = new ArrayList<>();
        for (Indicator indicator : Indicator.values()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("key", indicator.name());
            item.put("source", indicator.source().displayName());
            item.put("label", indicator.label());
            item.put("unit", indicator.unit());
            list.add(item);
        }
        return list;
    }

    /** Saves a result object as results/{category}/{name}.json. */
    public Path saveResult(String category, String name, Object data) {
        Path file = resultFile(category, name, ".json");
        jsonManager.write(file, data);
        return file;
    }

    /** Saves a result table as results/{category}/{name}.csv. */
    public Path exportCsv(String category, String name, List<String> header, List<List<Object>> rows) {
        Path file = resultFile(category, name, ".csv");
        csvManager.write(file, header, rows);
        return file;
    }

    /** Deletes all cached API responses. */
    public int clearCache() {
        return cache.clear();
    }

    private Path resultFile(String category, String name, String extension) {
        if (category == null || !RESULT_CATEGORIES.contains(category)) {
            throw new IllegalArgumentException("Result category must be one of " + RESULT_CATEGORIES);
        }
        if (name == null || !SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Result name may only contain letters, digits, '_' and '-'");
        }
        return resultsDir.resolve(category).resolve(name + extension);
    }

    private static String normalizeCountry(String countryCode) {
        if (countryCode == null || !COUNTRY_CODE.matcher(countryCode.trim()).matches()) {
            throw new IllegalArgumentException("Country must be a 3-letter ISO code, e.g. VNM, AUS, USA");
        }
        return countryCode.trim().toUpperCase();
    }
}