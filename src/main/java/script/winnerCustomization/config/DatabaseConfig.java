package script.winnerCustomization.config;

public class DatabaseConfig {
    private String host;
    private int port = 5432;
    private String rootUser;
    private String rootPassword;
    private String maintenanceDb = "postgres";
    private DbConnectionConfig source;
    private DbConnectionConfig sequence;

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getRootUser() { return rootUser; }
    public void setRootUser(String rootUser) { this.rootUser = rootUser; }
    public String getRootPassword() { return rootPassword; }
    public void setRootPassword(String rootPassword) { this.rootPassword = rootPassword; }
    public String getMaintenanceDb() { return maintenanceDb; }
    public void setMaintenanceDb(String maintenanceDb) { this.maintenanceDb = maintenanceDb; }
    public DbConnectionConfig getSource() { return source; }
    public void setSource(DbConnectionConfig source) { this.source = source; }
    public DbConnectionConfig getSequence() { return sequence; }
    public void setSequence(DbConnectionConfig sequence) { this.sequence = sequence; }
}
