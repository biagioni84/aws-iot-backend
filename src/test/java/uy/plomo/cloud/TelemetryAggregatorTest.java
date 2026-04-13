package uy.plomo.cloud;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uy.plomo.cloud.utils.TelemetryAggregator;
import uy.plomo.cloud.utils.TelemetryAggregator.AggregatedPoint;
import uy.plomo.cloud.utils.TelemetryAggregator.Fn;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("TelemetryAggregator")
class TelemetryAggregatorTest {

    private static Map<String, Object> point(String timestamp, double temp, double humidity) {
        return Map.of(
                "timestamp", timestamp,
                "payload", Map.of("temperature", temp, "humidity", humidity)
        );
    }

    @Test
    @DisplayName("returns empty list for empty input")
    void aggregate_empty() {
        assertThat(TelemetryAggregator.aggregate(List.of(), Duration.ofHours(1), Fn.AVG)).isEmpty();
    }

    @Test
    @DisplayName("groups points into correct buckets")
    void aggregate_bucketing() {
        List<Map<String, Object>> points = List.of(
                point("2024-01-01T00:10:00Z", 10.0, 50.0),
                point("2024-01-01T00:20:00Z", 20.0, 60.0),
                point("2024-01-01T01:10:00Z", 30.0, 70.0)
        );

        List<AggregatedPoint> result = TelemetryAggregator.aggregate(points, Duration.ofHours(1), Fn.AVG);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).count()).isEqualTo(2);
        assertThat(result.get(1).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("AVG computes correct average per field")
    void aggregate_avg() {
        List<Map<String, Object>> points = List.of(
                point("2024-01-01T00:10:00Z", 10.0, 50.0),
                point("2024-01-01T00:20:00Z", 20.0, 60.0)
        );

        List<AggregatedPoint> result = TelemetryAggregator.aggregate(points, Duration.ofHours(1), Fn.AVG);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).values().get("temperature")).isEqualTo(15.0);
        assertThat(result.get(0).values().get("humidity")).isEqualTo(55.0);
    }

    @Test
    @DisplayName("MIN returns minimum per field")
    void aggregate_min() {
        List<Map<String, Object>> points = List.of(
                point("2024-01-01T00:10:00Z", 10.0, 50.0),
                point("2024-01-01T00:20:00Z", 20.0, 60.0)
        );

        List<AggregatedPoint> result = TelemetryAggregator.aggregate(points, Duration.ofHours(1), Fn.MIN);

        assertThat(result.get(0).values().get("temperature")).isEqualTo(10.0);
        assertThat(result.get(0).values().get("humidity")).isEqualTo(50.0);
    }

    @Test
    @DisplayName("MAX returns maximum per field")
    void aggregate_max() {
        List<Map<String, Object>> points = List.of(
                point("2024-01-01T00:10:00Z", 10.0, 50.0),
                point("2024-01-01T00:20:00Z", 20.0, 60.0)
        );

        List<AggregatedPoint> result = TelemetryAggregator.aggregate(points, Duration.ofHours(1), Fn.MAX);

        assertThat(result.get(0).values().get("temperature")).isEqualTo(20.0);
        assertThat(result.get(0).values().get("humidity")).isEqualTo(60.0);
    }

    @Test
    @DisplayName("SUM returns sum per field")
    void aggregate_sum() {
        List<Map<String, Object>> points = List.of(
                point("2024-01-01T00:10:00Z", 10.0, 50.0),
                point("2024-01-01T00:20:00Z", 20.0, 60.0)
        );

        List<AggregatedPoint> result = TelemetryAggregator.aggregate(points, Duration.ofHours(1), Fn.SUM);

        assertThat(result.get(0).values().get("temperature")).isEqualTo(30.0);
        assertThat(result.get(0).values().get("humidity")).isEqualTo(110.0);
    }

    @Test
    @DisplayName("COUNT returns empty values map")
    void aggregate_count() {
        List<Map<String, Object>> points = List.of(
                point("2024-01-01T00:10:00Z", 10.0, 50.0),
                point("2024-01-01T00:20:00Z", 20.0, 60.0)
        );

        List<AggregatedPoint> result = TelemetryAggregator.aggregate(points, Duration.ofHours(1), Fn.COUNT);

        assertThat(result.get(0).count()).isEqualTo(2);
        assertThat(result.get(0).values()).isEmpty();
    }

    @Test
    @DisplayName("non-numeric fields are ignored")
    void aggregate_ignoresNonNumeric() {
        List<Map<String, Object>> points = List.of(
                Map.of("timestamp", "2024-01-01T00:10:00Z",
                       "payload", Map.of("temperature", 10.0, "status", "ok"))
        );

        List<AggregatedPoint> result = TelemetryAggregator.aggregate(points, Duration.ofHours(1), Fn.AVG);

        assertThat(result.get(0).values()).containsKey("temperature");
        assertThat(result.get(0).values()).doesNotContainKey("status");
    }

    @Test
    @DisplayName("parseWindow parses all supported sizes")
    void parseWindow_valid() {
        assertThat(TelemetryAggregator.parseWindow("1m")).isEqualTo(Duration.ofMinutes(1));
        assertThat(TelemetryAggregator.parseWindow("5m")).isEqualTo(Duration.ofMinutes(5));
        assertThat(TelemetryAggregator.parseWindow("15m")).isEqualTo(Duration.ofMinutes(15));
        assertThat(TelemetryAggregator.parseWindow("30m")).isEqualTo(Duration.ofMinutes(30));
        assertThat(TelemetryAggregator.parseWindow("1h")).isEqualTo(Duration.ofHours(1));
        assertThat(TelemetryAggregator.parseWindow("6h")).isEqualTo(Duration.ofHours(6));
        assertThat(TelemetryAggregator.parseWindow("12h")).isEqualTo(Duration.ofHours(12));
        assertThat(TelemetryAggregator.parseWindow("1d")).isEqualTo(Duration.ofDays(1));
    }

    @Test
    @DisplayName("parseWindow throws on unsupported value")
    void parseWindow_invalid() {
        assertThatThrownBy(() -> TelemetryAggregator.parseWindow("2h"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("parseFn defaults to AVG when null")
    void parseFn_defaults() {
        assertThat(TelemetryAggregator.parseFn(null)).isEqualTo(Fn.AVG);
        assertThat(TelemetryAggregator.parseFn("")).isEqualTo(Fn.AVG);
    }

    @Test
    @DisplayName("parseFn throws on unsupported value")
    void parseFn_invalid() {
        assertThatThrownBy(() -> TelemetryAggregator.parseFn("median"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
