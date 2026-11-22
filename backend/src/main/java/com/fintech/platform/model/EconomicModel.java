package com.fintech.platform.model;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Domain model of the platform.
 *
 * Main class: EconomicModel (abstract base: every model has an id and a name).
 * Nested subclasses: Country, Industry, FinancialIndicator, Investment.
 *
 * Encapsulation: all fields are private and validated in the constructors.
 * Polymorphism: every subclass provides its own getType() and describe().
 */
public abstract class EconomicModel {

    private final String id;
    private final String name;

    protected EconomicModel(String id, String name) {
        this.id = requireText(id, "id");
        this.name = requireText(name, "name");
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /** Short type label, e.g. "country" or "industry". */
    public abstract String getType();

    /** One-line human readable description. */
    public abstract String describe();

    protected static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        return value.trim();
    }

    protected static double requirePercentage(double value, String field) {
        if (Double.isNaN(value) || value < 0 || value > 100) {
            throw new IllegalArgumentException(field + " must be between 0 and 100");
        }
        return value;
    }

    protected static double requireNonNegative(double value, String field) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0) {
            throw new IllegalArgumentException(field + " must be a non-negative number");
        }
        return value;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || getClass() != other.getClass()) {
            return false;
        }
        return id.equals(((EconomicModel) other).id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(getClass(), id);
    }

    @Override
    public String toString() {
        return getType() + "[" + id + "] " + name;
    }

    // =====================================================================
    // Country
    // =====================================================================

    public static class Country extends EconomicModel {

        private final String region;
        private final String currency;
        private final Map<String, FinancialIndicator> indicators = new LinkedHashMap<>();

        public Country(String code, String name, String region, String currency) {
            super(normalizeCode(code), name);
            this.region = region == null ? "" : region.trim();
            this.currency = currency == null ? "" : currency.trim();
        }

        private static String normalizeCode(String code) {
            String text = requireText(code, "country code");
            if (!text.matches("[A-Za-z]{3}")) {
                throw new IllegalArgumentException("Country code must be a 3-letter ISO code, e.g. VNM");
            }
            return text.toUpperCase();
        }

        public String getRegion() {
            return region;
        }

        public String getCurrency() {
            return currency;
        }

        public void addIndicator(FinancialIndicator indicator) {
            Objects.requireNonNull(indicator, "indicator");
            indicators.put(indicator.getId(), indicator);
        }

        public Collection<FinancialIndicator> getIndicators() {
            return Collections.unmodifiableCollection(indicators.values());
        }

        public Optional<FinancialIndicator> findIndicator(String key) {
            return Optional.ofNullable(indicators.get(key));
        }

        @Override
        public String getType() {
            return "country";
        }

        @Override
        public String describe() {
            return getName() + " (" + getId() + ")" + (region.isEmpty() ? "" : ", " + region);
        }
    }

    // =====================================================================
    // Industry
    // =====================================================================

    public static class Industry extends EconomicModel {

        public enum Category {
            PRIMARY, SECONDARY, TERTIARY
        }

        private final Category category;
        private final String description;

        public Industry(String id, String name, Category category, String description) {
            super(id, name);
            this.category = Objects.requireNonNull(category, "category");
            this.description = description == null ? "" : description.trim();
        }

        public Category getCategory() {
            return category;
        }

        public String getDescription() {
            return description;
        }

        @Override
        public String getType() {
            return "industry";
        }

        @Override
        public String describe() {
            return getName() + " (" + category.name().toLowerCase() + " sector)";
        }
    }

    // =====================================================================
    // FinancialIndicator
    // =====================================================================

    public static class FinancialIndicator extends EconomicModel {

        private static final Set<String> DATA_TYPES = Set.of("historical", "estimated", "forecasted");

        /** One value of the indicator in one year. */
        public record Observation(int year, double value, String dataType) {
        }

        private final String unit;
        private final String source;
        private final TreeMap<Integer, Observation> observations = new TreeMap<>();

        public FinancialIndicator(String key, String label, String unit, String source) {
            super(key, label);
            this.unit = unit == null ? "" : unit.trim();
            this.source = source == null ? "" : source.trim();
        }

        public String getUnit() {
            return unit;
        }

        public String getSource() {
            return source;
        }

        public void addObservation(int year, double value, String dataType) {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IllegalArgumentException("Observation value must be a finite number");
            }
            if (!DATA_TYPES.contains(dataType)) {
                throw new IllegalArgumentException("dataType must be one of " + DATA_TYPES);
            }
            observations.put(year, new Observation(year, value, dataType));
        }

        public List<Observation> getObservations() {
            return List.copyOf(observations.values());
        }

        public Integer getLatestYear() {
            return observations.isEmpty() ? null : observations.lastKey();
        }

        public Double getLatestValue() {
            return observations.isEmpty() ? null : observations.lastEntry().getValue().value();
        }

        @Override
        public String getType() {
            return "indicator";
        }

        @Override
        public String describe() {
            return getName() + " [" + unit + "], " + observations.size() + " observations";
        }
    }

    // =====================================================================
    // Investment
    // =====================================================================

    public static class Investment extends EconomicModel {

        private final String countryCode;
        private final String industryId;
        private final int year;
        private final double amountUsd;
        private final double publicSharePct;
        private final double foreignSharePct;

        public Investment(String countryCode, String industryId, int year,
                double amountUsd, double publicSharePct, double foreignSharePct) {
            super(buildId(countryCode, industryId, year), requireText(industryId, "industryId") + " investment " + year);
            this.countryCode = countryCode.trim().toUpperCase();
            this.industryId = industryId.trim();
            this.year = year;
            this.amountUsd = requireNonNegative(amountUsd, "amountUsd");
            this.publicSharePct = requirePercentage(publicSharePct, "publicSharePct");
            this.foreignSharePct = requirePercentage(foreignSharePct, "foreignSharePct");
        }

        private static String buildId(String countryCode, String industryId, int year) {
            return requireText(countryCode, "countryCode").toUpperCase() + "-"
                    + requireText(industryId, "industryId") + "-" + year;
        }

        public String getCountryCode() {
            return countryCode;
        }

        public String getIndustryId() {
            return industryId;
        }

        public int getYear() {
            return year;
        }

        public double getAmountUsd() {
            return amountUsd;
        }

        public double getPublicSharePct() {
            return publicSharePct;
        }

        public double getForeignSharePct() {
            return foreignSharePct;
        }

        public double getPublicAmountUsd() {
            return amountUsd * publicSharePct / 100.0;
        }

        public double getPrivateAmountUsd() {
            return amountUsd - getPublicAmountUsd();
        }

        public double getForeignAmountUsd() {
            return amountUsd * foreignSharePct / 100.0;
        }

        public double getDomesticAmountUsd() {
            return amountUsd - getForeignAmountUsd();
        }

        @Override
        public String getType() {
            return "investment";
        }

        @Override
        public String describe() {
            return String.format("%s: %.2f USD (public %.1f%%, foreign %.1f%%)",
                    getName(), amountUsd, publicSharePct, foreignSharePct);
        }
    }
}