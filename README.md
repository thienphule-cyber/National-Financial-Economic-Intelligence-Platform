# National Financial & Economic Intelligence Platform

A stand-alone FinTech **analysis** platform that studies how industries contribute to national economies, how capital is allocated, and how the economy may evolve. It is built with Java Spring Boot (backend) and a plain HTML/CSS/JavaScript dashboard (frontend).

This is a research and analytics tool. It is **not** a banking, payment or trading system: there are no user accounts, no authentication and no transaction processing.

---

## 1. Overview

The platform combines four capabilities:

1. **Financial Research** - collects public macroeconomic and sector data and summarises it.
2. **Financial Data Analytics** - derives indicators such as trade balance, capital efficiency and ROI.
3. **Economic & Financial Forecasting** - statistical extrapolation of historical series.
4. **Investment Scenario Simulation** - assumption-based "what if" analysis of investment changes.

All national data is downloaded on demand from public APIs (World Bank and IMF) and cached locally as JSON files. No database server is required.

## 2. Features

| Dashboard section | What it shows | Main backend classes |
|---|---|---|
| National Overview | GDP, growth, inflation, unemployment, debt, investment, FDI, trade, derived indicators | `ResearchModule.GDPResearch` |
| Industry Analysis | Sector share of GDP and employment, relative productivity; analysis of user-supplied industry data | `ResearchModule.IndustryResearch`, `FinancialAnalysis.IndustryAnalysis` |
| Capital Allocation | Investment rate, government spending, FDI, ICOR; analysis of user-supplied capital data | `ResearchModule.InvestmentResearch`, `FinancialAnalysis.CapitalAnalysis` |
| Comparison | Multi-country time series and latest-value comparison | `FinancialAnalysis.CountryComparison` |
| Forecasting | Forecast with historical / estimated / forecasted labels and an approximate 95% band | `ForecastingModule` |
| Simulation | Impact of investment changes on GDP, employment and sectors | `SimulationModule` |

## 3. Architecture

```
World Bank API ─┐
                ├─► DataManager ◄─► cache/
IMF DataMapper ─┘        │
          ┌──────────────┼───────────────┐
          ▼              ▼               ▼
   ResearchModule   ForecastingModule  FinancialAnalysis
          │
          ▼
   SimulationModule
          └──────────────┬───────────────┘
                         ▼
                PlatformController ──► results/ (optional)
                         │  REST / JSON
                         ▼
              Frontend dashboard (Chart.js)
```

Typical request flow:

1. The dashboard calls a REST endpoint under `/api`.
2. `PlatformController` validates the request and calls the relevant module.
3. Modules read data through `DataManager`, which returns cached data when it is fresh and downloads it otherwise.
4. The result is returned as JSON and, if `save=true`, also stored in `results/`.

## 4. Project structure

```
National-Financial-Intelligence-Platform/
├── backend/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/fintech/platform/
│       │   ├── PlatformApplication.java
│       │   ├── controller/PlatformController.java
│       │   ├── model/EconomicModel.java
│       │   ├── research/ResearchModule.java
│       │   ├── analysis/FinancialAnalysis.java
│       │   ├── forecasting/ForecastingModule.java
│       │   ├── simulation/SimulationModule.java
│       │   └── data/DataManager.java
│       └── resources/application.properties
├── frontend/
│   ├── index.html
│   ├── css/style.css
│   └── js/app.js
├── cache/            (generated: cached API responses)
├── results/          (generated: saved analysis, forecasting, simulation results)
├── logs/             (generated: backend log written by start.sh)
├── start.sh
├── stop.sh
├── README.md
├── report.md
├── references.md
├── LICENSE.md
└── .gitignore
```

## 5. Modules

Every module has one main class. Its subclasses are nested classes in the same file, and each module follows the same pattern: an abstract base class defines the workflow, and the subclasses implement the specific behaviour.

| Module | Subclasses | Responsibility |
|---|---|---|
| `DataManager` | `WorldBankClient`, `IMFClient`, `CacheManager`, `JSONManager`, `CSVManager` | Downloads data, caches it, falls back to stale cache when the network fails, saves results. Also contains the `Indicator` enum (indicator catalog). |
| `ResearchModule` | `GDPResearch`, `IndustryResearch`, `InvestmentResearch` | Collects indicators and builds reports with latest value, 5-year average, trend and derived metrics. |
| `FinancialAnalysis` | `IndustryAnalysis`, `CapitalAnalysis`, `CountryComparison` | Pure calculations on the data it receives (profit margin, productivity, capital efficiency, ROI, trade balance, rankings). |
| `ForecastingModule` | `GDPForecast`, `IndustryForecast`, `InvestmentForecast` | Statistical forecasting with three models: `linear`, `holt`, `moving-average`. |
| `SimulationModule` | `InvestmentSimulation`, `EconomicScenario` | Capital-output simulation by sector, with custom adjustments or named presets. |
| `EconomicModel` | `Country`, `Industry`, `FinancialIndicator`, `Investment` | Domain model with validated, encapsulated fields. Currently a reusable model layer; modules exchange lightweight records. |

OOP principles used: encapsulation (private fields, validation in constructors), inheritance (abstract bases such as `Analysis`, `Research`, `Forecast`, `Simulation`), polymorphism (each subclass implements its own computation) and abstraction (the `Model` interface for forecasting models, `ApiClient` for data sources).

## 6. Data

### Sources

| Source | Used for | Notes |
|---|---|---|
| World Bank Indicators API | Country indicators, sector value added, employment shares, trade, FDI | No API key required |
| IMF DataMapper API | GDP growth, inflation, unemployment, debt, GDP in US$ (including official projections) | Available through `/api/data`, `IMF_*` indicators |

### Data types

Every data point is labelled with one of three types, and the dashboard keeps them visually separate:

- `historical` - published observations;
- `estimated` - values filled by interpolation, and the current-year estimates in IMF series;
- `forecasted` - model output or official projections.

### Cache and fallback

- Responses are stored in `cache/` as JSON, one file per source, indicator and country.
- A cached file is used while it is younger than `platform.cache.ttl-hours` (72 hours by default).
- If the API cannot be reached and an older cache file exists, the older file is used so the platform keeps working.
- `DELETE /api/cache` removes all cached files.

### What is not available as open data

Industry-level revenue, operating expenditure, capital investment and the public/private or domestic/foreign split by industry are not published consistently for all countries. The platform therefore:

- uses broad sectors from the World Bank (agriculture, industry, manufacturing, services, mineral rents) for the research views;
- lets the user paste their own industry and capital data into the analysis panels;
- measures capital efficiency for the whole economy with the ICOR.

## 7. REST API

All endpoints are under `/api`. Analysis, forecasting and simulation endpoints accept `save=true`; the result is then also stored in `results/` and the response header `X-Saved-To` names the file.

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/health` | Service status |
| GET | `/indicators` | All supported indicators (key, source, label, unit) |
| DELETE | `/cache` | Clear cached API responses |
| GET | `/data/{country}/{indicator}?fromYear=` | Time series of one indicator |
| GET | `/compare/series?countries=VNM,AUS&indicator=GDP_GROWTH&fromYear=` | One indicator for several countries |
| GET | `/research/{country}/overview` | National overview report |
| GET | `/research/{country}/industry` | Sector structure report |
| GET | `/research/{country}/investment` | Investment and capital report |
| GET | `/analysis/countries?codes=VNM,AUS,USA` | Country comparison from live data |
| POST | `/analysis/industry` | Industry analysis from a JSON body |
| POST | `/analysis/capital` | Capital analysis from a JSON body |
| POST | `/analysis/countries` | Country comparison from a JSON body |
| GET | `/forecast/models` | Available forecasting models |
| GET | `/forecast/{gdp\|industry\|investment}/{country}?indicator=&horizon=&model=` | Forecast |
| GET | `/simulation/presets` | Named scenarios |
| GET | `/simulation/baseline/{country}` | Starting point of a simulation |
| POST | `/simulation/investment` | Simulation with custom adjustments |
| POST | `/simulation/scenario` | Simulation with a preset (extra adjustments allowed) |

Errors are returned as `{"error": "..."}` with these status codes: `400` for invalid input, `502` when a data source is unreachable and no cache exists, `500` for file errors.

## 8. Configuration

Settings are in `backend/src/main/resources/application.properties`.

| Property | Default | Meaning |
|---|---|---|
| `server.port` | `8080` | HTTP port (`start.sh` overrides it with a free port) |
| `spring.web.resources.static-locations` | `file:../frontend/` | Where the dashboard files are served from |
| `platform.cache.dir` | `../cache` | Cache folder |
| `platform.results.dir` | `../results` | Results folder |
| `platform.cache.ttl-hours` | `72` | Cache lifetime |
| `platform.api.worldbank.base-url` | World Bank v2 URL | World Bank API |
| `platform.api.imf.base-url` | IMF DataMapper URL | IMF API |
| `platform.api.oecd.base-url` | OECD SDMX URL | Reserved, not used yet |
| `platform.http.connect-timeout-ms` | `5000` | Connection timeout |
| `platform.http.read-timeout-ms` | `15000` | Read timeout |

Paths are relative to the `backend/` directory, so the backend must be started from there (`start.sh` does this).

## 9. Frontend

The dashboard is a single page with six sections (National Overview, Industry Analysis, Capital Allocation, Comparison, Forecasting, Simulation). It uses plain JavaScript and Chart.js (loaded from a CDN), follows the system light/dark theme, and is served by the backend itself, so it needs no separate web server.

## 10. Design decisions

- **No database.** Public data is re-downloadable, so a JSON file cache is enough and keeps deployment trivial.
- **Derived values are computed, never stored.** Ratios such as ROI or trade balance are calculated from raw values, so the data cannot contradict itself.
- **Honest labelling.** Historical, estimated and forecasted values are always distinguished, and simulation results always carry a disclaimer.
- **Graceful degradation.** A missing indicator is reported in an `unavailable` list instead of failing the whole report.
- **Nested classes.** Subclasses live inside their module's main class to keep the project compact.

## 11. Limitations

- Forecasts are statistical extrapolations of past data; they do not model shocks, policy changes or structural breaks.
- Simulation parameters (investment efficiency, employment elasticity, capital intensity per sector) are **illustrative assumptions**, not measured values. Results show what follows from the assumptions, not what will happen.
- Public data covers only broad sectors, and indicators may end in different years for different countries.
- Government expenditure is approximated by general government final consumption (% of GDP).
- There is no automated test suite in this version.

See `report.md` for the methodology and a fuller discussion.

## 12. Quick start

Requirements: JDK 21 or newer, Maven 3.6.3 or newer, an internet connection.

```bash
./start.sh      # finds a free port, starts the backend, opens the dashboard
./stop.sh       # stops the backend
```

Manual alternative:

```bash
cd backend
mvn spring-boot:run     # then open http://localhost:8080
```

## 13. Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| `Failed to connect to localhost` | The backend is not running; start it with `./start.sh`. |
| HTTP 502 `Data source unavailable` | World Bank / IMF unreachable and no cache yet; check the connection and retry. |
| Empty chart or `unavailable` list | The source has no data for that country or indicator. |
| Blank page | `frontend/index.html` is empty, or the page was opened as a file instead of through `http://localhost:<port>`. |
| Stale numbers | Call `DELETE /api/cache` or wait for the cache to expire. |

## 14. Documentation

- `report.md` - research report: methods, formulas, assumptions, limitations.
- `references.md` - data sources, literature and tools.

## 15. License and data terms

The source code is released under the MIT License (see `LICENSE.md`).

Data downloaded from the World Bank and the IMF remains subject to the terms of use of those organisations; check them before redistributing any data or results.

## 16. Disclaimer

This project is for research and education. Nothing it produces is investment, financial or policy advice.