package script.winnerCustomization.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import script.winnerCustomization.model.AppConfig;

import javax.sql.DataSource;

@Configuration
public class JdbcConfig {
    @Bean(name = "sourceDataSource")
    public DataSource sourceDataSource(RuntimeConfig runtimeConfig) {
        return toDataSource(runtimeConfig.get().getSourceDatabase());
    }

    @Bean(name = "sequenceDataSource")
    public DataSource sequenceDataSource(RuntimeConfig runtimeConfig) {
        return toDataSource(runtimeConfig.get().getSequenceDatabase());
    }

    @Bean(name = "sourceJdbc")
    public JdbcTemplate sourceJdbcTemplate(DataSource sourceDataSource) {
        return new JdbcTemplate(sourceDataSource);
    }

    @Bean(name = "sequenceJdbc")
    public JdbcTemplate sequenceJdbcTemplate(DataSource sequenceDataSource) {
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
