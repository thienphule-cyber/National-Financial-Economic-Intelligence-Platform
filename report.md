# National Financial & Economic Intelligence Platform: Research Report

**Author:** Phill Le
**Date:** November 2026
**Type:** Engineering portfolio project (FinTech, economic analytics)

---

## Abstract

This report describes the design, methods and limitations of the National Financial & Economic Intelligence Platform, a stand-alone analysis tool that studies how industries contribute to national economies and how capital is allocated between them. The platform downloads public macroeconomic data from the World Bank and the IMF, derives financial indicators, forecasts selected series with simple statistical models, and runs assumption-based investment scenarios. The emphasis is on transparency: every figure is computed from stated formulas, every data point is labelled as historical, estimated or forecasted, and simulation results are presented as consequences of explicit assumptions rather than predictions. The report documents the indicators, the formulas, the forecasting and simulation models, how the system was verified, and what the platform cannot do.

---

## 1. Introduction

### 1.1 Motivation

Understanding which industries drive an economy, and whether the capital invested in them is used efficiently, is a central question of economic analysis. The relevant data exists in public statistical databases, but it is spread across sources, uses technical indicator codes, and rarely comes with ready-made analytical tools. The project asks whether a compact, locally run platform can bring these pieces together: collect public data, analyse it, extend it forward in time and let a user test simple investment assumptions.

### 1.2 Objectives

1. Provide a national financial overview for any country covered by the data sources.
2. Show the contribution of broad economic sectors to output and employment.
3. Assess capital allocation and capital efficiency.
4. Compare countries and indicators over time.
5. Forecast selected series with transparent statistical models.
6. Simulate the effect of investment changes under explicit assumptions.
7. Keep the system simple enough to run locally, with no database, accounts or payments.

### 1.3 Scope and non-goals

The platform is an analysis tool. It is not a banking, payment or trading system, and it does not give investment, financial or policy advice. It does not claim causal conclusions about real economies.

---

## 2. Background

**Sector structure.** Economic development is commonly described as a shift of output and employment between primary, secondary and tertiary activities [R9, R10]. The share of a sector in GDP, compared with its share in employment, indicates whether the sector produces more or less per worker than the economy as a whole.

**Capital and growth.** Growth theory links investment to output growth [R6]. In the Harrod-Domar tradition, the capital-output ratio relates the capital added to the output it supports [R7, R8]. The incremental capital-output ratio (ICOR), the investment rate divided by the growth rate, is a standard summary of how much capital an economy needs per unit of growth. A lower ICOR suggests that capital is used more efficiently. The platform uses it because sector-level capital data is not publicly available.

**Forecasting.** For short annual series, simple methods (trend lines, averages and exponential smoothing with a damped trend) are standard baselines and are often hard to beat [R11, R12, R13, R14]. The platform uses them for transparency, not for maximum accuracy.

---

## 3. Data

### 3.1 Sources

| Source | Content | Reference |
|---|---|---|
| World Bank Indicators API | National accounts, prices, labour, trade, FDI, sector value added | R1, R2 |
| IMF DataMapper API | GDP growth, inflation, unemployment, debt, GDP in US$, including official projections | R3, R4 |

Data is downloaded on demand and cached locally. A cache entry is valid for 72 hours; if a download fails, an older cache entry is used.

### 3.2 Indicators

World Bank indicators:

| Key | Code | Description |
|---|---|---|
| GDP | NY.GDP.MKTP.CD | GDP (current US$) |
| GDP_GROWTH | NY.GDP.MKTP.KD.ZG | GDP growth (annual %) |
| GDP_PER_CAPITA | NY.GDP.PCAP.CD | GDP per capita (current US$) |
| INFLATION | FP.CPI.TOTL.ZG | Inflation, consumer prices (annual %) |
| UNEMPLOYMENT | SL.UEM.TOTL.ZS | Unemployment (% of labour force) |
| DEBT_PCT_GDP | GC.DOD.TOTL.GD.ZS | Central government debt (% of GDP) |
| GOV_EXPENDITURE_PCT_GDP | NE.CON.GOVT.ZS | Government final consumption (% of GDP) |
| INVESTMENT_PCT_GDP | NE.GDI.TOTL.ZS | Gross capital formation (% of GDP) |
| FDI_NET_INFLOWS | BX.KLT.DINV.CD.WD | FDI, net inflows (current US$) |
| FDI_PCT_GDP | BX.KLT.DINV.WD.GD.ZS | FDI, net inflows (% of GDP) |
| EXPORTS / IMPORTS | NE.EXP.GNFS.CD / NE.IMP.GNFS.CD | Exports / imports of goods and services (current US$) |
| EXPORTS_PCT_GDP / IMPORTS_PCT_GDP | NE.EXP.GNFS.ZS / NE.IMP.GNFS.ZS | Exports / imports (% of GDP) |
| AGRICULTURE_PCT_GDP | NV.AGR.TOTL.ZS | Agriculture value added (% of GDP) |
| INDUSTRY_PCT_GDP | NV.IND.TOTL.ZS | Industry including construction (% of GDP) |
| MANUFACTURING_PCT_GDP | NV.IND.MANF.ZS | Manufacturing value added (% of GDP) |
| SERVICES_PCT_GDP | NV.SRV.TOTL.ZS | Services value added (% of GDP) |
| MINERAL_RENTS_PCT_GDP | NY.GDP.MINR.RT.ZS | Mineral rents (% of GDP) |
| TOURISM_RECEIPTS | ST.INT.RCPT.CD | International tourism receipts (current US$) |
| EMPLOYMENT_AGRICULTURE_PCT | SL.AGR.EMPL.ZS | Employment in agriculture (% of total) |
| EMPLOYMENT_INDUSTRY_PCT | SL.IND.EMPL.ZS | Employment in industry (% of total) |
| EMPLOYMENT_SERVICES_PCT | SL.SRV.EMPL.ZS | Employment in services (% of total) |

IMF indicators: `NGDP_RPCH` (real GDP growth), `PCPIPCH` (inflation), `LUR` (unemployment rate), `GGXWDG_NGDP` (general government gross debt, % of GDP), `NGDPD` (GDP, billions of US$).

### 3.3 Data types

Each point is `historical` (published observation), `estimated` (interpolated gap, or the current-year IMF estimate) or `forecasted` (model output or official projection). Forecast models use only `historical` values as input.

### 3.4 Coverage limits

- Sector data is limited to agriculture, industry, manufacturing, services and mineral rents. Finer industries such as technology, healthcare, energy or financial services have no public equivalent in these sources, and mining is represented by mineral rents.
- Industry revenue, costs, capital investment and the public/private or domestic/foreign split by industry are not available. The platform accepts them as user input instead.
- Indicators end in different years, and small economies often have gaps. The platform reports missing indicators in an `unavailable` list instead of failing.
- Government expenditure is approximated by general government final consumption.

---

## 4. Methodology

### 4.1 National overview

For each indicator the platform reports the latest value and year, the average of the last five observations, and a trend. The trend is the slope of a least-squares line through the last up to five observations; it is "stable" if the absolute slope is at most 2% of the mean absolute value of those observations.

Derived indicators:

```
Trade balance            = Exports - Imports
Trade balance (% GDP)    = Trade balance / GDP x 100
Misery index             = Inflation + Unemployment
GDP CAGR (10 years)      = ((GDP_latest / GDP_10_years_earlier) ^ (1/10) - 1) x 100
Investment (US$)         = Investment rate x GDP / 100
Government spending (US$)= Government consumption rate x GDP / 100
FDI share of investment  = FDI net inflows / Investment (US$) x 100
Tourism receipts (% GDP) = Tourism receipts / GDP x 100
```

Derived values use the latest year for which all inputs exist.

### 4.2 Sector structure

For broad sectors the platform reports the share of GDP, the estimated value (share x GDP) and the share of employment. The relative productivity is:

```
Relative productivity = Sector share of GDP / Sector share of employment
```

A value above 1 means the sector produces more than its share of jobs. Manufacturing is a part of industry, and mining is proxied by mineral rents, so these rows overlap with others by construction.

### 4.3 Capital allocation and ICOR

```
ICOR (5-year) = Mean investment rate (last 5 observations) / Mean GDP growth (last 5 observations)
```

ICOR is not computed when average growth is 0.5% or lower, because the ratio becomes unstable. It describes the whole economy, not individual sectors.

### 4.4 Analysis of user-supplied data

Industry analysis (input: revenue, operating expenditure, capital, employment, GDP and export contribution):

```
Profit            = Revenue - Operating expenditure
Profit margin (%) = Profit / Revenue x 100
Productivity      = Revenue / Employment
```

Industries are ranked by GDP contribution.

Capital analysis (input: capital, public share %, foreign share %, revenue, operating expenditure):

```
Public amount     = Capital x Public share / 100       Private = Capital - Public
Foreign amount    = Capital x Foreign share / 100      Domestic = Capital - Foreign
Capital efficiency = Revenue / Capital
ROI (%)           = (Revenue - Operating expenditure) / Capital x 100
```

Industries are ranked by capital efficiency. Shares outside 0-100 are rejected.

Country comparison uses GDP growth, trade balance (also as % of GDP), FDI as % of GDP, investment rate, debt, and the misery index, and ranks countries by GDP growth. When it is built from live data, the latest available value of each indicator is used, so countries may be compared across different years. A missing indicator is treated as 0 and listed in a warning.

### 4.5 Forecasting

Series are first cleaned: invalid values are rejected and missing years are filled by linear interpolation (and labelled `estimated`). Three models are available.

**Linear trend** (at least 3 observations): an ordinary least-squares line `y = a + b t`, extended forward.

**Damped-trend exponential smoothing, Holt style** (at least 4 observations) [R11, R12, R13]:

```
forecast_t       = level_(t-1) + phi x trend_(t-1)
level_t          = alpha x y_t + (1 - alpha) x forecast_t
trend_t          = beta x (level_t - level_(t-1)) + (1 - beta) x phi x trend_(t-1)
forecast (h ahead) = level_n + (phi + phi^2 + ... + phi^h) x trend_n
```

The damping factor `phi` is fixed at 0.9. The smoothing parameters `alpha` and `beta` are chosen from {0.1, ..., 0.9} to minimise the in-sample sum of squared one-step errors.

**Moving average** (at least 3 observations): a flat forecast equal to the mean of the last 3 values.

Forecast types differ in their defaults and constraints:

| Type | Default model | Constraint |
|---|---|---|
| GDP | Holt (damped) | none (growth can be negative) |
| Industry | Linear | forecasts and bounds floored at 0 |
| Investment / FDI | Moving average | forecasts and bounds floored at 0 |

The platform reports the in-sample RMSE and an approximate 95% band:

```
band(h) = forecast_h +/- 1.96 x RMSE x sqrt(h)
```

This band is a heuristic. It uses in-sample error, assumes roughly normal and uncorrelated errors, and ignores parameter uncertainty and structural change, so it probably understates the true uncertainty. The maximum horizon is 20 years.

### 4.6 Scenario simulation

The simulation is a deliberately simple capital-output model with five sectors: agriculture, manufacturing, mining, other industry (construction, energy and utilities) and services.

**Baseline** (from public data): latest GDP, sector value-added shares, employment shares, investment rate, average growth of the last five years and the ICOR (limited to 0.5-20; defaults: growth 3%, ICOR 4, depreciation 5%). Industry is split into manufacturing, mining (mineral rents) and the remainder.

**Step 1 - baseline investment by sector.** Total investment `I` is split in proportion to value added times a capital-intensity weight:

```
w_s = V_s x c_s         I_s = I x w_s / sum(w)
```

**Step 2 - scenario investment.** A change of `x_s` percent gives `I'_s = I_s x (1 + x_s/100)`. In "additional" mode the extra capital is new. In "reallocate" mode the net change is taken from, or given to, the sectors that were not adjusted, in proportion to their investment, so total investment stays the same.

**Step 3 - capital and output.** With the investment difference `dI_s = I'_s - I_s` and depreciation `delta`:

```
dK_(s,t) = dK_(s,t-1) x (1 - delta) + dI_s        dK_(s,0) = 0
dY_(s,t) = dK_(s,t) x e_s / ICOR
Baseline GDP_t = GDP_0 x (1 + g)^t
```

where `e_s` is the sector's relative investment efficiency.

**Step 4 - employment.** The employment impact of a sector is its employment elasticity times the percentage change in its output. The aggregate impact is weighted by employment shares.

**Outputs:** GDP impact (absolute and %), employment impact, impact per sector and per year, growth before and after, output per unit of extra investment, and an investment efficiency index `sum(I_s x e_s) / sum(I_s)`.

**Illustrative sector parameters:**

| Sector | Investment efficiency | Employment elasticity | Capital intensity |
|---|---|---|---|
| Agriculture | 0.7 | 0.3 | 0.6 |
| Manufacturing | 1.2 | 0.5 | 1.3 |
| Mining | 0.9 | 0.3 | 2.5 |
| Other industry | 0.9 | 0.7 | 1.5 |
| Services | 1.1 | 0.7 | 0.8 |

These values are assumptions chosen to be plausible in relative terms (for example, mining is capital-intensive and creates few jobs). They are **not estimated from data**. Because the output scales linearly with them, the simulation results should be read as "what follows if these assumptions hold".

Named presets: manufacturing +10%, mining -5%, services +10%, and manufacturing +10% funded by the other sectors.

---

## 5. System design

The backend is a Spring Boot application exposing a REST API; the frontend is a static HTML/CSS/JavaScript dashboard using Chart.js [R15, R18]. Statistical routines use Apache Commons Math and files are written with Jackson and Apache Commons CSV [R16, R17].

The code is organised in six modules (data, research, analysis, forecasting, simulation, model) plus one controller. Each module has one main class with nested subclasses, and uses abstract base classes and interfaces for shared behaviour. The controller only translates HTTP requests and errors; all calculations live in the modules. See `README.md` for the architecture diagram, the project structure and the API reference.

---

## 6. Verification

### 6.1 What was done

- The platform was run and checked manually with several country codes. Each dashboard section loaded, and forecasts and simulations completed.
- Input validation was exercised: invalid country codes, unknown indicators or sectors, and out-of-range values return clear errors instead of failing silently.
- Failure behaviour was considered in the design: missing indicators are reported, and a stale cache is used when a source cannot be reached.

### 6.2 What was not done

- There is no automated test suite in this version.
- The forecasting models have not been back-tested, so their accuracy has not been measured.
- The simulation parameters have not been calibrated or validated against observed outcomes.
- Results were not systematically compared against the original source tables.

### 6.3 Suggested next checks

1. **Source cross-check:** compare dashboard values for several countries with the World Bank and IMF web tables.
2. **Back-test:** hold out the last 3-5 years, forecast them, and compare the three models with error measures such as MAE and MAPE [R14].
3. **Band coverage:** check how often held-out values fall inside the 95% band.
4. **Sensitivity analysis:** vary the simulation parameters and ICOR to see how strongly conclusions depend on them.
5. **Unit tests:** cover the formulas in `FinancialAnalysis`, the models in `ForecastingModule` and the allocation logic in `SimulationModule`.

---

## 7. Limitations

**Data.** Coverage differs by country and indicator. Sector detail is coarse, and latest values may come from different years. Public data may be revised, so results change over time.

**Forecasting.** Models extrapolate patterns in annual series with few observations. They cannot anticipate crises, policy changes, commodity shocks or structural breaks, and the uncertainty band is approximate.

**Simulation.** The model is linear, with a constant ICOR and fixed sector parameters. It ignores prices, exchange rates, crowding out, multiplier effects, trade, time lags in investment, and feedback between sectors. It treats investment as the only driver of change.

**Interpretation.** Correlation in the data is not causation. A simulated GDP gain is a property of the model's assumptions, not evidence that the same investment would produce that gain in reality.

**Indicators.** The misery index and ICOR are simple summaries. Government final consumption is only a proxy for total government expenditure.

---

## 8. Responsible use

The platform labels data types, shows warnings for missing data, and displays a disclaimer with every simulation. Its outputs should be used for learning and exploration only. They should not be used as the sole basis for investment, lending or policy decisions. Data from the World Bank and IMF must be used under their terms of use.

---

## 9. Future work

1. Add industry-level data from sources such as OECD [R5] to support finer sectors and sector-level capital analysis.
2. Back-test the forecasting models and add further methods (for example ETS with automatic selection or ARIMA), possibly with an optional Python/ML component.
3. Estimate simulation parameters from data and add sensitivity and Monte Carlo analysis.
4. Add automated tests and continuous integration.
5. Export reports (CSV, PDF) from the dashboard and add a country search with names instead of ISO codes only.

---

## 10. Conclusion

The platform shows that public data, a small set of transparent formulas and simple statistical models are enough to build a useful, locally run tool for exploring national finances, sector contributions and capital efficiency. Its main value lies in clarity: each number can be traced to a source or a formula, and each model states its assumptions. Its main weaknesses are the coarse sector data, the lack of back-testing, and the illustrative nature of the simulation parameters. These are the first things to address in later versions.

---
