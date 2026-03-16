package uy.plomo.cloud.services;

import lombok.extern.slf4j.Slf4j;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.enhanced.dynamodb.document.EnhancedDocument;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import uy.plomo.cloud.repository.GatewayRepository;
import uy.plomo.cloud.utils.ByteArrayOutputFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * Daily job: reads yesterday's telemetry from DynamoDB and writes one Parquet file
 * per gateway to S3, partitioned for Athena:
 *   s3://{bucket}/{prefix}dt=yyyy-MM-dd/gateway_id={gwId}/data.parquet
 *
 * Activated only when archive.s3.bucket is configured.
 */
@Service
@ConditionalOnProperty(name = "archive.s3.bucket")
@Slf4j
public class ArchiveService {

    static final Schema SCHEMA = SchemaBuilder.record("TelemetryRecord")
            .namespace("uy.plomo.cloud")
            .fields()
            .requiredString("timestamp")
            .requiredString("payload_json")
            .endRecord();

    private final TelemetryService telemetryService;
    private final GatewayRepository gatewayRepository;
    private final S3Client s3;
    private final String bucket;
    private final String prefix;

    @Autowired
    public ArchiveService(
            TelemetryService telemetryService,
            GatewayRepository gatewayRepository,
            @Value("${aws.region}") String region,
            @Value("${archive.s3.bucket}") String bucket,
            @Value("${archive.s3.prefix:telemetry/}") String prefix) {
        this.telemetryService = telemetryService;
        this.gatewayRepository = gatewayRepository;
        this.s3 = S3Client.builder().region(Region.of(region)).build();
        this.bucket = bucket;
        this.prefix = prefix;
    }

    /** Package-private constructor for unit tests — inject dependencies directly. */
    ArchiveService(TelemetryService telemetryService, GatewayRepository gatewayRepository,
                   S3Client s3, String bucket, String prefix) {
        this.telemetryService = telemetryService;
        this.gatewayRepository = gatewayRepository;
        this.s3 = s3;
        this.bucket = bucket;
        this.prefix = prefix;
    }

    @Scheduled(cron = "${archive.cron:0 0 2 * * *}")
    public void archiveYesterday() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        log.info("Archive job starting for {}", yesterday);
        int archived = 0, skipped = 0, failed = 0;

        for (var gateway : gatewayRepository.findAll()) {
            try {
                int count = archiveGatewayDay(gateway.getId(), yesterday);
                if (count > 0) {
                    log.info("Archived {} records for gateway {} on {}", count, gateway.getId(), yesterday);
                    archived++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                log.error("Archive failed for gateway {} on {}: {}", gateway.getId(), yesterday, e.getMessage(), e);
                failed++;
            }
        }
        log.info("Archive job done for {}: {} gateways archived, {} skipped, {} failed",
                yesterday, archived, skipped, failed);
    }

    /**
     * Archives one gateway's telemetry for the given day.
     * Returns the number of records written (0 if none found).
     */
    int archiveGatewayDay(String gwId, LocalDate date) throws IOException {
        String from = date.atStartOfDay(ZoneOffset.UTC).toInstant().toString();
        String to   = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString();

        List<Map<String, AttributeValue>> items =
                telemetryService.queryRawPaginated(gwId, from, to).join();

        if (items.isEmpty()) return 0;

        byte[] parquetBytes = writeParquet(items);

        String key = String.format("%sdt=%s/gateway_id=%s/data.parquet", prefix, date, gwId);
        s3.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes(parquetBytes));

        return items.size();
    }

    private byte[] writeParquet(List<Map<String, AttributeValue>> items) throws IOException {
        ByteArrayOutputFile output = new ByteArrayOutputFile();
        try (var writer = AvroParquetWriter.<GenericRecord>builder(output)
                .withSchema(SCHEMA)
                .withCompressionCodec(CompressionCodecName.SNAPPY)
                .build()) {

            for (Map<String, AttributeValue> item : items) {
                GenericRecord record = new GenericData.Record(SCHEMA);
                record.put("timestamp", item.get("timestamp").s());

                AttributeValue payloadAttr = item.get("payload");
                String payloadJson = (payloadAttr != null && payloadAttr.m() != null)
                        ? EnhancedDocument.fromAttributeValueMap(payloadAttr.m()).toJson()
                        : "{}";
                record.put("payload_json", payloadJson);
                writer.write(record);
            }
        }
        return output.toByteArray();
    }
}
