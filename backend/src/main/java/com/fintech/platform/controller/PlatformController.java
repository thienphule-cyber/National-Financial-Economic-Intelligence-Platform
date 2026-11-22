package com.fintech.platform.controller;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.fintech.platform.analysis.FinancialAnalysis;
import com.fintech.platform.analysis.FinancialAnalysis.CapitalData;
import com.fintech.platform.analysis.FinancialAnalysis.CapitalResult;
import com.fintech.platform.analysis.FinancialAnalysis.CountryData;
import com.fintech.platform.analysis.FinancialAnalysis.CountryResult;
import com.fintech.platform.analysis.FinancialAnalysis.IndustryData;
import com.fintech.platform.analysis.FinancialAnalysis.IndustryResult;
import com.fintech.platform.data.DataManager;
import com.fintech.platform.data.DataManager.DataPoint;
import com.fintech.platform.data.DataManager.Indicator;
import com.fintech.platform.forecasting.ForecastingModule;
import com.fintech.platform.forecasting.ForecastingModule.ForecastResult;
import com.fintech.platform.research.ResearchModule;
import com.fintech.platform.research.ResearchModule.Metric;
import com.fintech.platform.research.ResearchModule.Report;
import com.fintech.platform.simulation.SimulationModule;
import com.fintech.platform.simulation.SimulationModule.Baseline;
import com.fintech.platform.simulation.SimulationModule.Preset;
import com.fintech.platform.simulation.SimulationModule.ScenarioRequest;
import com.fintech.platform.simulation.SimulationModule.ScenarioResult;

/**
 * Single REST controller of the platform. It only translates HTTP requests into
 * calls to the modules and converts errors into JSON; all logic lives in the modules.
 *
 * Endpoints (all under /api):
 *
 *   System
 *     GET    /health
 *     GET    /indicators
 *     DELETE /cache
 *
 *   Data
 *     GET    /data/{country}/{indicator}?fromYear=
 *     GET    /compare/series?countries=VNM,AUS&indicator=GDP_GROWTH&fromYear=
 *
 *   Research
 *     GET    /research/{country}/overview | industry | investment
 *
 *   Analysis
 *     GET    /analysis/countries?codes=VNM,AUS,USA        (live World Bank data)
 *     POST   /analysis/industry                           (body: list of IndustryData)
 *     POST   /analysis/capital                            (body: list of CapitalData)
 *     POST   /analysis/countries                          (body: list of CountryData)
 *
 *   Forecasting
 *     GET    /forecast/models
 *     GET    /forecast/{type}/{country}?indicator=&horizon=&model=   (type: gdp | industry | investment)
 *
 *   Simulation
 *     GET    /simulation/presets
 *     GET    /simulation/baseline/{country}
 *     POST   /simulation/investment                       (body: ScenarioRequest with adjustments)
 *     POST   /simulation/scenario                         (body: ScenarioRequest, optional preset)
 *
 * Analysis, forecasting and simulation endpoints accept save=true. The result is then also
 * stored in results/{analysis|forecasting|simulation}/ and the response header X-Saved-To
 * tells where.
 */
@RestController
@RequestMapping("/api")
public class PlatformController {

    private static final Logger log = LoggerFactory.getLogger(PlatformController.class);

    private static final int MAX_COUNTRIES = 10;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final DataManager dataManager;
    private final ResearchModule research;
    private final FinancialAnalysis financialAnalysis;
    private final ForecastingModule forecasting;
    private final SimulationModule simulation;

    public PlatformController(
            DataManager dataManager,
            ResearchModule research,
            FinancialAnalysis financialAnalysis,
            ForecastingModule forecasting,
            SimulationModule simulation) {
        this.dataManager = dataManager;
        this.research = research;
        this.financialAnalysis = financialAnalysis;
        this.forecasting = forecasting;
        this.simulation = simulation;
    }

    // =====================================================================
    // System
    // =====================================================================

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "National Financial & Economic Intelligence Platform",
                "timestamp", Instant.now().toString());
    }

    /** All indicators that can be requested (key, source, label, unit). */
    @GetMapping("/indicators")
    public List<Map<String, String>> indicators() {
        return dataManager.listIndicators();
    }

    /** Deletes all cached API responses, so the next request downloads fresh data. */
    @DeleteMapping("/cache")
    public Map<String, Object> clearCache() {
        return Map.of("removedFiles", dataManager.clearCache());
    }

    // =====================================================================
    // Data
    // =====================================================================

    @GetMapping("/data/{country}/{indicator}")
    public Map<String, Object> series(
            @PathVariable String country,
            @PathVariable String indicator,
            @RequestParam(required = false) Integer fromYear) {

        Indicator key = Indicator.fromKey(indicator);
        List<DataPoint> points = filterFromYear(dataManager.getSeries(country, key), fromYear);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("country", country.trim().toUpperCase(Locale.ROOT));
        body.put("indicator", key.name());
        body.put("label", key.label());
        body.put("unit", key.unit());
        body.put("source", key.source().displayName());
        body.put("count", points.size());
        body.put("points", points);
        return body;
    }

    /** Same indicator for several countries, ready for a multi-line chart. */
    @GetMapping("/compare/series")
    public Map<String, Object> compareSeries(
            @RequestParam String countries,
            @RequestParam String indicator,
            @RequestParam(required = false) Integer fromYear) {

        Indicator key = Indicator.fromKey(indicator);

        Map<String, List<DataPoint>> byCountry = new LinkedHashMap<>();
        for (String code : parseCodes(countries, MAX_COUNTRIES)) {
            byCountry.put(code, filterFromYear(dataManager.getSeries(code, key), fromYear));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("indicator", key.name());
        body.put("label", key.label());
        body.put("unit", key.unit());
        body.put("source", key.source().displayName());
        body.put("series", byCountry);
        return body;
    }

    // =====================================================================
    // Research
    // =====================================================================

    @GetMapping("/research/{country}/overview")
    public Report researchOverview(@PathVariable String country) {
        return research.researchGdp(country);
    }

    @GetMapping("/research/{country}/industry")
    public Report researchIndustry(@PathVariable String country) {
        return research.researchIndustry(country);
    }

    @GetMapping("/research/{country}/investment")
    public Report researchInvestment(@PathVariable String country) {
        return research.researchInvestment(country);
    }

    // =====================================================================
    // Financial analysis
    // =====================================================================

    /**
     * Country comparison from live World Bank data (latest available value of each indicator).
     * A missing indicator is treated as 0 and reported in "warnings", so read the warnings
     * before trusting the derived figures.
     */
    @GetMapping("/analysis/countries")
    public ResponseEntity<Map<String, Object>> compareCountriesLive(
            @RequestParam String codes,
            @RequestParam(defaultValue = "false") boolean save) {

        List<CountryData> data = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (String code : parseCodes(codes, MAX_COUNTRIES)) {
            Report report = research.researchGdp(code);
            data.add(new CountryData(
                    report.country(),
                    requiredValue(report, "GDP"),
                    valueOrZero(report, "GDP_GROWTH", warnings),
                    valueOrZero(report, "INFLATION", warnings),
                    valueOrZero(report, "UNEMPLOYMENT", warnings),
                    valueOrZero(report, "DEBT_PCT_GDP", warnings),
                    valueOrZero(report, "INVESTMENT_PCT_GDP", warnings),
                    valueOrZero(report, "FDI_NET_INFLOWS", warnings),
                    valueOrZero(report, "EXPORTS", warnings),
                    valueOrZero(report, "IMPORTS", warnings)));
        }

        List<CountryResult> results = financialAnalysis.compareCountries(data);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("basis", "Latest available World Bank value of each indicator (years may differ between countries)");
        body.put("warnings", warnings);
        body.put("results", results);
        return respond(body, save, "analysis", "countries-" + stamp());
    }

    /** Industry-level figures (revenue, capital, ...) are not public, so they are sent in the request body. */
    @PostMapping("/analysis/industry")
    public ResponseEntity<List<IndustryResult>> analyzeIndustries(
            @RequestBody List<IndustryData> data,
            @RequestParam(defaultValue = "false") boolean save) {
        return respond(financialAnalysis.analyzeIndustries(data), save, "analysis", "industry-" + stamp());
    }

    @PostMapping("/analysis/capital")
    public ResponseEntity<List<CapitalResult>> analyzeCapital(
            @RequestBody List<CapitalData> data,
            @RequestParam(defaultValue = "false") boolean save) {
        return respond(financialAnalysis.analyzeCapital(data), save, "analysis", "capital-" + stamp());
    }

    @PostMapping("/analysis/countries")
    public ResponseEntity<List<CountryResult>> compareCountries(
            @RequestBody List<CountryData> data,
            @RequestParam(defaultValue = "false") boolean save) {
        return respond(financialAnalysis.compareCountries(data), save, "analysis", "countries-" + stamp());
    }

    // =====================================================================
    // Forecasting
    // =====================================================================

    @GetMapping("/forecast/models")
    public List<String> forecastModels() {
        return forecasting.availableModels();
    }

    /**
     * Forecast of one indicator, based on its historical (not estimated or forecasted) values.
     * Default indicator per type: gdp -> GDP, industry -> MANUFACTURING_PCT_GDP, investment -> FDI_NET_INFLOWS.
     */
    @GetMapping("/forecast/{type}/{country}")
    public ResponseEntity<ForecastResult> forecast(
            @PathVariable String type,
            @PathVariable String country,
            @RequestParam(required = false) String indicator,
            @RequestParam(defaultValue = "5") int horizon,
            @RequestParam(required = false) String model,
            @RequestParam(defaultValue = "false") boolean save) {

        String forecastType = type.trim().toLowerCase(Locale.ROOT);
        String defaultIndicator = switch (forecastType) {
            case "gdp" -> "GDP";
            case "industry" -> "MANUFACTURING_PCT_GDP";
            case "investment" -> "FDI_NET_INFLOWS";
            default -> throw new IllegalArgumentException("Forecast type must be one of: gdp, industry, investment");
        };

        Indicator key = Indicator.fromKey(indicator == null || indicator.isBlank() ? defaultIndicator : indicator);
        String countryCode = country.trim().toUpperCase(Locale.ROOT);
        String subject = countryCode + " " + key.label();

        Map<Integer, Double> history = DataManager.toYearMap(dataManager.getSeries(countryCode, key), true);

        ForecastResult result = switch (forecastType) {
            case "gdp" -> forecasting.forecastGdp(subject, history, horizon, model);
            case "industry" -> forecasting.forecastIndustry(subject, history, horizon, model);
            default -> forecasting.forecastInvestment(subject, history, horizon, model);
        };

        return respond(result, save, "forecasting", countryCode + "_" + key.name() + "_" + result.model());
    }

    // =====================================================================
    // Simulation
    // =====================================================================

    @GetMapping("/simulation/presets")
    public List<Preset> simulationPresets() {
        return simulation.presets();
    }

    /** Starting point of a simulation (GDP, investment, sector shares) built from World Bank data. */
    @GetMapping("/simulation/baseline/{country}")
    public Baseline simulationBaseline(@PathVariable String country) {
        return simulation.loadBaseline(country);
    }

    @PostMapping("/simulation/investment")
    public ResponseEntity<ScenarioResult> simulateInvestment(
            @RequestBody ScenarioRequest request,
            @RequestParam(defaultValue = "false") boolean save) {
        ScenarioResult result = simulation.simulateInvestment(request);
        return respond(result, save, "simulation", result.country() + "_" + stamp());
    }

    @PostMapping("/simulation/scenario")
    public ResponseEntity<ScenarioResult> simulateScenario(
            @RequestBody ScenarioRequest request,
            @RequestParam(defaultValue = "false") boolean save) {
        ScenarioResult result = simulation.simulateScenario(request);
        return respond(result, save, "simulation", result.country() + "_" + stamp());
    }

    // =====================================================================
    // Error handling
    // =====================================================================

    /** Invalid input: bad country code, unknown indicator or sector, out-of-range value, no data. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException e) {
        String message = e.getMessage() == null ? "Invalid request" : e.getMessage();
        return error(HttpStatus.BAD_REQUEST, message);
    }

    /** The external API (World Bank / IMF) could not be reached and there is no cache to fall back on. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleUpstreamFailure(IllegalStateException e) {
        log.warn("Data source problem: {}", e.getMessage());
        return error(HttpStatus.BAD_GATEWAY, "Data source unavailable: " + e.getMessage());
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ResponseEntity<Map<String, String>> handleFileError(UncheckedIOException e) {
        log.error("File error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "File error: " + e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadableBody(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "Request body is missing or is not valid JSON");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleBadParameter(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + e.getName() + "'");
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /** Returns the body, and when save is true also stores it in results/{category}/{name}.json. */
    private <T> ResponseEntity<T> respond(T body, boolean save, String category, String name) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (save) {
            Path file = dataManager.saveResult(category, name, body);
            builder.header("X-Saved-To", category + "/" + file.getFileName());
        }
        return builder.body(body);
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    private static String stamp() {
        return STAMP.format(LocalDateTime.now());
    }

    private static List<DataPoint> filterFromYear(List<DataPoint> points, Integer fromYear) {
        if (fromYear == null) {
            return points;
        }
        return points.stream().filter(point -> point.year() >= fromYear).toList();
    }

    /** Splits "VNM, aus,USA" into [VNM, AUS, USA] (upper case, no duplicates). */
    private static List<String> parseCodes(String text, int max) {
        List<String> codes = Arrays.stream(text.split(","))
                .map(String::trim)
                .filter(code -> !code.isEmpty())
                .map(code -> code.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();

        if (codes.isEmpty()) {
            throw new IllegalArgumentException("At least one country code is required, e.g. VNM,AUS,USA");
        }
        if (codes.size() > max) {
            throw new IllegalArgumentException("At most " + max + " countries can be requested at once");
        }
        return codes;
    }

    private static Double metricValue(Report report, String key) {
        for (Metric metric : report.metrics()) {
            if (key.equals(metric.key())) {
                return metric.latestValue();
            }
        }
        return null;
    }

    private static double requiredValue(Report report, String key) {
        Double value = metricValue(report, key);
        if (value == null) {
            throw new IllegalArgumentException(key + " is not available for " + report.country());
        }
        return value;
    }

    private static double valueOrZero(Report report, String key, List<String> warnings) {
        Double value = metricValue(report, key);
        if (value == null) {
            warnings.add(report.country() + ": " + key + " is unavailable and was treated as 0");
            return 0.0;
        }
        return value;
    }
}