package script.winnerCustomization.service;

import org.springframework.stereotype.Service;
import script.winnerCustomization.config.RuntimeConfig;
import script.winnerCustomization.logic.StageSequenceProcessor;
import script.winnerCustomization.model.Detection;
import script.winnerCustomization.report.SequenceReportWriter;
import script.winnerCustomization.repository.DetectionRepository;

import java.io.IOException;
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

    public byte[] buildReport() throws IOException {
        LocalDateTime reportAt = LocalDateTime.now(clock);
        return buildReport(reportAt, detectionRepository.findAll());
    }

    public byte[] buildReport(LocalDate reportDate) throws IOException {
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

    public String buildDatedReportFileName(LocalDate reportDate) {
        return "sequences-" + reportDate + ".xlsx";
    }

    private byte[] buildReport(LocalDateTime reportAt, List<Detection> detections) throws IOException {
        return writer.write(processor.process(detections, runtimeConfig.get(), reportAt), reportAt);
    }
}
