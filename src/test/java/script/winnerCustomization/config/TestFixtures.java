package script.winnerCustomization.config;

import script.winnerCustomization.model.AppConfig;
import script.winnerCustomization.service.TestConfigFactory;

public final class TestFixtures {
    private TestFixtures() {
    }

    public static AppConfig configWithReportDirectory(String outputDirectory) {
        AppConfig config = TestConfigFactory.config();
        AppConfig.DatabaseConfig sourceDatabase = config.getSourceDatabase();
        sourceDatabase.setHost("localhost");
        sourceDatabase.setPort(5432);
        sourceDatabase.setDb("source_db");
        sourceDatabase.setUser("source_user");
        sourceDatabase.setPassword("source_password");
        config.setSourceDatabase(sourceDatabase);

        AppConfig.DatabaseConfig sequenceDatabase = new AppConfig.DatabaseConfig();
        sequenceDatabase.setHost("localhost");
        sequenceDatabase.setPort(5432);
        sequenceDatabase.setDb("sequence_db");
        sequenceDatabase.setSchema("public");
        sequenceDatabase.setUser("sequence_user");
        sequenceDatabase.setPassword("sequence_password");
        config.setSequenceDatabase(sequenceDatabase);

        AppConfig.RootDatabaseConfig rootDatabase = new AppConfig.RootDatabaseConfig();
        rootDatabase.setHost("localhost");
        rootDatabase.setPort(5432);
        rootDatabase.setUser("postgres");
        rootDatabase.setPassword("postgres_password");
        config.setRootDatabase(rootDatabase);

        AppConfig.ReportConfig reportConfig = new AppConfig.ReportConfig();
        reportConfig.setOutputDirectory(outputDirectory);
        config.setReports(reportConfig);
        return config;
    }
}
