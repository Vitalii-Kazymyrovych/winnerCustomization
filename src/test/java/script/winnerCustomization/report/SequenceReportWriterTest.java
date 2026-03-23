package script.winnerCustomization.report;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import script.winnerCustomization.logic.StageSequenceProcessor;
import script.winnerCustomization.model.SequenceRecord;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SequenceReportWriterTest {
    @Test
    void createsWorkbookWithSequencesAndEventsSheetsMatchingExpectedShape() throws Exception {
        SequenceRecord record = new SequenceRecord("AA1111", LocalDateTime.of(2026, 3, 23, 10, 0));
        record.addStage(new SequenceRecord.StageWindow("drive_in", "Drive In", SequenceRecord.StageType.REAL,
                LocalDateTime.of(2026, 3, 23, 10, 0), LocalDateTime.of(2026, 3, 23, 10, 10), false, false, true));
        var writer = new SequenceReportWriter();

        byte[] bytes = writer.write(new StageSequenceProcessor.ProcessingResult(List.of(record)), LocalDateTime.of(2026, 3, 23, 10, 15));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getSheetName(0)).isEqualTo("Sequences");
            assertThat(workbook.getSheetName(1)).isEqualTo("Events");
            assertThat(workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue()).isEqualTo("Stage");
            assertThat(workbook.getSheetAt(1).getRow(0).getCell(0).getStringCellValue()).isEqualTo("Plate");
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo("AA1111");
        }
    }
}
