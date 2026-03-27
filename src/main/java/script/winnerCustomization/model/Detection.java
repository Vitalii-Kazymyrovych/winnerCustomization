package script.winnerCustomization.model;
 
import java.time.LocalDateTime;
 
/**
 * Represents a single ALPR detection from the source database.
 */
public class Detection {
 
    private long id;
    private String plateNumber;
    private String arabicNumber;
    private String adr;
    private int analyticsId;
    private int makeModelId;
    private int vehicleType;
    private LocalDateTime createdAt;
    private Integer colorId;
    private Integer direction;
    private String country;
    private String pattern;
    private int clientId;
 
    public long getId() { return id; }
    public void setId(long id) { this.id = id; }
 
    public String getPlateNumber() { return plateNumber; }
    public void setPlateNumber(String plateNumber) { this.plateNumber = plateNumber; }
 
    public String getArabicNumber() { return arabicNumber; }
    public void setArabicNumber(String arabicNumber) { this.arabicNumber = arabicNumber; }
 
    public String getAdr() { return adr; }
    public void setAdr(String adr) { this.adr = adr; }
 
    public int getAnalyticsId() { return analyticsId; }
    public void setAnalyticsId(int analyticsId) { this.analyticsId = analyticsId; }
 
    public int getMakeModelId() { return makeModelId; }
    public void setMakeModelId(int makeModelId) { this.makeModelId = makeModelId; }
 
    public int getVehicleType() { return vehicleType; }
    public void setVehicleType(int vehicleType) { this.vehicleType = vehicleType; }
 
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
 
    public Integer getColorId() { return colorId; }
    public void setColorId(Integer colorId) { this.colorId = colorId; }
 
    public Integer getDirection() { return direction; }
    public void setDirection(Integer direction) { this.direction = direction; }
 
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
 
    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }
 
    public int getClientId() { return clientId; }
    public void setClientId(int clientId) { this.clientId = clientId; }
 
    @Override
    public String toString() {
        return "Detection{plate='" + plateNumber + "', analyticsId=" + analyticsId +
                ", direction=" + direction + ", createdAt=" + createdAt + "}";
    }
}