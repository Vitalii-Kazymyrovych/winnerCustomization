package script.winnerCustomization.service.reports;

import java.io.IOException;
import java.time.LocalDate;

public interface ReportService {
    byte[] buildReport(LocalDate filterDayUtc) throws IOException;
}
