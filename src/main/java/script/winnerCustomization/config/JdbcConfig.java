package script.winnerCustomization.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import script.winnerCustomization.model.AppConfig;

import javax.sql.DataSource;

@Configuration
public class JdbcConfig {
    private static final Logger log = LoggerFactory.getLogger(JdbcConfig.class);

    @Bean(name = "sourceDataSource")
    public DataSource sourceDataSource(RuntimeConfig runtimeConfig) {
        log.info("Initializing source datasource for {}:{} / {}",
                runtimeConfig.get().getSourceDatabase().getHost(),
                runtimeConfig.get().getSourceDatabase().getPort(),
                runtimeConfig.get().getSourceDatabase().getDb());
        return toDataSource(runtimeConfig.get().getSourceDatabase());
    }

    @Bean(name = "sequenceDataSource")
    public DataSource sequenceDataSource(RuntimeConfig runtimeConfig) {
        log.info("Initializing sequence datasource for {}:{} / {}",
                runtimeConfig.get().getSequenceDatabase().getHost(),
                runtimeConfig.get().getSequenceDatabase().getPort(),
                runtimeConfig.get().getSequenceDatabase().getDb());
        return toDataSource(runtimeConfig.get().getSequenceDatabase());
    }

    @Bean(name = "sourceJdbc")
    public JdbcTemplate sourceJdbcTemplate(DataSource sourceDataSource) {
        log.info("Creating source JdbcTemplate bean");
        return new JdbcTemplate(sourceDataSource);
    }

    @Bean(name = "sequenceJdbc")
    public JdbcTemplate sequenceJdbcTemplate(DataSource sequenceDataSource) {
        log.info("Creating sequence JdbcTemplate bean");
        return new JdbcTemplate(sequenceDataSource);
    }

    private DataSource toDataSource(AppConfig.DatabaseConfig config) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(config.jdbcUrl());
        ds.setUsername(config.getUser());
        ds.setPassword(config.getPassword());
        return ds;
    }
}
