package script.winnerCustomization.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import script.winnerCustomization.service.DatabaseBootstrapService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcConfigTest {
    @Test
    void createsSourceAndSequenceDataSourcesAndJdbcTemplates() {
        RuntimeConfig runtimeConfig = mock(RuntimeConfig.class);
        var config = TestFixtures.configWithReportDirectory("");
        when(runtimeConfig.get()).thenReturn(config);
        DatabaseBootstrapService databaseBootstrapService = mock(DatabaseBootstrapService.class);

        JdbcConfig jdbcConfig = new JdbcConfig();
        var sourceDataSource = jdbcConfig.sourceDataSource(runtimeConfig);
        var sequenceDataSource = jdbcConfig.sequenceDataSource(runtimeConfig, databaseBootstrapService);
        JdbcTemplate sourceJdbc = jdbcConfig.sourceJdbcTemplate(sourceDataSource);
        JdbcTemplate sequenceJdbc = jdbcConfig.sequenceJdbcTemplate(sequenceDataSource);

        verify(databaseBootstrapService).ensureDatabaseExists(config.getRootDatabase(), config.getSequenceDatabase());
        assertThat(sourceDataSource).isNotNull();
        assertThat(sequenceDataSource).isNotNull();
        assertThat(sourceJdbc.getDataSource()).isSameAs(sourceDataSource);
        assertThat(sequenceJdbc.getDataSource()).isSameAs(sequenceDataSource);
    }
}
