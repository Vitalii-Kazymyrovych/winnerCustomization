package script.winnerCustomization;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WinnerCustomizationApplication {

	public static void main(String[] args) {
		SpringApplication.run(WinnerCustomizationApplication.class, args);
	}

}
