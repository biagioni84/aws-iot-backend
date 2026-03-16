package uy.plomo.cloud.services;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import uy.plomo.cloud.entity.Gateway;
import uy.plomo.cloud.repository.GatewayRepository;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ArchiveService")
class ArchiveServiceTest {

    @Mock TelemetryService telemetryService;
    @Mock GatewayRepository gatewayRepository;
    @Mock S3Client s3;

    ArchiveService service;

    @BeforeEach
    void setUp() {
        service = new ArchiveService(telemetryService, gatewayRepository,
                s3, "my-bucket", "telemetry/");
    }

    // -------------------------------------------------------------------------
    // archiveGatewayDay
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("archiveGatewayDay")
    class ArchiveGatewayDay {

        @Test
        @DisplayName("returns 0 and skips S3 upload when DynamoDB has no records")
        void skipsWhenNoRecords() throws IOException {
            when(telemetryService.queryRawPaginated(any(), any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(List.of()));

            int count = service.archiveGatewayDay("gw-001", LocalDate.of(2025, 6, 1));

            assertThat(count).isZero();
            verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        }

        @Test
        @DisplayName("writes Parquet to S3 with Hive-partition key and returns record count")
        void uploadsParquetWithCorrectKey() throws IOException {
            List<Map<String, AttributeValue>> items = List.of(
                    Map.of(
                            "timestamp", AttributeValue.builder().s("2025-06-01T10:00:00Z").build(),
                            "payload",   AttributeValue.builder().m(Map.of(
                                    "temp", AttributeValue.builder().n("22.5").build()
                            )).build()
                    )
            );
            when(telemetryService.queryRawPaginated(any(), any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(items));
            when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                    .thenReturn(PutObjectResponse.builder().build());

            int count = service.archiveGatewayDay("gw-001", LocalDate.of(2025, 6, 1));

            assertThat(count).isEqualTo(1);

            var captor = org.mockito.ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3).putObject(captor.capture(), any(RequestBody.class));
            assertThat(captor.getValue().bucket()).isEqualTo("my-bucket");
            assertThat(captor.getValue().key())
                    .isEqualTo("telemetry/dt=2025-06-01/gateway_id=gw-001/data.parquet");
        }

        @Test
        @DisplayName("queries DynamoDB with full-day UTC range for the given date")
        void queriesCorrectTimeRange() throws IOException {
            when(telemetryService.queryRawPaginated(any(), any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(List.of()));

            service.archiveGatewayDay("gw-001", LocalDate.of(2025, 6, 1));

            verify(telemetryService).queryRawPaginated(
                    "gw-001",
                    "2025-06-01T00:00:00Z",
                    "2025-06-02T00:00:00Z");
        }
    }

    // -------------------------------------------------------------------------
    // archiveYesterday
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("archiveYesterday")
    class ArchiveYesterday {

        @Test
        @DisplayName("iterates all gateways; a failure in one does not stop the others")
        void continuesAfterSingleFailure() {
            Gateway gw1 = mock(Gateway.class);
            Gateway gw2 = mock(Gateway.class);
            when(gw1.getId()).thenReturn("gw-001");
            when(gw2.getId()).thenReturn("gw-002");
            when(gatewayRepository.findAll()).thenReturn(List.of(gw1, gw2));

            // gw-001 fails, gw-002 returns no records
            when(telemetryService.queryRawPaginated(eq("gw-001"), any(), any()))
                    .thenReturn(CompletableFuture.failedFuture(new RuntimeException("DynamoDB down")));
            when(telemetryService.queryRawPaginated(eq("gw-002"), any(), any()))
                    .thenReturn(CompletableFuture.completedFuture(List.of()));

            assertThatNoException().isThrownBy(() -> service.archiveYesterday());
            verify(telemetryService, times(2)).queryRawPaginated(any(), any(), any());
        }
    }
}
