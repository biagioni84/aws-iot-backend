package uy.plomo.cloud.utils;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregates raw telemetry points into fixed-size time buckets.
 *
 * Input:  List of { "timestamp": ISO-8601, "payload": { field: value, ... } }
 * Output: List of { "window_start", "window_end", "count", "values": { field: aggregated } }
 *
 * Only numeric payload fields are aggregated. Non-numeric fields are ignored.
 */
public class TelemetryAggregator {

    public enum Fn { AVG, MIN, MAX, SUM, COUNT }

    public record Window(Instant start, Instant end) {}

    public record AggregatedPoint(
            String windowStart,
            String windowEnd,
            int count,
            Map<String, Double> values
    ) {}

    /**
     * @param points  raw telemetry list from TelemetryService / AthenaService
     * @param window  bucket size (e.g. Duration.ofHours(1))
     * @param fn      aggregation function
     */
    public static List<AggregatedPoint> aggregate(
            List<Map<String, Object>> points,
            Duration window,
            Fn fn) {

        if (points.isEmpty()) return List.of();

        // Group points by bucket index: floor(timestamp - epoch) / windowSeconds
        long windowSeconds = window.toSeconds();
        Map<Long, List<Map<String, Object>>> buckets = new TreeMap<>();
        for (Map<String, Object> point : points) {
            Instant ts = Instant.parse((String) point.get("timestamp"));
            long bucket = ts.getEpochSecond() / windowSeconds;
            buckets.computeIfAbsent(bucket, k -> new ArrayList<>()).add(point);
        }

        List<AggregatedPoint> result = new ArrayList<>();
        for (Map.Entry<Long, List<Map<String, Object>>> entry : buckets.entrySet()) {
            long bucketIdx = entry.getKey();
            List<Map<String, Object>> bucketPoints = entry.getValue();

            Instant start = Instant.ofEpochSecond(bucketIdx * windowSeconds);
            Instant end   = start.plus(window);

            Map<String, Double> values = fn == Fn.COUNT
                    ? Map.of()
                    : aggregateFields(bucketPoints, fn);

            result.add(new AggregatedPoint(
                    start.toString(),
                    end.toString(),
                    bucketPoints.size(),
                    values
            ));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Double> aggregateFields(
            List<Map<String, Object>> points, Fn fn) {

        // Collect all numeric values per field across all points in the bucket
        Map<String, List<Double>> fieldValues = new LinkedHashMap<>();
        for (Map<String, Object> point : points) {
            Object payloadObj = point.get("payload");
            if (!(payloadObj instanceof Map)) continue;
            Map<String, Object> payload = (Map<String, Object>) payloadObj;
            for (Map.Entry<String, Object> e : payload.entrySet()) {
                if (e.getValue() instanceof Number n) {
                    fieldValues.computeIfAbsent(e.getKey(), k -> new ArrayList<>())
                               .add(n.doubleValue());
                }
            }
        }

        return fieldValues.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> apply(fn, e.getValue()),
                (a, b) -> a,
                LinkedHashMap::new
        ));
    }

    private static double apply(Fn fn, List<Double> values) {
        return switch (fn) {
            case AVG   -> values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            case MIN   -> values.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            case MAX   -> values.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            case SUM   -> values.stream().mapToDouble(Double::doubleValue).sum();
            case COUNT -> values.size();
        };
    }

    /** Parses window strings like "1m", "5m", "15m", "1h", "6h", "1d". */
    public static Duration parseWindow(String window) {
        if (window == null || window.isBlank()) return Duration.ofHours(1);
        return switch (window.toLowerCase()) {
            case "1m"  -> Duration.ofMinutes(1);
            case "5m"  -> Duration.ofMinutes(5);
            case "15m" -> Duration.ofMinutes(15);
            case "30m" -> Duration.ofMinutes(30);
            case "1h"  -> Duration.ofHours(1);
            case "6h"  -> Duration.ofHours(6);
            case "12h" -> Duration.ofHours(12);
            case "1d"  -> Duration.ofDays(1);
            default    -> throw new IllegalArgumentException("Unsupported window: " + window);
        };
    }

    /** Parses fn strings like "avg", "min", "max", "sum", "count". */
    public static Fn parseFn(String fn) {
        if (fn == null || fn.isBlank()) return Fn.AVG;
        try {
            return Fn.valueOf(fn.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported function: " + fn);
        }
    }
}
