package script.winnerCustomization;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.boot.SpringApplication;

class WinnerCustomizationApplicationTest {
    @Test
    void mainDelegatesToSpringApplicationRun() {
        try (MockedStatic<SpringApplication> springApplication = Mockito.mockStatic(SpringApplication.class)) {
            WinnerCustomizationApplication.main(new String[]{"--test"});
            springApplication.verify(() -> SpringApplication.run(WinnerCustomizationApplication.class, new String[]{"--test"}));
        }
    }
}
