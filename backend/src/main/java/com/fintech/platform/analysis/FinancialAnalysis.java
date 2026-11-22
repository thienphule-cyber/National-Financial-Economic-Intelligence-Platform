package com.fintech.platform.analysis;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Financial analysis module.
 *
 * Main class: FinancialAnalysis (facade used by the controller).
 * Nested subclasses: IndustryAnalysis, CapitalAnalysis, CountryComparison.
 *
 * All analyses extend the abstract class Analysis, which defines the common
 * workflow (validate -> compute). Each subclass implements compute() in its
 * own way (polymorphism). Units are not enforced: results use the same units
 * as the input data.
 */
@Component
public class FinancialAnalysis {

    // =====================================================================
    // Input records
    // =====================================================================

    /** Financial figures of one industry in one country and year. */
    public record IndustryData(
            String industry,
            double revenue,
            double operatingExpenditure,
            double capitalInvestment,
            double employment,
            double gdpContributionPct,
            double exportContributionPct) {
    }

    /** Capital structure of one industry. Shares are percentages (0-100). */
    public record CapitalData(
            String industry,
            double capitalInvestment,
            double publicSharePct,
            double foreignSharePct,
            double revenue,
            double operatingExpenditure) {
    }

    /** National indicators of one country in one year. */
    public record CountryData(
            String country,
            double gdp,
            double gdpGrowthPct,
            double inflationPct,
            double unemploymentPct,
            double debtPctGdp,
            double investmentPctGdp,
            double fdi,
            double exports,
            double imports) {
    }

    // =====================================================================
    // Result records (null values are omitted from the JSON output)
    // =====================================================================

    public record IndustryResult(
            String industry,
            double profit,
            Double profitMarginPct,
            Double productivity,
            double gdpContributionPct,
            double exportContributionPct,
            int gdpRank) {
    }

    public record CapitalResult(
            String industry,
            double capitalInvestment,
            Double sharePctOfTotalCapital,
            double publicAmount,
            double privateAmount,
            double domesticAmount,
            double foreignAmount,
            Double capitalEfficiency,
            Double roiPct,
            int efficiencyRank) {
    }

    public record CountryResult(
            String country,
            double gdpGrowthPct,
            double tradeBalance,
            Double tradeBalancePctGdp,
            Double fdiPctGdp,
            double investmentPctGdp,
            double debtPctGdp,
            double miseryIndex,
            int growthRank) {
    }

    // =====================================================================
    // Abstraction: common behaviour of every analysis
    // =====================================================================

    public abstract static class Analysis<I, O> {

        private final String name;

        protected Analysis(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        /** Template method: validate the input, then run the specific computation. */
        public final List<O> run(List<I> input) {
            if (input == null || input.isEmpty()) {
                throw new IllegalArgumentException(name + ": input list must not be empty");
            }
            return compute(input);
        }

        protected abstract List<O> compute(List<I> input);

        /** Returns null when the denominator is zero, otherwise the rounded ratio. */
        protected static Double ratio(double numerator, double denominator) {
            if (denominator == 0) {
                return null;
            }
            return round(numerator / denominator);
        }

        protected static double round(double value) {
            return Math.round(value * 100.0) / 100.0;
        }
    }

    // =====================================================================
    // IndustryAnalysis: profitability, productivity, contribution ranking
    // =====================================================================

    public static class IndustryAnalysis extends Analysis<IndustryData, IndustryResult> {

        public IndustryAnalysis() {
            super("Industry Analysis");
        }

        @Override
        protected List<IndustryResult> compute(List<IndustryData> input) {
            List<IndustryData> sorted = new ArrayList<>(input);
            sorted.sort(Comparator.comparingDouble(IndustryData::gdpContributionPct).reversed());

            List<IndustryResult> results = new ArrayList<>();
            for (int i = 0; i < sorted.size(); i++) {
                IndustryData d = sorted.get(i);
                double profit = d.revenue() - d.operatingExpenditure();

                results.add(new IndustryResult(
                        d.industry(),
                        round(profit),
                        ratio(profit * 100.0, d.revenue()),      // profit margin (%)
                        ratio(d.revenue(), d.employment()),      // revenue per worker
                        d.gdpContributionPct(),
                        d.exportContributionPct(),
                        i + 1));
            }
            return results;
        }
    }

    // =====================================================================
    // CapitalAnalysis: capital split, capital efficiency, ROI
    // =====================================================================

    public static class CapitalAnalysis extends Analysis<CapitalData, CapitalResult> {

        public CapitalAnalysis() {
            super("Capital Analysis");
        }

        @Override
        protected List<CapitalResult> compute(List<CapitalData> input) {
            double totalCapital = 0;
            for (CapitalData d : input) {
                checkPercentage(d.industry(), "publicSharePct", d.publicSharePct());
                checkPercentage(d.industry(), "foreignSharePct", d.foreignSharePct());
                totalCapital += d.capitalInvestment();
            }

            // Efficiency = revenue generated per unit of capital invested.
            List<CapitalData> sorted = new ArrayList<>(input);
            sorted.sort(Comparator.comparingDouble(
                    (CapitalData d) -> d.capitalInvestment() == 0 ? 0 : d.revenue() / d.capitalInvestment())
                    .reversed());

            List<CapitalResult> results = new ArrayList<>();
            for (int i = 0; i < sorted.size(); i++) {
                CapitalData d = sorted.get(i);
                double capital = d.capitalInvestment();
                double publicAmount = capital * d.publicSharePct() / 100.0;
                double foreignAmount = capital * d.foreignSharePct() / 100.0;
                double profit = d.revenue() - d.operatingExpenditure();

                results.add(new CapitalResult(
                        d.industry(),
                        capital,
                        ratio(capital * 100.0, totalCapital),
                        round(publicAmount),
                        round(capital - publicAmount),
                        round(capital - foreignAmount),
                        round(foreignAmount),
                        ratio(d.revenue(), capital),
                        ratio(profit * 100.0, capital),          // ROI (%) = profit / capital
                        i + 1));
            }
            return results;
        }

        private static void checkPercentage(String industry, String field, double value) {
            if (value < 0 || value > 100) {
                throw new IllegalArgumentException(
                        "Capital Analysis: " + field + " of '" + industry + "' must be between 0 and 100");
            }
        }
    }

    // =====================================================================
    // CountryComparison: trade, FDI, misery index, growth ranking
    // =====================================================================

    public static class CountryComparison extends Analysis<CountryData, CountryResult> {

        public CountryComparison() {
            super("Country Comparison");
        }

        @Override
        protected List<CountryResult> compute(List<CountryData> input) {
            List<CountryData> sorted = new ArrayList<>(input);
            sorted.sort(Comparator.comparingDouble(CountryData::gdpGrowthPct).reversed());

            List<CountryResult> results = new ArrayList<>();
            for (int i = 0; i < sorted.size(); i++) {
                CountryData d = sorted.get(i);
                double tradeBalance = d.exports() - d.imports();

                results.add(new CountryResult(
                        d.country(),
                        d.gdpGrowthPct(),
                        round(tradeBalance),
                        ratio(tradeBalance * 100.0, d.gdp()),
                        ratio(d.fdi() * 100.0, d.gdp()),
                        d.investmentPctGdp(),
                        d.debtPctGdp(),
                        round(d.inflationPct() + d.unemploymentPct()),  // misery index
                        i + 1));
            }
            return results;
        }
    }

    // =====================================================================
    // Facade used by the controller
    // =====================================================================

    private final IndustryAnalysis industryAnalysis = new IndustryAnalysis();
    private final CapitalAnalysis capitalAnalysis = new CapitalAnalysis();
    private final CountryComparison countryComparison = new CountryComparison();

    public List<IndustryResult> analyzeIndustries(List<IndustryData> data) {
        return industryAnalysis.run(data);
    }

    public List<CapitalResult> analyzeCapital(List<CapitalData> data) {
        return capitalAnalysis.run(data);
    }

    public List<CountryResult> compareCountries(List<CountryData> data) {
        return countryComparison.run(data);
    }
}