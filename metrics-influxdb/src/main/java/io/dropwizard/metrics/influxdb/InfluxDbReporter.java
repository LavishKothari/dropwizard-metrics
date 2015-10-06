package io.dropwizard.metrics.influxdb;

import com.codahale.metrics.*;
import io.dropwizard.metrics.influxdb.data.InfluxDbPoint;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class InfluxDbReporter extends ScheduledReporter {
    public static class Builder {
        private final MetricRegistry registry;
        private Map<String, String> tags;
        private TimeUnit rateUnit;
        private TimeUnit durationUnit;
        private MetricFilter filter;
        private boolean skipIdleMetrics;

        private Builder(MetricRegistry registry) {
            this.registry = registry;
            this.tags = null;
            this.rateUnit = TimeUnit.SECONDS;
            this.durationUnit = TimeUnit.MILLISECONDS;
            this.filter = MetricFilter.ALL;
        }

        /**
         * Add these tags to all metrics.
         *
         * @param tags a map containing tags common to all metrics
         * @return {@code this}
         */
        public Builder withTags(Map<String, String> tags) {
            this.tags = Collections.unmodifiableMap(tags);
            return this;
        }

        /**
         * Convert rates to the given time unit.
         *
         * @param rateUnit a unit of time
         * @return {@code this}
         */
        public Builder convertRatesTo(TimeUnit rateUnit) {
            this.rateUnit = rateUnit;
            return this;
        }

        /**
         * Convert durations to the given time unit.
         *
         * @param durationUnit a unit of time
         * @return {@code this}
         */
        public Builder convertDurationsTo(TimeUnit durationUnit) {
            this.durationUnit = durationUnit;
            return this;
        }

        /**
         * Only report metrics which match the given filter.
         *
         * @param filter a {@link MetricFilter}
         * @return {@code this}
         */
        public Builder filter(MetricFilter filter) {
            this.filter = filter;
            return this;
        }

        /**
         * Only report metrics that have changed.
         *
         * @param skipIdleMetrics true/false for skipping metrics not reported
         * @return {@code this}
         */
        public Builder skipIdleMetrics(boolean skipIdleMetrics) {
            this.skipIdleMetrics = skipIdleMetrics;
            return this;
        }

        public InfluxDbReporter build(final InfluxDbSender influxDb) {
            return new InfluxDbReporter(registry, influxDb, tags, rateUnit, durationUnit, filter, skipIdleMetrics);
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(InfluxDbReporter.class);
    private final InfluxDbSender influxDb;
    private final boolean skipIdleMetrics;
    private final Map<String, Long> previousValues;

    private InfluxDbReporter(final MetricRegistry registry, final InfluxDbSender influxDb, final Map<String, String> tags,
                             final TimeUnit rateUnit, final TimeUnit durationUnit, final MetricFilter filter, final boolean skipIdleMetrics) {
        super(registry, "influxDb-reporter", filter, rateUnit, durationUnit);
        this.influxDb = influxDb;
        influxDb.setTags(tags);
        this.skipIdleMetrics = skipIdleMetrics;
        this.previousValues = new TreeMap<String, Long>();
    }

    public static Builder forRegistry(MetricRegistry registry) {
        return new Builder(registry);
    }

    @Override
    public void report(final SortedMap<String, Gauge> gauges, final SortedMap<String, Counter> counters,
                       final SortedMap<String, Histogram> histograms, final SortedMap<String, Meter> meters, final SortedMap<String, Timer> timers) {
        final long now = System.currentTimeMillis();

        try {
            influxDb.flush();

            for (Map.Entry<String, Gauge> entry : gauges.entrySet()) {
                reportGauge(entry.getKey(), entry.getValue(), now);
            }

            for (Map.Entry<String, Counter> entry : counters.entrySet()) {
                reportCounter(entry.getKey(), entry.getValue(), now);
            }

            for (Map.Entry<String, Histogram> entry : histograms.entrySet()) {
                reportHistogram(entry.getKey(), entry.getValue(), now);
            }

            for (Map.Entry<String, Meter> entry : meters.entrySet()) {
                reportMeter(entry.getKey(), entry.getValue(), now);
            }

            for (Map.Entry<String, Timer> entry : timers.entrySet()) {
                reportTimer(entry.getKey(), entry.getValue(), now);
            }

            if (influxDb.hasSeriesData()) {
                influxDb.writeData();
            }
        } catch (Exception e) {
            LOGGER.warn("Unable to report to InfluxDB. Discarding data.", e);
        }
    }

    private void reportTimer(String name, Timer timer, long now) {
        if (canSkipMetric(name, timer)) {
            return;
        }
        final Snapshot snapshot = timer.getSnapshot();
        Map<String, Object> fields = new HashMap<String, Object>();
        fields.put("count", timer.getCount());
        fields.put("min", convertDuration(snapshot.getMin()));
        fields.put("max", convertDuration(snapshot.getMax()));
        fields.put("mean", convertDuration(snapshot.getMean()));
        fields.put("stddev", convertDuration(snapshot.getStdDev()));
        fields.put("p50", convertDuration(snapshot.getMedian()));
        fields.put("p75", convertDuration(snapshot.get75thPercentile()));
        fields.put("p95", convertDuration(snapshot.get95thPercentile()));
        fields.put("p98", convertDuration(snapshot.get98thPercentile()));
        fields.put("p99", convertDuration(snapshot.get99thPercentile()));
        fields.put("p999", convertDuration(snapshot.get999thPercentile()));
        fields.put("m1_rate", convertRate(timer.getOneMinuteRate()));
        fields.put("m5_rate", convertRate(timer.getFiveMinuteRate()));
        fields.put("m15_rate", convertRate(timer.getFifteenMinuteRate()));
        fields.put("mean_rate", convertRate(timer.getMeanRate()));
        influxDb.appendPoints(new InfluxDbPoint(
                name,
                null,
                String.valueOf(now),
                fields));
    }

    private void reportHistogram(String name, Histogram histogram, long now) {
        if (canSkipMetric(name, histogram)) {
            return;
        }
        final Snapshot snapshot = histogram.getSnapshot();
        Map<String, Object> fields = new HashMap<String, Object>();
        fields.put("count", histogram.getCount());
        fields.put("min", snapshot.getMin());
        fields.put("max", snapshot.getMax());
        fields.put("mean", snapshot.getMean());
        fields.put("stddev", snapshot.getStdDev());
        fields.put("p50", snapshot.getMedian());
        fields.put("p75", snapshot.get75thPercentile());
        fields.put("p95", snapshot.get95thPercentile());
        fields.put("p98", snapshot.get98thPercentile());
        fields.put("p99", snapshot.get99thPercentile());
        fields.put("p999", snapshot.get999thPercentile());
        influxDb.appendPoints(new InfluxDbPoint(
                name,
                null,
                String.valueOf(now),
                fields));
    }

    private void reportCounter(String name, Counter counter, long now) {
        Map<String, Object> fields = new HashMap<String, Object>();
        fields.put("count", counter.getCount());
        influxDb.appendPoints(new InfluxDbPoint(
                name,
                null,
                String.valueOf(now),
                fields));
    }


    private void reportGauge(String name, Gauge<?> gauge, long now) {
        Map<String, Object> fields = new HashMap<String, Object>();
        fields.put("value", gauge.getValue());
        influxDb.appendPoints(new InfluxDbPoint(
                name,
                null,
                String.valueOf(now),
                fields));
    }

    private void reportMeter(String name, Metered meter, long now) {
        if (canSkipMetric(name, meter)) {
            return;
        }
        Map<String, Object> fields = new HashMap<String, Object>();
        fields.put("count", meter.getCount());
        fields.put("m1_rate", convertRate(meter.getOneMinuteRate()));
        fields.put("m5_rate", convertRate(meter.getFiveMinuteRate()));
        fields.put("m15_rate", convertRate(meter.getFifteenMinuteRate()));
        fields.put("mean_rate", convertRate(meter.getMeanRate()));
        influxDb.appendPoints(new InfluxDbPoint(
                name,
                null,
                String.valueOf(now),
                fields));
    }

    private boolean canSkipMetric(String name, Counting counting) {
        boolean isIdle = (calculateDelta(name, counting.getCount()) == 0);
        if (skipIdleMetrics && !isIdle) {
            previousValues.put(name, counting.getCount());
        }
        return skipIdleMetrics && isIdle;
    }

    private long calculateDelta(String name, long count) {
        Long previous = previousValues.get(name);
        if (previous == null) {
            return -1;
        }
        if (count < previous) {
            LOGGER.warn("Saw a non-monotonically increasing value for metric '{}'", name);
            return 0;
        }
        return count - previous;
    }

}
