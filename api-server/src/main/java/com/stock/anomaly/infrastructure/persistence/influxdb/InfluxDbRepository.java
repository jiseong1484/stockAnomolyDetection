package com.stock.anomaly.infrastructure.persistence.influxdb;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.WriteApiBlocking;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.influxdb.query.FluxRecord;
import com.influxdb.query.FluxTable;
import com.stock.anomaly.domain.stock.StockTick;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Repository
@RequiredArgsConstructor
public class InfluxDbRepository {

    private final InfluxDBClient influxDBClient;

    @Value("${spring.influxdb.bucket}")
    private String bucket;

    @Value("${spring.influxdb.org}")
    private String org;

    public void save(StockTick tick) {
        try {
            WriteApiBlocking writeApi = influxDBClient.getWriteApiBlocking();
            
            // InfluxDB Point 생성
            Point point = Point.measurement("stock_prices")
                    .addTag("ticker", tick.getTicker())
                    .addField("price", Double.parseDouble(tick.getPrice()))
                    .addField("volume", Long.parseLong(tick.getVolume()))
                    .time(Instant.ofEpochMilli(tick.getTimestamp()), WritePrecision.MS);

            writeApi.writePoint(bucket, org, point);
            log.debug("Saved stock tick to InfluxDB: {}", tick.getTicker());
        } catch (Exception e) {
            log.error("Failed to save stock tick to InfluxDB: {}", e.getMessage());
        }
    }

    public List<StockTick> findRecentTicks(String ticker, int minutes) {
        String query = String.format(
                "from(bucket: \"%s\") " +
                "|> range(start: -%dm) " +
                "|> filter(fn: (r) r[\"_measurement\"] == \"stock_prices\") " +
                "|> filter(fn: (r) r[\"ticker\"] == \"%s\") " +
                "|> pivot(rowKey:[\"_time\"], columnKey: [\"_field\"], valueColumn: \"_value\")",
                bucket, minutes, ticker
        );

        List<StockTick> ticks = new ArrayList<>();
        try {
            List<FluxTable> tables = influxDBClient.getQueryApi().query(query, org);
            for (FluxTable table : tables) {
                for (FluxRecord record : table.getRecords()) {
                    ticks.add(StockTick.builder()
                            .ticker(ticker)
                            .price(String.valueOf(record.getValueByKey("price")))
                            .volume(String.valueOf(record.getValueByKey("volume")))
                            .timestamp(record.getTime().toEpochMilli())
                            .build());
                }
            }
        } catch (Exception e) {
            log.error("Failed to query InfluxDB for ticker {}: {}", ticker, e.getMessage());
        }
        return ticks;
    }
}
