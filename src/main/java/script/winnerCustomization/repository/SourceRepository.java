package script.winnerCustomization.repository;
 
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Repository;
import script.winnerCustomization.config.ConfigLoader;
import script.winnerCustomization.config.DatabaseConfig;
import script.winnerCustomization.config.DbConnectionConfig;
import script.winnerCustomization.model.Detection;
 
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
 
@Repository
public class SourceRepository {
 
    private static final Logger log = LoggerFactory.getLogger(SourceRepository.class);
    private final ConfigLoader configLoader;
    private JdbcTemplate jdbcTemplate;
    private String schemaTable;
 
    public SourceRepository(ConfigLoader configLoader) {
        this.configLoader = configLoader;
    }
 
    public void initialize() {
        DatabaseConfig dbConfig = configLoader.getConfig().getDatabase();
        DbConnectionConfig srcConfig = dbConfig.getSource();
 
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(dbConfig.getJdbcUrl(srcConfig.getDb()));
        ds.setUsername(srcConfig.getUser());
        ds.setPassword(srcConfig.getPassword());
        this.jdbcTemplate = new JdbcTemplate(ds);
        this.schemaTable = "\"" + srcConfig.getSchema() + "\".alpr_detections";
 
        log.info("Source repository initialized: {}", dbConfig.getJdbcUrl(srcConfig.getDb()));
    }
 
    /**
     * Fetch all detections, sorted by created_at ascending.
     */
    public List<Detection> fetchAllDetections() {
        log.info("Fetching ALL detections from source...");
        String sql = "SELECT * FROM " + schemaTable + " ORDER BY created_at ASC";
        List<Detection> detections = jdbcTemplate.query(sql, new DetectionRowMapper());
        log.info("Fetched {} detections from source", detections.size());
        return detections;
    }
 
    /**
     * Fetch detections newer than the given timestamp, sorted by created_at ascending.
     */
    public List<Detection> fetchDetectionsAfter(LocalDateTime after) {
        log.debug("Fetching detections after {}", after);
        String sql = "SELECT * FROM " + schemaTable + " WHERE created_at > ? ORDER BY created_at ASC";
        List<Detection> detections = jdbcTemplate.query(sql, new DetectionRowMapper(), Timestamp.valueOf(after));
        log.debug("Fetched {} new detections", detections.size());
        return detections;
    }
 
    private static class DetectionRowMapper implements RowMapper<Detection> {
        @Override
        public Detection mapRow(ResultSet rs, int rowNum) throws SQLException {
            Detection d = new Detection();
            d.setId(rs.getLong("id"));
            d.setPlateNumber(rs.getString("plate_number"));
            d.setArabicNumber(rs.getString("arabic_number"));
            d.setAdr(rs.getString("adr"));
            d.setAnalyticsId(rs.getInt("analytics_id"));
            d.setMakeModelId(rs.getInt("make_model_id"));
            d.setVehicleType(rs.getInt("vehicle_type"));
            d.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            d.setColorId(rs.getObject("color_id") != null ? rs.getInt("color_id") : null);
            d.setDirection(rs.getObject("direction") != null ? rs.getInt("direction") : null);
            d.setCountry(rs.getString("country"));
            d.setPattern(rs.getString("pattern"));
            d.setClientId(rs.getInt("client_id"));
            return d;
        }
    }
}