package script.winnerCustomization.service;

import org.springframework.stereotype.Service;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.logic.StageSequenceProcessor;
import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.report.SequenceReportWriter;
import script.winnerCustomization.repository.DetectionRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ReportService {
    private final DetectionRepository detectionRepository;
    private final RuntimeConfig runtimeConfig;
    private final StageSequenceProcessor processor;
    private final SequenceReportWriter writer;
    private final Clock clock;

    public ReportService(DetectionRepository detectionRepository,
                         RuntimeConfig runtimeConfig,
                         StageSequenceProcessor processor,
                         SequenceReportWriter writer,
                         Clock clock) {
        this.detectionRepository = detectionRepository;
        this.runtimeConfig = runtimeConfig;
        this.processor = processor;
        this.writer = writer;
        this.clock = clock;
    }

    public SavedReport saveReport() throws IOException {
        return saveReport(buildCurrentReport(), "sequences.xlsx");
    }

    public SavedReport saveReport(LocalDate reportDate) throws IOException {
        return saveReport(buildDatedReport(reportDate), buildDatedReportFileName(reportDate));
    }

    public String buildDatedReportFileName(LocalDate reportDate) {
        return "sequences-" + reportDate + ".xlsx";
    }

    private byte[] buildCurrentReport() throws IOException {
        LocalDateTime reportAt = LocalDateTime.now(clock);
        return buildReport(reportAt, detectionRepository.findAll());
    }

    private byte[] buildDatedReport(LocalDate reportDate) throws IOException {
        LocalDateTime from = reportDate.atStartOfDay();
        LocalDateTime to = reportDate.plusDays(1).atStartOfDay();
        LocalDateTime reportAt = LocalDateTime.now(clock);
        var fullResult = processor.process(detectionRepository.findAll(), runtimeConfig.get(), reportAt);
        var filtered = fullResult.sequences().stream()
                .map(sequence -> sequence.filtered(from, to, reportAt))
                .filter(sequence -> !sequence.stagesChronologically().isEmpty())
                .toList();
        return writer.write(new StageSequenceProcessor.ProcessingResult(filtered), reportAt);
    }

    private SavedReport saveReport(byte[] body, String fileName) throws IOException {
        Path outputDirectory = resolveOutputDirectory(runtimeConfig.get());
        Files.createDirectories(outputDirectory);
        Path savedPath = outputDirectory.resolve(fileName);
        Files.write(savedPath, body);
        return new SavedReport(savedPath, body.length);
    }

    private Path resolveOutputDirectory(AppConfig config) {
        String configured = config != null && config.getReports() != null ? config.getReports().getOutputDirectory() : null;
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("reports.outputDirectory is required to save reports");
        }
        Path configuredPath = Path.of(configured);
        if (configuredPath.isAbsolute()) {
            return configuredPath;
        }
        Path configDirectory = runtimeConfig.getConfigPath().getParent();
        return configDirectory == null ? configuredPath.normalize() : configDirectory.resolve(configuredPath).normalize();
    }

    private byte[] buildReport(LocalDateTime reportAt, List<Detection> detections) throws IOException {
        return writer.write(processor.process(detections, runtimeConfig.get(), reportAt), reportAt);
    }

    public record SavedReport(Path path, long sizeBytes) {
    }
}
