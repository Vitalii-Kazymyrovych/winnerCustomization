package script.winnerCustomization.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import script.winnerCustomization.model.Detection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Repository
public class SqlFileSourceDetectionRepository implements SourceDetectionRepository {
    private static final Logger log = LoggerFactory.getLogger(SqlFileSourceDetectionRepository.class);
    private static final DateTimeFormatter FORMATTER = new DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd HH:mm:ss")
        .optionalStart()
        .appendFraction(ChronoField.MILLI_OF_SECOND, 1, 3, true)
        .optionalEnd()
        .toFormatter();

    @Override
    public List<Detection> findAll() {
        Path path = Path.of("alpr_detections.sql");
        if (!Files.exists(path)) {
            log.warn("alpr_detections.sql not found, no detections loaded");
            return List.of();
        }
        try {
            List<Detection> result = new ArrayList<>();
            boolean copy = false;
            for (String line : Files.readAllLines(path)) {
                if (line.startsWith("COPY videoanalytics.alpr_detections")) {
                    copy = true;
                    continue;
                }
                if (copy && line.equals("\\.")) {
                    break;
                }
                if (!copy || line.isBlank()) {
                    continue;
                }
                String[] p = line.split("\\t");
                if (p.length < 13) {
                    continue;
                }
                long id = Long.parseLong(p[0]);
                String plate = p[1];
                int analyticsId = Integer.parseInt(p[7]);
                Integer direction = parseIntNullable(p[12]);
                LocalDateTime ts = LocalDateTime.parse(p[10], FORMATTER);
                result.add(new Detection(id, plate, analyticsId, direction, ts));
            }
            result.sort(Comparator.comparing(Detection::createdAtUtc).thenComparing(Detection::id));
            return result;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to read alpr_detections.sql", ex);
        }
    }

    @Override
    public List<Detection> findNewerThan(LocalDateTime timestampUtc) {
        return findAll().stream().filter(d -> d.createdAtUtc().isAfter(timestampUtc)).toList();
    }

    private Integer parseIntNullable(String value) {
        if (value == null || value.isBlank() || "\\N".equals(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
