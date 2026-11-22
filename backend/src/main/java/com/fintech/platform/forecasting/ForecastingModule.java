package com.fintech.platform.forecasting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.springframework.stereotype.Component;

/**
 * Forecasting module (statistical models only).
 *
 * Main class: ForecastingModule (facade used by the controller).
 * Nested subclasses: GDPForecast, IndustryForecast, InvestmentForecast.
 *
 * Every forecast type extends the abstract class Forecast, which runs the common
 * workflow: clean the history -> fit a statistical Model -> build the output series.
 * Subclasses differ in their default model and in the rules applied to the output
 * (polymorphism). The models themselves implement the Model interface:
 *   - linear          : ordinary least squares trend line
 *   - holt            : damped-trend exponential smoothing (parameters tuned automatically)
 *   - moving-average  : flat forecast from the mean of the last 3 years
 *
 * Output points are labelled: historical (input data), estimated (gaps filled by
 * interpolation) and forecasted (model output).
 */
@Component
public class ForecastingModule {

    public static final int MAX_HORIZON = 20;

    // =====================================================================
    // Output records
    // =====================================================================

    /** Fitted values (NaN where not available) and future values of a model. */
    public record Projection(double[] fitted, double[] forecast) {
    }

    public record ForecastPoint(int year, double value, String dataType, Double lower, Double upper) {
    }

    public record ForecastResult(
            String subject,
            String forecastType,
            String model,
            int horizon,
            int observations,
            int lastObservedYear,
            double rmse,
            List<ForecastPoint> series,
            String note) {
    }

    // =====================================================================
    // Statistical models (abstraction)
    // =====================================================================

    public interface Model {

        String key();

        int minObservations();

        /** values are assumed to be evenly spaced (one per year). */
        Projection project(double[] values, int horizon);
    }

    public static class LinearTrendModel implements Model {

        @Override
        public String key() {
            return "linear";
        }

        @Override
        public int minObservations() {
            return 3;
        }

        @Override
        public Projection project(double[] y, int horizon) {
            int n = y.length;
            SimpleRegression regression = new SimpleRegression();
            for (int i = 0; i < n; i++) {
                regression.addData(i, y[i]);
            }
            double slope = regression.getSlope();
            double intercept = regression.getIntercept();

            double[] fitted = new double[n];
            for (int i = 0; i < n; i++) {
                fitted[i] = intercept + slope * i;
            }
            double[] forecast = new double[horizon];
            for (int h = 0; h < horizon; h++) {
                forecast[h] = intercept + slope * (n + h);
            }
            return new Projection(fitted, forecast);
        }
    }

    public static class HoltModel implements Model {

        private static final double PHI = 0.9; // trend damping factor

        private record Run(double[] fitted, double level, double trend, double sse) {
        }

        @Override
        public String key() {
            return "holt";
        }

        @Override
        public int minObservations() {
            return 4;
        }

        @Override
        public Projection project(double[] y, int horizon) {
            // Grid search for the smoothing parameters with the lowest squared error.
            Run best = null;
            for (int a = 1; a <= 9; a++) {
                for (int b = 1; b <= 9; b++) {
                    Run run = run(y, a / 10.0, b / 10.0);
                    if (best == null || run.sse() < best.sse()) {
                        best = run;
                    }
                }
            }

            double[] forecast = new double[horizon];
            double power = 1.0;
            double dampedSum = 0.0;
            for (int h = 0; h < horizon; h++) {
                power *= PHI;
                dampedSum += power;
                forecast[h] = best.level() + dampedSum * best.trend();
            }
            return new Projection(best.fitted(), forecast);
        }

        private static Run run(double[] y, double alpha, double beta) {
            int n = y.length;
            double level = y[0];
            double trend = y[1] - y[0];
            double[] fitted = new double[n];
            fitted[0] = Double.NaN;
            double sse = 0.0;

            for (int t = 1; t < n; t++) {
                double prediction = level + PHI * trend;
                fitted[t] = prediction;
                double error = y[t] - prediction;
                sse += error * error;

                double previousLevel = level;
                level = alpha * y[t] + (1 - alpha) * prediction;
                trend = beta * (level - previousLevel) + (1 - beta) * PHI * trend;
            }
            return new Run(fitted, level, trend, sse);
        }
    }

    public static class MovingAverageModel implements Model {

        private final int window;

        public MovingAverageModel(int window) {
            this.window = window;
        }

        @Override
        public String key() {
            return "moving-average";
        }

        @Override
        public int minObservations() {
            return window;
        }

        @Override
        public Projection project(double[] y, int horizon) {
            int n = y.length;
            double[] fitted = new double[n];
            Arrays.fill(fitted, Double.NaN);
            for (int t = window; t < n; t++) {
                fitted[t] = mean(y, t - window, t);
            }

            double[] forecast = new double[horizon];
            Arrays.fill(forecast, mean(y, n - window, n));
            return new Projection(fitted, forecast);
        }

        /** Mean of y[from .. to-1]. */
        private static double mean(double[] y, int from, int to) {
            double sum = 0.0;
            for (int i = from; i < to; i++) {
                sum += y[i];
            }
            return sum / (to - from);
        }
    }

    private static final Map<String, Model> MODELS = new LinkedHashMap<>();

    static {
        for (Model model : List.of(new LinearTrendModel(), new HoltModel(), new MovingAverageModel(3))) {
            MODELS.put(model.key(), model);
        }
    }

    // =====================================================================
    // Forecast types (inheritance + polymorphism)
    // =====================================================================

    public abstract static class Forecast {

        private final String forecastType;
        private final String defaultModelKey;

        protected Forecast(String forecastType, String defaultModelKey) {
            this.forecastType = forecastType;
            this.defaultModelKey = defaultModelKey;
        }

        /** Rule applied to every forecasted value (e.g. no negative values). */
        protected double constrain(double value) {
            return value;
        }

        /**
         * @param subject  label of the series, e.g. "VNM GDP"
         * @param history  year -> value (historical data only)
         * @param horizon  number of years to forecast after the last observed year
         * @param modelKey linear | holt | moving-average, or null for the default model
         */
        public final ForecastResult run(String subject, Map<Integer, Double> history, int horizon, String modelKey) {
            if (horizon < 1 || horizon > MAX_HORIZON) {
                throw new IllegalArgumentException("Horizon must be between 1 and " + MAX_HORIZON + " years");
            }
            if (history == null || history.isEmpty()) {
                throw new IllegalArgumentException(forecastType + ": history must not be empty");
            }

            Model model = resolveModel(modelKey);
            TreeMap<Integer, Double> original = validate(history);
            TreeMap<Integer, Double> filled = fillGaps(original);

            if (filled.size() < model.minObservations()) {
                throw new IllegalArgumentException(forecastType + ": model '" + model.key() + "' needs at least "
                        + model.minObservations() + " yearly observations, got " + filled.size());
            }

            int firstYear = filled.firstKey();
            int lastYear = filled.lastKey();
            double[] y = filled.values().stream().mapToDouble(Double::doubleValue).toArray();

            Projection projection = model.project(y, horizon);
            double rmse = rmse(y, projection.fitted());

            List<ForecastPoint> series = new ArrayList<>();
            for (int i = 0; i < y.length; i++) {
                int year = firstYear + i;
                String type = original.containsKey(year) ? "historical" : "estimated";
                series.add(new ForecastPoint(year, round(y[i]), type, null, null));
            }
            for (int h = 0; h < horizon; h++) {
                double value = constrain(projection.forecast()[h]);
                double margin = 1.96 * rmse * Math.sqrt(h + 1);
                series.add(new ForecastPoint(
                        lastYear + h + 1,
                        round(value),
                        "forecasted",
                        round(constrain(value - margin)),
                        round(value + margin)));
            }

            return new ForecastResult(subject, forecastType, model.key(), horizon, filled.size(), lastYear,
                    round(rmse), series,
                    "Statistical extrapolation of past data, not a guaranteed prediction. "
                            + "Lower/upper is an approximate 95% band (1.96 x RMSE x sqrt(years ahead)).");
        }

        private Model resolveModel(String modelKey) {
            String key = (modelKey == null || modelKey.isBlank()) ? defaultModelKey : modelKey.trim().toLowerCase();
            Model model = MODELS.get(key);
            if (model == null) {
                throw new IllegalArgumentException("Unknown model '" + key + "'. Available: " + MODELS.keySet());
            }
            return model;
        }

        private static TreeMap<Integer, Double> validate(Map<Integer, Double> history) {
            TreeMap<Integer, Double> sorted = new TreeMap<>();
            for (Map.Entry<Integer, Double> entry : history.entrySet()) {
                Double value = entry.getValue();
                if (entry.getKey() == null || value == null || value.isNaN() || value.isInfinite()) {
                    throw new IllegalArgumentException("History contains an invalid year or value");
                }
                sorted.put(entry.getKey(), value);
            }
            return sorted;
        }

        /** Fills missing years by linear interpolation so the series is evenly spaced. */
        private static TreeMap<Integer, Double> fillGaps(TreeMap<Integer, Double> sorted) {
            TreeMap<Integer, Double> filled = new TreeMap<>(sorted);
            Integer previousYear = null;
            for (Map.Entry<Integer, Double> entry : sorted.entrySet()) {
                int year = entry.getKey();
                if (previousYear != null && year - previousYear > 1) {
                    double previousValue = sorted.get(previousYear);
                    int gap = year - previousYear;
                    for (int k = 1; k < gap; k++) {
                        filled.put(previousYear + k, previousValue + (entry.getValue() - previousValue) * k / gap);
                    }
                }
                previousYear = year;
            }
            return filled;
        }

        private static double rmse(double[] actual, double[] fitted) {
            double sum = 0.0;
            int count = 0;
            for (int i = 0; i < actual.length; i++) {
                if (!Double.isNaN(fitted[i])) {
                    double error = actual[i] - fitted[i];
                    sum += error * error;
                    count++;
                }
            }
            return count == 0 ? 0.0 : Math.sqrt(sum / count);
        }
    }

    /** GDP level or GDP growth. Trend-based by default; values may be negative (recession). */
    public static class GDPForecast extends Forecast {

        public GDPForecast() {
            super("GDP Forecast", "holt");
        }
    }

    /** Industry revenue, employment or growth. Never negative. */
    public static class IndustryForecast extends Forecast {

        public IndustryForecast() {
            super("Industry Forecast", "linear");
        }

        @Override
        protected double constrain(double value) {
            return Math.max(0.0, value);
        }
    }

    /** Investment demand or FDI (volatile series, so averaging is the default). Never negative. */
    public static class InvestmentForecast extends Forecast {

        public InvestmentForecast() {
            super("Investment Forecast", "moving-average");
        }

        @Override
        protected double constrain(double value) {
            return Math.max(0.0, value);
        }
    }

    // =====================================================================
    // Facade used by the controller
    // =====================================================================

    private final GDPForecast gdpForecast = new GDPForecast();
    private final IndustryForecast industryForecast = new IndustryForecast();
    private final InvestmentForecast investmentForecast = new InvestmentForecast();

    public ForecastResult forecastGdp(String subject, Map<Integer, Double> history, int horizon, String model) {
        return gdpForecast.run(subject, history, horizon, model);
    }

    public ForecastResult forecastIndustry(String subject, Map<Integer, Double> history, int horizon, String model) {
        return industryForecast.run(subject, history, horizon, model);
    }

    public ForecastResult forecastInvestment(String subject, Map<Integer, Double> history, int horizon, String model) {
        return investmentForecast.run(subject, history, horizon, model);
    }

    public List<String> availableModels() {
        return new ArrayList<>(MODELS.keySet());
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}