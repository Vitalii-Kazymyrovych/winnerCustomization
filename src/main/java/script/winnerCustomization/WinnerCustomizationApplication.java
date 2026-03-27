package script.winnerCustomization;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import script.winnerCustomization.repository.SourceRepository;
import script.winnerCustomization.repository.TargetRepository;
import script.winnerCustomization.service.logic.DatabaseBootstrapService;
import script.winnerCustomization.service.logic.SchedulerServiceImpl;

@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
public class WinnerCustomizationApplication {

	private static final Logger log = LoggerFactory.getLogger(WinnerCustomizationApplication.class);

	public static void main(String[] args) {
		SpringApplication.run(WinnerCustomizationApplication.class, args);
	}

	@Bean
	public CommandLineRunner startup(DatabaseBootstrapService databaseBootstrap,
									 SourceRepository sourceRepository,
									 TargetRepository targetRepository,
									 SchedulerServiceImpl schedulerService) {
		return args -> {
			log.info("========================================");
			log.info("  ALPR Sequence Engine starting up...");
			log.info("========================================");

			// Step 1: Bootstrap target database
			databaseBootstrap.bootstrap();

			// Step 2: Initialize repositories
			sourceRepository.initialize();
			targetRepository.initialize();

			// Step 3: Perform initial full load
			schedulerService.performInitialLoad();

			// Step 4: Start periodic polling
			schedulerService.startPolling();

			log.info("========================================");
			log.info("  ALPR Sequence Engine is running");
			log.info("========================================");
		};
	}
}
