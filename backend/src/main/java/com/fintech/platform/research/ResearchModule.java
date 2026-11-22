package com.fintech.platform.research;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.springframework.stereotype.Component;

import com.fintech.platform.data.DataManager;
import com.fintech.platform.data.DataManager.DataPoint;
import com.fintech.platform.data.DataManager.Indicator;

/**
 * Research module: gathers public data (World Bank) and summarises it.
 *
 * Main class: ResearchModule (facade used by the controller and the simulation).
 * Nested subclasses: GDPResearch, IndustryResearch, InvestmentResearch.
 *
 * Every research type extends the abstract class Research, which runs the common
 * workflow: collect data -> compose the report -> check that some data was found.
 * Subclasses decide which indicators to collect and which figures to derive.
 */
@Component
public class ResearchModule {

    // Keys of derived metrics (raw metrics use the Indicator name, e.g. "GDP").
    public static final String TRADE_BALANCE_USD = "TRADE_BALANCE_USD";
    public static final String TRADE_BALANCE_PCT_GDP = "TRADE_BALANCE_PCT_GDP";
    public static final String MISERY_INDEX = "MISERY_INDEX";
    public static final String GDP_CAGR_10Y_PCT = "GDP_CAGR_10Y_PCT";
    public static final String TOURISM_RECEIPTS_PCT_GDP = "TOURISM_RECEIPTS_PCT_GDP";
    public static final String INVESTMENT_USD = "INVESTMENT_USD";
    public static final String GOV_EXPENDITURE_USD = "GOV_EXPENDITURE_USD";
    public static final String FDI_SHARE_OF_INVESTMENT_PCT = "FDI_SHARE_OF_INVESTMENT_PCT";
    public static final String ICOR_5Y = "ICOR_5Y";

    private static final Pattern COUNTRY_CODE = Pattern.compile("^[A-Za-z]{3}$");
    private static final int RECENT_YEARS = 5;

    // =====================================================================
    // Output records
    // =====================================================================

    /** One figure: latest value, 5-year average and short-term direction. */
    public record Metric(
            String key,
            String label,
            String unit,
            Integer latestYear,
            Double latestValue,
            Double average5y,
            String trend) {
    }

    /** Contribution of one broad sector to the economy. */
    public record SectorShare(
            String sector,
            String label,
            Integer year,
            Double pctOfGdp,
            Double estimatedValueUsd,
            Double employmentPct,
            Double relativeProductivity) {
    }

    public record Report(
            String country,
            String researchType,
            List<Metric> metrics,
            List<Metric> derived,
            List<SectorShare> sectors,
            List<String> unavailable,
            String note) {
    }

    // =====================================================================
    // Request context (collects data and problems for one research run)
    // =====================================================================

    static final class Context {

        final DataManager data;
        final String country;
        final List<String> unavailable = new ArrayList<>();
        RuntimeException firstError;
        int loaded;

        Context(DataManager data, String country) {
            this.data = data;
            this.country = country;
        }

        /** Never throws: a failing indicator is recorded and an empty list is returned. */
        List<DataPoint> series(Indicator indicator) {
            try {
                List<DataPoint> series = data.getSeries(country, indicator);
                if (series.isEmpty()) {
                    unavailable.add(indicator.name());
                    return List.of();
                }
                loaded++;
                return series;
            } catch (RuntimeException e) {
                if (firstError == null) {
                    firstError = e;
                }
                unavailable.add(indicator.name());
                return List.of();
            }
        }

        /** If nothing at all could be loaded, report the real cause. */
        void requireData() {
            if (loaded == 0) {
                if (firstError != null) {
                    throw firstError;
                }
                throw new IllegalArgumentException("No data available for country " + country);
            }
        }
    }

    // =====================================================================
    // Abstraction: common workflow of every research type
    // =====================================================================

    public abstract static class Research {

        private final String type;

        protected Research(String type) {
            this.type = type;
        }

        public final Report run(DataManager data, String countryCode) {
            if (countryCode == null || !COUNTRY_CODE.matcher(countryCode.trim()).matches()) {
                throw new IllegalArgumentException("Country must be a 3-letter ISO code, e.g. VNM, AUS, USA");
            }
            Context ctx = new Context(data, countryCode.trim().toUpperCase());
            Report report = compose(ctx);
            ctx.requireData();
            return report;
        }

        protected abstract Report compose(Context ctx);

        protected final Report report(Context ctx, List<Metric> metrics, List<Metric> derived,
                List<SectorShare> sectors, String note) {
            return new Report(ctx.country, type, metrics, derived, sectors, List.copyOf(ctx.unavailable), note);
        }

        /** Loads one indicator, adds its metric to the list and returns the raw series. */
        protected final List<DataPoint> collect(Context ctx, List<Metric> metrics, Indicator indicator) {
            List<DataPoint> series = ctx.series(indicator);
            Metric metric = metric(indicator, series);
            if (metric != null) {
                metrics.add(metric);
            }
            return series;
        }
    }

    // =====================================================================
    // GDPResearch: national overview
    // =====================================================================

    public static class GDPResearch extends Research {

        public GDPResearch() {
            super("National Overview");
        }

        @Override
        protected Report compose(Context ctx) {
            List<Metric> metrics = new ArrayList<>();
            List<DataPoint> gdp = collect(ctx, metrics, Indicator.GDP);
            collect(ctx, metrics, Indicator.GDP_GROWTH);
            collect(ctx, metrics, Indicator.GDP_PER_CAPITA);
            List<DataPoint> inflation = collect(ctx, metrics, Indicator.INFLATION);
            List<DataPoint> unemployment = collect(ctx, metrics, Indicator.UNEMPLOYMENT);
            collect(ctx, metrics, Indicator.DEBT_PCT_GDP);
            collect(ctx, metrics, Indicator.GOV_EXPENDITURE_PCT_GDP);
            collect(ctx, metrics, Indicator.INVESTMENT_PCT_GDP);
            collect(ctx, metrics, Indicator.FDI_NET_INFLOWS);
            List<DataPoint> exports = collect(ctx, metrics, Indicator.EXPORTS);
            List<DataPoint> imports = collect(ctx, metrics, Indicator.IMPORTS);

            List<Metric> derived = new ArrayList<>();

            Integer tradeYear = latestCommonYear(exports, imports);
            if (tradeYear != null) {
                Double balance = valueAt(exports, tradeYear) - valueAt(imports, tradeYear);
                addIfPresent(derived, derivedMetric(TRADE_BALANCE_USD,
                        "Trade balance (exports - imports)", "USD", tradeYear, balance));
                addIfPresent(derived, derivedMetric(TRADE_BALANCE_PCT_GDP,
                        "Trade balance (% of GDP)", "% of GDP", tradeYear,
                        percentOf(balance, valueAt(gdp, tradeYear))));
            }

            Integer miseryYear = latestCommonYear(inflation, unemployment);
            if (miseryYear != null) {
                addIfPresent(derived, derivedMetric(MISERY_INDEX,
                        "Misery index (inflation + unemployment)", "points", miseryYear,
                        valueAt(inflation, miseryYear) + valueAt(unemployment, miseryYear)));
            }

            addIfPresent(derived, derivedMetric(GDP_CAGR_10Y_PCT,
                    "GDP compound annual growth, 10 years (current US$)", "%",
                    latestYear(gdp), cagr(gdp, 10)));

            return report(ctx, metrics, derived, null,
                    "Source: World Bank. Latest values are the most recent year with published data; "
                            + "different indicators may end in different years.");
        }
    }

    // =====================================================================
    // IndustryResearch: sector structure of the economy
    // =====================================================================

    public static class IndustryResearch extends Research {

        public IndustryResearch() {
            super("Industry Structure");
        }

        @Override
        protected Report compose(Context ctx) {
            List<Metric> metrics = new ArrayList<>();
            List<DataPoint> gdp = collect(ctx, metrics, Indicator.GDP);
            List<DataPoint> agriculture = collect(ctx, metrics, Indicator.AGRICULTURE_PCT_GDP);
            List<DataPoint> industry = collect(ctx, metrics, Indicator.INDUSTRY_PCT_GDP);
            List<DataPoint> manufacturing = collect(ctx, metrics, Indicator.MANUFACTURING_PCT_GDP);
            List<DataPoint> services = collect(ctx, metrics, Indicator.SERVICES_PCT_GDP);
            List<DataPoint> mining = collect(ctx, metrics, Indicator.MINERAL_RENTS_PCT_GDP);
            List<DataPoint> tourism = collect(ctx, metrics, Indicator.TOURISM_RECEIPTS);
            List<DataPoint> employmentAgriculture = collect(ctx, metrics, Indicator.EMPLOYMENT_AGRICULTURE_PCT);
            List<DataPoint> employmentIndustry = collect(ctx, metrics, Indicator.EMPLOYMENT_INDUSTRY_PCT);
            List<DataPoint> employmentServices = collect(ctx, metrics, Indicator.EMPLOYMENT_SERVICES_PCT);

            List<SectorShare> sectors = new ArrayList<>();
            addSector(sectors, "AGRICULTURE", "Agriculture, forestry & fishing",
                    agriculture, employmentAgriculture, gdp);
            addSector(sectors, "INDUSTRY", "Industry (incl. construction, energy & mining)",
                    industry, employmentIndustry, gdp);
            addSector(sectors, "MANUFACTURING", "Manufacturing (part of industry)",
                    manufacturing, List.of(), gdp);
            addSector(sectors, "MINING", "Mineral rents (resource rents, proxy for mining)",
                    mining, List.of(), gdp);
            addSector(sectors, "SERVICES", "Services (incl. technology, tourism & financial services)",
                    services, employmentServices, gdp);

            List<Metric> derived = new ArrayList<>();
            Integer tourismYear = latestCommonYear(tourism, gdp);
            if (tourismYear != null) {
                addIfPresent(derived, derivedMetric(TOURISM_RECEIPTS_PCT_GDP,
                        "International tourism receipts (% of GDP)", "% of GDP", tourismYear,
                        percentOf(valueAt(tourism, tourismYear), valueAt(gdp, tourismYear))));
            }

            return report(ctx, metrics, derived, sectors,
                    "Source: World Bank. Public data only covers broad sectors (agriculture, industry, "
                            + "manufacturing, services). Finer industries such as technology, healthcare, energy or "
                            + "financial services are not available, and mining uses mineral rents as a proxy. "
                            + "Relative productivity = share of GDP / share of employment "
                            + "(above 1 means the sector produces more than its share of jobs).");
        }

        private static void addSector(List<SectorShare> sectors, String key, String label,
                List<DataPoint> share, List<DataPoint> employment, List<DataPoint> gdp) {
            Integer year = latestYear(share);
            Double pct = latestValue(share);
            if (year == null || pct == null) {
                return;
            }
            Double gdpAtYear = valueAt(gdp, year);
            Double estimatedValue = gdpAtYear == null ? null : pct * gdpAtYear / 100.0;
            Double employmentPct = latestValue(employment);
            Double productivity = (employmentPct == null || employmentPct <= 0) ? null : pct / employmentPct;

            sectors.add(new SectorShare(key, label, year, round(pct),
                    estimatedValue == null ? null : round(estimatedValue),
                    employmentPct == null ? null : round(employmentPct),
                    productivity == null ? null : round(productivity)));
        }
    }

    // =====================================================================
    // InvestmentResearch: investment, FDI and capital efficiency
    // =====================================================================

    public static class InvestmentResearch extends Research {

        public InvestmentResearch() {
            super("Investment & Capital");
        }

        @Override
        protected Report compose(Context ctx) {
            List<Metric> metrics = new ArrayList<>();
            List<DataPoint> investment = collect(ctx, metrics, Indicator.INVESTMENT_PCT_GDP);
            List<DataPoint> government = collect(ctx, metrics, Indicator.GOV_EXPENDITURE_PCT_GDP);
            List<DataPoint> fdi = collect(ctx, metrics, Indicator.FDI_NET_INFLOWS);
            collect(ctx, metrics, Indicator.FDI_PCT_GDP);
            List<DataPoint> growth = collect(ctx, metrics, Indicator.GDP_GROWTH);
            List<DataPoint> gdp = collect(ctx, metrics, Indicator.GDP);
            collect(ctx, metrics, Indicator.DEBT_PCT_GDP);

            List<Metric> derived = new ArrayList<>();

            Integer investmentYear = latestCommonYear(investment, gdp);
            if (investmentYear != null) {
                addIfPresent(derived, derivedMetric(INVESTMENT_USD,
                        "Gross capital formation (estimated, US$)", "USD", investmentYear,
                        valueAt(investment, investmentYear) * valueAt(gdp, investmentYear) / 100.0));
            }

            Integer governmentYear = latestCommonYear(government, gdp);
            if (governmentYear != null) {
                addIfPresent(derived, derivedMetric(GOV_EXPENDITURE_USD,
                        "Government final consumption (estimated, US$)", "USD", governmentYear,
                        valueAt(government, governmentYear) * valueAt(gdp, governmentYear) / 100.0));
            }

            Integer fdiYear = latestCommonYear(fdi, investment, gdp);
            if (fdiYear != null) {
                double investmentUsd = valueAt(investment, fdiYear) * valueAt(gdp, fdiYear) / 100.0;
                addIfPresent(derived, derivedMetric(FDI_SHARE_OF_INVESTMENT_PCT,
                        "FDI net inflows as share of total investment", "%", fdiYear,
                        percentOf(valueAt(fdi, fdiYear), investmentUsd)));
            }

            // ICOR = investment rate / growth rate. Lower means capital is used more efficiently.
            Double averageInvestment = average(investment, RECENT_YEARS);
            Double averageGrowth = average(growth, RECENT_YEARS);
            if (averageInvestment != null && averageGrowth != null && averageGrowth > 0.5) {
                addIfPresent(derived, derivedMetric(ICOR_5Y,
                        "Incremental capital-output ratio (5-year averages)", "ratio",
                        latestYear(growth), averageInvestment / averageGrowth));
            }

            return report(ctx, metrics, derived, null,
                    "Source: World Bank. Sector-level capital data is not public, so efficiency is measured "
                            + "for the whole economy with the ICOR (investment rate / GDP growth rate). "
                            + "ICOR is skipped when average growth is 0.5% or lower.");
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static Integer latestYear(List<DataPoint> series) {
        return series.isEmpty() ? null : series.get(series.size() - 1).year();
    }

    private static Double latestValue(List<DataPoint> series) {
        return series.isEmpty() ? null : series.get(series.size() - 1).value();
    }

    private static Double valueAt(List<DataPoint> series, int year) {
        for (DataPoint point : series) {
            if (point.year() == year) {
                return point.value();
            }
        }
        return null;
    }

    private static Double average(List<DataPoint> series, int count) {
        if (series.isEmpty()) {
            return null;
        }
        int n = Math.min(count, series.size());
        double sum = 0.0;
        for (int i = series.size() - n; i < series.size(); i++) {
            sum += series.get(i).value();
        }
        return sum / n;
    }

    /** Most recent year (of the first list) that also has a value in the other lists. */
    private static Integer latestCommonYear(List<DataPoint> first, List<DataPoint> second, List<DataPoint> third) {
        for (int i = first.size() - 1; i >= 0; i--) {
            int year = first.get(i).year();
            if (valueAt(second, year) != null && valueAt(third, year) != null) {
                return year;
            }
        }
        return null;
    }

    private static Integer latestCommonYear(List<DataPoint> first, List<DataPoint> second) {
        return latestCommonYear(first, second, second);
    }

    /** Compound annual growth rate (%) over the given number of years, or null. */
    private static Double cagr(List<DataPoint> series, int years) {
        if (series.isEmpty()) {
            return null;
        }
        DataPoint last = series.get(series.size() - 1);
        Double first = valueAt(series, last.year() - years);
        if (first == null || first <= 0 || last.value() <= 0) {
            return null;
        }
        return (Math.pow(last.value() / first, 1.0 / years) - 1.0) * 100.0;
    }

    /** Direction of the last 5 observations: rising, falling or stable. */
    private static String trend(List<DataPoint> series) {
        int n = Math.min(RECENT_YEARS, series.size());
        if (n < 3) {
            return null;
        }
        SimpleRegression regression = new SimpleRegression();
        double sumAbs = 0.0;
        for (int i = 0; i < n; i++) {
            double value = series.get(series.size() - n + i).value();
            regression.addData(i, value);
            sumAbs += Math.abs(value);
        }
        double slope = regression.getSlope();
        double scale = sumAbs / n;
        if (Double.isNaN(slope) || Math.abs(slope) <= 0.02 * Math.max(scale, 1e-9)) {
            return "stable";
        }
        return slope > 0 ? "rising" : "falling";
    }

    private static Metric metric(Indicator indicator, List<DataPoint> series) {
        if (series.isEmpty()) {
            return null;
        }
        Double average = average(series, RECENT_YEARS);
        return new Metric(indicator.name(), indicator.label(), indicator.unit(),
                latestYear(series), round(latestValue(series)),
                average == null ? null : round(average), trend(series));
    }

    private static Metric derivedMetric(String key, String label, String unit, Integer year, Double value) {
        if (year == null || value == null || value.isNaN() || value.isInfinite()) {
            return null;
        }
        return new Metric(key, label, unit, year, round(value), null, null);
    }

    private static void addIfPresent(List<Metric> list, Metric metric) {
        if (metric != null) {
            list.add(metric);
        }
    }

    private static Double percentOf(Double part, Double whole) {
        if (part == null || whole == null || whole == 0) {
            return null;
        }
        return part / whole * 100.0;
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    // =====================================================================
    // Facade used by the controller
    // =====================================================================

    private final DataManager dataManager;
    private final GDPResearch gdpResearch = new GDPResearch();
    private final IndustryResearch industryResearch = new IndustryResearch();
    private final InvestmentResearch investmentResearch = new InvestmentResearch();

    public ResearchModule(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    public Report researchGdp(String country) {
        return gdpResearch.run(dataManager, country);
    }

    public Report researchIndustry(String country) {
        return industryResearch.run(dataManager, country);
    }

    public Report researchInvestment(String country) {
        return investmentResearch.run(dataManager, country);
    }
}