package script.winnerCustomization.service.reports;
 
import java.time.LocalDate;
 
/**
 * Service for generating XLSX reports.
 */
public interface ReportService {
 
    /**
     * Generate a full report for all loaded detections.
     * Returns the path to the generated file.
     */
    String generateFullReport();
 
    /**
     * Generate a report filtered to the specified day.
     * Returns the path to the generated file.
     */
    String generateDayReport(LocalDate date);
}