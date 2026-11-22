package com.fintech.platform.simulation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fintech.platform.research.ResearchModule;
import com.fintech.platform.research.ResearchModule.Metric;
import com.fintech.platform.research.ResearchModule.Report;
import com.fintech.platform.research.ResearchModule.SectorShare;

/**
 * Investment scenario simulation (assumption-based, NOT a causal prediction).
 *
 * Main class: SimulationModule (facade used by the controller).
 * Nested subclasses: InvestmentSimulation, EconomicScenario.
 *
 * Both extend the abstract class Simulation, which runs one shared engine:
 *   1. Split total investment between sectors (by value added x capital intensity).
 *   2. Apply the requested investment changes (extra capital, or reallocated from other sectors).
 *   3. Each year: extra capital stock = previous x (1 - depreciation) + extra investment.
 *   4. Extra output per sector = extra capital x sector efficiency / ICOR.
 *   5. Extra employment per sector = employment elasticity x % change in sector output.
 *
 * InvestmentSimulation takes custom adjustments; EconomicScenario can also start from a named preset.
 * The per-sector parameters in the Sector enum are ILLUSTRATIVE defaults, not measured values.
 */
@Component
public class SimulationModule {

    private static final int DEFAULT_HORIZON = 10;
    private static final int MAX_HORIZON = 30;
    private static final double DEFAULT_DEPRECIATION_PCT = 5.0;
    private static final double DEFAULT_GROWTH_PCT = 3.0;
    private static final double DEFAULT_ICOR = 4.0;
    private static final Set<String> FUNDING_MODES = Set.of("additional", "reallocate");

    private static final String DISCLAIMER =
            "Assumption-based simulation, not a prediction. Results depend on illustrative sector parameters "
                    + "and a simple capital-output model; real economies have many effects not modelled here.";

    // =====================================================================
    // Sectors and their (illustrative) parameters
    // =====================================================================

    public enum Sector {
        //                label, investment efficiency, employment elasticity, capital intensity
        AGRICULTURE("Agriculture", 0.7, 0.3, 0.6),
        MANUFACTURING("Manufacturing", 1.2, 0.5, 1.3),
        MINING("Mining", 0.9, 0.3, 2.5),
        OTHER_INDUSTRY("Construction, energy & utilities", 0.9, 0.7, 1.5),
        SERVICES("Services (incl. technology, tourism, finance)", 1.1, 0.7, 0.8);

        private final String label;
        private final double investmentEfficiency;
        private final double employmentElasticity;
        private final double capitalIntensity;

        Sector(String label, double investmentEfficiency, double employmentElasticity, double capitalIntensity) {
            this.label = label;
            this.investmentEfficiency = investmentEfficiency;
            this.employmentElasticity = employmentElasticity;
            this.capitalIntensity = capitalIntensity;
        }

        public String label() {
            return label;
        }

        /** Output per unit of capital relative to the economy average (1.0 = average). */
        public double investmentEfficiency() {
            return investmentEfficiency;
        }

        /** % change in employment per 1% change in output. */
        public double employmentElasticity() {
            return employmentElasticity;
        }

        /** Relative share of investment a sector attracts for the same value added. */
        public double capitalIntensity() {
            return capitalIntensity;
        }

        public static Sector fromKey(String key) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Sector must not be empty");
            }
            String normalized = key.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            for (Sector sector : values()) {
                if (sector.name().equals(normalized)) {
                    return sector;
                }
            }
            throw new IllegalArgumentException("Unknown sector '" + key + "'. Available: " + Arrays.toString(values()));
        }
    }

    // =====================================================================
    // Input records
    // =====================================================================

    /** Change of one sector's investment, in percent (e.g. 10 = +10%, -5 = -5%). */
    public record Adjustment(String sector, double changePct) {
    }

    /**
     * Scenario request. Only country and (adjustments or preset) are required.
     *
     * fundingMode: "additional" (new capital is added) or "reallocate" (the net change is taken
     * from / given to the sectors that are NOT adjusted, so total investment stays the same).
     */
    public record ScenarioRequest(
            String country,
            String preset,
            List<Adjustment> adjustments,
            String fundingMode,
            Integer horizonYears,
            Double depreciationPct,
            Double baselineGrowthPct,
            Double icor) {
    }

    public record SectorBaseline(Sector sector, double valueAddedPct, double valueUsd, double employmentPct) {
    }

    public record Baseline(
            String country,
            int year,
            double gdpUsd,
            double investmentPctGdp,
            double investmentUsd,
            double baselineGrowthPct,
            double icor,
            List<SectorBaseline> sectors,
            List<String> notes) {
    }

    public record Preset(String key, String name, String description, String fundingMode,
            List<Adjustment> adjustments) {
    }

    // =====================================================================
    // Output records
    // =====================================================================

    public record SectorAssumption(String sector, double valueAddedPct, double employmentPct,
            double investmentEfficiency, double employmentElasticity, double capitalIntensity) {
    }

    public record Assumptions(
            double baselineGdpUsd,
            double investmentUsd,
            double investmentPctGdp,
            double baselineGrowthPct,
            double icor,
            double depreciationPct,
            List<String> baselineNotes,
            List<SectorAssumption> sectorParameters) {
    }

    public record SectorImpact(
            String sector,
            String label,
            double baselineInvestment,
            double scenarioInvestment,
            double investmentChangePct,
            double baselineValueAtHorizon,
            double scenarioValueAtHorizon,
            double outputImpactPct,
            double employmentImpactPct) {
    }

    public record YearlyPoint(
            int year,
            double baselineGdp,
            double scenarioGdp,
            double gdpImpact,
            double gdpImpactPct,
            double employmentImpactPct) {
    }

    public record Summary(
            double extraInvestmentPerYear,
            double cumulativeExtraInvestment,
            double gdpImpactAtHorizon,
            double gdpImpactPctAtHorizon,
            double employmentImpactPctAtHorizon,
            double baselineGrowthPct,
            double scenarioGrowthPct,
            Double outputPerExtraInvestment,
            double efficiencyIndexBaseline,
            double efficiencyIndexScenario) {
    }

    public record ScenarioResult(
            String country,
            String scenario,
            String fundingMode,
            int horizonYears,
            int baselineYear,
            Assumptions assumptions,
            List<Adjustment> adjustments,
            Summary summary,
            List<SectorImpact> sectors,
            List<YearlyPoint> yearly,
            String disclaimer) {
    }

    // =====================================================================
    // Abstraction: shared engine
    // =====================================================================

    public abstract static class Simulation {

        private final String defaultName;

        protected Simulation(String defaultName) {
            this.defaultName = defaultName;
        }

        public final ScenarioResult run(Baseline baseline, ScenarioRequest request) {
            if (baseline == null || request == null) {
                throw new IllegalArgumentException("Baseline and request must not be null");
            }
            ScenarioRequest effective = prepare(request);
            return simulate(baseline, effective, scenarioName(request));
        }

        /** Hook: lets a subclass turn the request into the final list of adjustments. */
        protected ScenarioRequest prepare(ScenarioRequest request) {
            return request;
        }

        protected String scenarioName(ScenarioRequest request) {
            return defaultName;
        }

        private static ScenarioResult simulate(Baseline b, ScenarioRequest r, String name) {
            // ---------- validate and read parameters ----------
            List<Adjustment> requested = r.adjustments() == null ? List.of() : r.adjustments();
            if (requested.isEmpty()) {
                throw new IllegalArgumentException("At least one investment adjustment is required");
            }

            int horizon = r.horizonYears() == null ? DEFAULT_HORIZON : r.horizonYears();
            if (horizon < 1 || horizon > MAX_HORIZON) {
                throw new IllegalArgumentException("Horizon must be between 1 and " + MAX_HORIZON + " years");
            }

            double depreciationPct = r.depreciationPct() == null ? DEFAULT_DEPRECIATION_PCT : r.depreciationPct();
            if (!(depreciationPct >= 0 && depreciationPct <= 50)) {
                throw new IllegalArgumentException("Depreciation must be between 0 and 50 percent");
            }

            double growthPct = r.baselineGrowthPct() == null ? b.baselineGrowthPct() : r.baselineGrowthPct();
            if (!(growthPct >= -20 && growthPct <= 30)) {
                throw new IllegalArgumentException("Baseline growth must be between -20 and 30 percent");
            }

            double icor = r.icor() == null ? b.icor() : r.icor();
            if (!(icor >= 0.5 && icor <= 20)) {
                throw new IllegalArgumentException("ICOR must be between 0.5 and 20");
            }

            String mode = (r.fundingMode() == null || r.fundingMode().isBlank())
                    ? "additional" : r.fundingMode().trim().toLowerCase(Locale.ROOT);
            if (!FUNDING_MODES.contains(mode)) {
                throw new IllegalArgumentException("fundingMode must be one of " + FUNDING_MODES);
            }

            EnumMap<Sector, Double> changes = new EnumMap<>(Sector.class);
            for (Adjustment adjustment : requested) {
                if (adjustment == null) {
                    throw new IllegalArgumentException("Adjustment must not be null");
                }
                Sector sector = Sector.fromKey(adjustment.sector());
                double change = adjustment.changePct();
                if (!(change > -100 && change <= 500)) {
                    throw new IllegalArgumentException(
                            "Change of " + sector + " must be above -100% and at most +500%");
                }
                if (changes.put(sector, change) != null) {
                    throw new IllegalArgumentException("Sector " + sector + " is adjusted more than once");
                }
            }

            // ---------- baseline investment per sector ----------
            EnumMap<Sector, SectorBaseline> base = new EnumMap<>(Sector.class);
            for (SectorBaseline sectorBaseline : b.sectors()) {
                base.put(sectorBaseline.sector(), sectorBaseline);
            }
            for (Sector sector : changes.keySet()) {
                if (!base.containsKey(sector)) {
                    throw new IllegalArgumentException("Baseline has no data for sector " + sector);
                }
            }

            double weightSum = 0.0;
            for (SectorBaseline sb : base.values()) {
                weightSum += sb.valueUsd() * sb.sector().capitalIntensity();
            }
            if (weightSum <= 0 || b.investmentUsd() <= 0 || b.gdpUsd() <= 0) {
                throw new IllegalArgumentException("Baseline has no usable GDP or investment data");
            }

            EnumMap<Sector, Double> baseInvestment = new EnumMap<>(Sector.class);
            for (SectorBaseline sb : base.values()) {
                double weight = sb.valueUsd() * sb.sector().capitalIntensity();
                baseInvestment.put(sb.sector(), b.investmentUsd() * weight / weightSum);
            }

            // ---------- scenario investment per sector ----------
            EnumMap<Sector, Double> scenarioInvestment = new EnumMap<>(Sector.class);
            for (Sector sector : base.keySet()) {
                double change = changes.getOrDefault(sector, 0.0);
                scenarioInvestment.put(sector, baseInvestment.get(sector) * (1.0 + change / 100.0));
            }

            if ("reallocate".equals(mode)) {
                double net = 0.0;
                double pool = 0.0;
                for (Sector sector : base.keySet()) {
                    if (changes.containsKey(sector)) {
                        net += scenarioInvestment.get(sector) - baseInvestment.get(sector);
                    } else {
                        pool += baseInvestment.get(sector);
                    }
                }
                if (pool <= 0) {
                    throw new IllegalArgumentException(
                            "Reallocation needs at least one sector that is not adjusted");
                }
                for (Sector sector : base.keySet()) {
                    if (!changes.containsKey(sector)) {
                        double value = baseInvestment.get(sector) - net * baseInvestment.get(sector) / pool;
                        if (value < 0) {
                            throw new IllegalArgumentException(
                                    "The adjustments need more capital than the other sectors can provide");
                        }
                        scenarioInvestment.put(sector, value);
                    }
                }
            }

            EnumMap<Sector, Double> deltaInvestment = new EnumMap<>(Sector.class);
            double totalDelta = 0.0;
            for (Sector sector : base.keySet()) {
                double delta = scenarioInvestment.get(sector) - baseInvestment.get(sector);
                deltaInvestment.put(sector, delta);
                totalDelta += delta;
            }

            // ---------- year by year ----------
            double growth = growthPct / 100.0;
            double depreciation = depreciationPct / 100.0;
            double employmentWeightSum = 0.0;
            for (SectorBaseline sb : base.values()) {
                employmentWeightSum += sb.employmentPct();
            }

            EnumMap<Sector, Double> extraCapital = new EnumMap<>(Sector.class);
            EnumMap<Sector, Double> lastDeltaOutput = new EnumMap<>(Sector.class);
            for (Sector sector : base.keySet()) {
                extraCapital.put(sector, 0.0);
                lastDeltaOutput.put(sector, 0.0);
            }

            List<YearlyPoint> yearly = new ArrayList<>();
            double lastBaselineGdp = b.gdpUsd();
            double lastScenarioGdp = b.gdpUsd();
            double lastEmploymentImpact = 0.0;

            for (int t = 1; t <= horizon; t++) {
                double growthFactor = Math.pow(1.0 + growth, t);
                double baselineGdp = b.gdpUsd() * growthFactor;
                double deltaGdp = 0.0;
                double weightedEmployment = 0.0;

                for (SectorBaseline sb : base.values()) {
                    Sector sector = sb.sector();
                    double capital = extraCapital.get(sector) * (1.0 - depreciation) + deltaInvestment.get(sector);
                    extraCapital.put(sector, capital);

                    double deltaOutput = capital * sector.investmentEfficiency() / icor;
                    lastDeltaOutput.put(sector, deltaOutput);
                    deltaGdp += deltaOutput;

                    double baseValue = sb.valueUsd() * growthFactor;
                    double outputPct = baseValue > 0 ? deltaOutput / baseValue * 100.0 : 0.0;
                    weightedEmployment += sb.employmentPct() * sector.employmentElasticity() * outputPct;
                }

                double employmentImpact = employmentWeightSum > 0 ? weightedEmployment / employmentWeightSum : 0.0;
                lastBaselineGdp = baselineGdp;
                lastScenarioGdp = baselineGdp + deltaGdp;
                lastEmploymentImpact = employmentImpact;

                yearly.add(new YearlyPoint(b.year() + t, round(baselineGdp), round(lastScenarioGdp),
                        round(deltaGdp), round(deltaGdp / baselineGdp * 100.0), round(employmentImpact)));
            }

            // ---------- sector results at the horizon ----------
            double finalGrowthFactor = Math.pow(1.0 + growth, horizon);
            List<SectorImpact> sectorImpacts = new ArrayList<>();
            for (SectorBaseline sb : base.values()) {
                Sector sector = sb.sector();
                double baseValue = sb.valueUsd() * finalGrowthFactor;
                double deltaOutput = lastDeltaOutput.get(sector);
                double outputPct = baseValue > 0 ? deltaOutput / baseValue * 100.0 : 0.0;
                double baseInv = baseInvestment.get(sector);
                double investmentChangePct = baseInv > 0 ? (scenarioInvestment.get(sector) / baseInv - 1.0) * 100.0 : 0.0;

                sectorImpacts.add(new SectorImpact(sector.name(), sector.label(),
                        round(baseInv), round(scenarioInvestment.get(sector)), round(investmentChangePct),
                        round(baseValue), round(baseValue + deltaOutput),
                        round(outputPct), round(sector.employmentElasticity() * outputPct)));
            }

            // ---------- summary ----------
            double efficiencyBaseline = 0.0;
            double efficiencyScenario = 0.0;
            double scenarioInvestmentTotal = 0.0;
            for (Sector sector : base.keySet()) {
                efficiencyBaseline += baseInvestment.get(sector) * sector.investmentEfficiency();
                efficiencyScenario += scenarioInvestment.get(sector) * sector.investmentEfficiency();
                scenarioInvestmentTotal += scenarioInvestment.get(sector);
            }

            double gdpImpact = lastScenarioGdp - lastBaselineGdp;
            double cumulativeExtra = totalDelta * horizon;
            Double outputPerExtra = Math.abs(cumulativeExtra) < 1e-9 ? null : round(gdpImpact / cumulativeExtra);
            double scenarioGrowthPct = (Math.pow(lastScenarioGdp / b.gdpUsd(), 1.0 / horizon) - 1.0) * 100.0;

            Summary summary = new Summary(
                    round(totalDelta),
                    round(cumulativeExtra),
                    round(gdpImpact),
                    round(gdpImpact / lastBaselineGdp * 100.0),
                    round(lastEmploymentImpact),
                    round(growthPct),
                    round(scenarioGrowthPct),
                    outputPerExtra,
                    round(efficiencyBaseline / b.investmentUsd()),
                    round(efficiencyScenario / scenarioInvestmentTotal));

            List<SectorAssumption> parameters = new ArrayList<>();
            for (SectorBaseline sb : base.values()) {
                Sector sector = sb.sector();
                parameters.add(new SectorAssumption(sector.name(), round(sb.valueAddedPct()),
                        round(sb.employmentPct()), sector.investmentEfficiency(),
                        sector.employmentElasticity(), sector.capitalIntensity()));
            }

            Assumptions assumptions = new Assumptions(round(b.gdpUsd()), round(b.investmentUsd()),
                    round(b.investmentPctGdp()), round(growthPct), round(icor), round(depreciationPct),
                    b.notes(), parameters);

            List<Adjustment> normalized = new ArrayList<>();
            for (var entry : changes.entrySet()) {
                normalized.add(new Adjustment(entry.getKey().name(), entry.getValue()));
            }

            return new ScenarioResult(b.country(), name, mode, horizon, b.year(), assumptions,
                    normalized, summary, sectorImpacts, yearly, DISCLAIMER);
        }
    }

    // =====================================================================
    // InvestmentSimulation: custom adjustments
    // =====================================================================

    public static class InvestmentSimulation extends Simulation {

        public InvestmentSimulation() {
            super("Custom investment scenario");
        }
    }

    // =====================================================================
    // EconomicScenario: named presets (extra adjustments can be added on top)
    // =====================================================================

    public static class EconomicScenario extends Simulation {

        public EconomicScenario() {
            super("Economic scenario");
        }

        @Override
        protected ScenarioRequest prepare(ScenarioRequest request) {
            if (request.preset() == null || request.preset().isBlank()) {
                return request;
            }
            Preset preset = findPreset(request.preset());
            List<Adjustment> merged = new ArrayList<>(preset.adjustments());
            if (request.adjustments() != null) {
                merged.addAll(request.adjustments());
            }
            String mode = (request.fundingMode() == null || request.fundingMode().isBlank())
                    ? preset.fundingMode() : request.fundingMode();

            return new ScenarioRequest(request.country(), request.preset(), merged, mode,
                    request.horizonYears(), request.depreciationPct(), request.baselineGrowthPct(), request.icor());
        }

        @Override
        protected String scenarioName(ScenarioRequest request) {
            if (request.preset() == null || request.preset().isBlank()) {
                return super.scenarioName(request);
            }
            return findPreset(request.preset()).name();
        }
    }

    private static final List<Preset> PRESETS = List.of(
            new Preset("manufacturing-plus-10", "Manufacturing investment +10%",
                    "Raise manufacturing investment by 10% with additional capital.",
                    "additional", List.of(new Adjustment("MANUFACTURING", 10))),
            new Preset("mining-minus-5", "Mining investment -5%",
                    "Cut mining investment by 5%; the capital is not redeployed.",
                    "additional", List.of(new Adjustment("MINING", -5))),
            new Preset("services-plus-10", "Services investment +10%",
                    "Raise investment in services (technology, tourism, finance) by 10% with additional capital.",
                    "additional", List.of(new Adjustment("SERVICES", 10))),
            new Preset("manufacturing-plus-10-reallocated", "Manufacturing +10%, funded by other sectors",
                    "Raise manufacturing investment by 10%; total investment stays the same, so all other "
                            + "sectors are reduced proportionally.",
                    "reallocate", List.of(new Adjustment("MANUFACTURING", 10))));

    private static Preset findPreset(String key) {
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        for (Preset preset : PRESETS) {
            if (preset.key().equals(normalized)) {
                return preset;
            }
        }
        throw new IllegalArgumentException("Unknown preset '" + key + "'. Available: "
                + PRESETS.stream().map(Preset::key).toList());
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    // =====================================================================
    // Facade used by the controller
    // =====================================================================

    private final ResearchModule research;
    private final InvestmentSimulation investmentSimulation = new InvestmentSimulation();
    private final EconomicScenario economicScenario = new EconomicScenario();

    public SimulationModule(ResearchModule research) {
        this.research = research;
    }

    public List<Preset> presets() {
        return PRESETS;
    }

    /** Simulates custom adjustments, loading the baseline from public data. */
    public ScenarioResult simulateInvestment(ScenarioRequest request) {
        return investmentSimulation.run(loadBaseline(requireCountry(request)), request);
    }

    /** Simulates a preset scenario (extra adjustments are added on top of the preset). */
    public ScenarioResult simulateScenario(ScenarioRequest request) {
        return economicScenario.run(loadBaseline(requireCountry(request)), request);
    }

    /** Same as above, but with a baseline you already have (no network access). */
    public ScenarioResult simulateInvestment(Baseline baseline, ScenarioRequest request) {
        return investmentSimulation.run(baseline, request);
    }

    public ScenarioResult simulateScenario(Baseline baseline, ScenarioRequest request) {
        return economicScenario.run(baseline, request);
    }

    /** Builds the starting point of a simulation from World Bank data. */
    public Baseline loadBaseline(String country) {
        Report industryReport = research.researchIndustry(country);
        Report investmentReport = research.researchInvestment(country);
        List<String> notes = new ArrayList<>();

        Metric gdp = findMetric(industryReport.metrics(), "GDP");
        if (gdp == null || gdp.latestValue() == null || gdp.latestYear() == null) {
            throw new IllegalArgumentException("GDP data is not available for " + industryReport.country());
        }

        SectorShare agriculture = findSector(industryReport, "AGRICULTURE");
        SectorShare industry = findSector(industryReport, "INDUSTRY");
        SectorShare services = findSector(industryReport, "SERVICES");
        if (agriculture == null || industry == null || services == null
                || agriculture.pctOfGdp() == null || industry.pctOfGdp() == null || services.pctOfGdp() == null) {
            throw new IllegalArgumentException(
                    "Sector value-added data is not available for " + industryReport.country());
        }

        double agriculturePct = agriculture.pctOfGdp();
        double industryPct = industry.pctOfGdp();
        double servicesPct = services.pctOfGdp();

        double manufacturingPct = shareOf(findSector(industryReport, "MANUFACTURING"));
        double miningPct = shareOf(findSector(industryReport, "MINING"));
        manufacturingPct = Math.min(manufacturingPct, industryPct);
        miningPct = Math.min(miningPct, Math.max(0.0, industryPct - manufacturingPct));
        double otherIndustryPct = Math.max(0.0, industryPct - manufacturingPct - miningPct);
        notes.add("Industry is split into manufacturing, mining (mineral rents) and other industry "
                + "(construction, energy, utilities); the remainder is computed from World Bank shares.");

        // Employment shares (fall back to value-added shares when unavailable).
        boolean hasEmployment = agriculture.employmentPct() != null && industry.employmentPct() != null
                && services.employmentPct() != null;
        double agricultureEmployment = hasEmployment ? agriculture.employmentPct() : agriculturePct;
        double industryEmployment = hasEmployment ? industry.employmentPct() : industryPct;
        double servicesEmployment = hasEmployment ? services.employmentPct() : servicesPct;
        if (!hasEmployment) {
            notes.add("Employment shares unavailable; value-added shares are used as employment weights.");
        }

        double industryTotal = manufacturingPct + miningPct + otherIndustryPct;
        double gdpUsd = gdp.latestValue();

        List<SectorBaseline> sectors = new ArrayList<>();
        sectors.add(sectorBaseline(Sector.AGRICULTURE, agriculturePct, gdpUsd, agricultureEmployment));
        sectors.add(sectorBaseline(Sector.MANUFACTURING, manufacturingPct, gdpUsd,
                splitEmployment(industryEmployment, manufacturingPct, industryTotal)));
        sectors.add(sectorBaseline(Sector.MINING, miningPct, gdpUsd,
                splitEmployment(industryEmployment, miningPct, industryTotal)));
        sectors.add(sectorBaseline(Sector.OTHER_INDUSTRY, otherIndustryPct, gdpUsd,
                splitEmployment(industryEmployment, otherIndustryPct, industryTotal)));
        sectors.add(sectorBaseline(Sector.SERVICES, servicesPct, gdpUsd, servicesEmployment));

        Metric investment = findMetric(investmentReport.metrics(), "INVESTMENT_PCT_GDP");
        if (investment == null || investment.latestValue() == null) {
            throw new IllegalArgumentException(
                    "Investment data is not available for " + industryReport.country());
        }
        double investmentPct = investment.latestValue();

        Metric growth = findMetric(investmentReport.metrics(), "GDP_GROWTH");
        double growthPct = DEFAULT_GROWTH_PCT;
        if (growth != null && growth.average5y() != null) {
            growthPct = growth.average5y();
        } else {
            notes.add("GDP growth unavailable; default of " + DEFAULT_GROWTH_PCT + "% used.");
        }

        Metric icorMetric = findMetric(investmentReport.derived(), ResearchModule.ICOR_5Y);
        double icor = DEFAULT_ICOR;
        if (icorMetric != null && icorMetric.latestValue() != null) {
            icor = Math.max(0.5, Math.min(20.0, icorMetric.latestValue()));
        } else {
            notes.add("ICOR could not be computed; default of " + DEFAULT_ICOR + " used.");
        }

        notes.add("Baseline GDP is the latest World Bank value (current US$); sector shares and "
                + "investment rate may come from slightly different years.");

        return new Baseline(industryReport.country(), gdp.latestYear(), gdpUsd, investmentPct,
                gdpUsd * investmentPct / 100.0, growthPct, icor, sectors, notes);
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static String requireCountry(ScenarioRequest request) {
        if (request == null || request.country() == null || request.country().isBlank()) {
            throw new IllegalArgumentException("Country is required");
        }
        return request.country();
    }

    private static Metric findMetric(List<Metric> metrics, String key) {
        if (metrics == null) {
            return null;
        }
        for (Metric metric : metrics) {
            if (key.equals(metric.key())) {
                return metric;
            }
        }
        return null;
    }

    private static SectorShare findSector(Report report, String key) {
        if (report.sectors() == null) {
            return null;
        }
        for (SectorShare sector : report.sectors()) {
            if (key.equals(sector.sector())) {
                return sector;
            }
        }
        return null;
    }

    private static double shareOf(SectorShare share) {
        return (share == null || share.pctOfGdp() == null) ? 0.0 : Math.max(0.0, share.pctOfGdp());
    }

    private static double splitEmployment(double industryEmployment, double part, double total) {
        return total > 0 ? industryEmployment * part / total : 0.0;
    }

    private static SectorBaseline sectorBaseline(Sector sector, double valueAddedPct, double gdpUsd,
            double employmentPct) {
        return new SectorBaseline(sector, valueAddedPct, gdpUsd * valueAddedPct / 100.0, employmentPct);
    }
}