package script.winnerCustomization.model;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class SequenceRecord {
    private final String plateNumber;
    private final LocalDateTime startedAt;
    private LocalDateTime driveInOutAt;
    private LocalDateTime driveInToServiceAt;
    private LocalDateTime serviceInAt;
    private LocalDateTime serviceFirstFinishedAt;
    private LocalDateTime postInAt;
    private LocalDateTime postOutAt;
    private LocalDateTime secondServiceInAt;
    private LocalDateTime serviceOutAt;
    private LocalDateTime serviceToDriveInAt;
    private LocalDateTime parkingInAt;
    private LocalDateTime parkingOutAt;
    private LocalDateTime finishedAt;
    private LocalDateTime testDriveAnchorAt;
    private LocalDateTime pendingBackyardStartedAt;
    private PendingBackyardExpectation pendingBackyardExpectation;
    private String path;
    private final List<String> alerts = new ArrayList<>();
    private final List<StageWindow> backyardStages = new ArrayList<>();

    public SequenceRecord(String plateNumber, LocalDateTime startedAt) {
        this.plateNumber = plateNumber;
        this.startedAt = startedAt;
        this.path = "Drive in (in)";
    }

    public String getPlateNumber() { return plateNumber; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public LocalDateTime getDriveInOutAt() { return driveInOutAt; }
    public void setDriveInOutAt(LocalDateTime driveInOutAt) { this.driveInOutAt = driveInOutAt; appendPath("Drive in (out)"); }
    public LocalDateTime getDriveInToServiceAt() { return driveInToServiceAt; }
    public void setDriveInToServiceAt(LocalDateTime driveInToServiceAt) { this.driveInToServiceAt = driveInToServiceAt; appendPath("Drive-In -> Service"); }
    public LocalDateTime getServiceInAt() { return serviceInAt; }
    public void setServiceInAt(LocalDateTime serviceInAt) { this.serviceInAt = serviceInAt; appendPath("Service (in)"); }
    public LocalDateTime getServiceFirstFinishedAt() { return serviceFirstFinishedAt; }
    public void setServiceFirstFinishedAt(LocalDateTime serviceFirstFinishedAt) { this.serviceFirstFinishedAt = serviceFirstFinishedAt; }
    public LocalDateTime getPostInAt() { return postInAt; }
    public void setPostInAt(LocalDateTime postInAt) { this.postInAt = postInAt; appendPath("Service post (in)"); }
    public LocalDateTime getPostOutAt() { return postOutAt; }
    public void setPostOutAt(LocalDateTime postOutAt) { this.postOutAt = postOutAt; appendPath("Service post (out)"); }
    public LocalDateTime getSecondServiceInAt() { return secondServiceInAt; }
    public void setSecondServiceInAt(LocalDateTime secondServiceInAt) { this.secondServiceInAt = secondServiceInAt; }
    public LocalDateTime getServiceOutAt() { return serviceOutAt; }
    public void setServiceOutAt(LocalDateTime serviceOutAt) { this.serviceOutAt = serviceOutAt; appendPath("Service (out)"); }
    public LocalDateTime getServiceToDriveInAt() { return serviceToDriveInAt; }
    public void setServiceToDriveInAt(LocalDateTime serviceToDriveInAt) { this.serviceToDriveInAt = serviceToDriveInAt; appendPath("Service -> Drive-In"); }
    public LocalDateTime getParkingInAt() { return parkingInAt; }
    public void setParkingInAt(LocalDateTime parkingInAt) { this.parkingInAt = parkingInAt; appendPath("Parking (in)"); }
    public LocalDateTime getParkingOutAt() { return parkingOutAt; }
    public void setParkingOutAt(LocalDateTime parkingOutAt) { this.parkingOutAt = parkingOutAt; appendPath("Parking (out)"); }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public LocalDateTime getTestDriveAnchorAt() { return testDriveAnchorAt; }
    public void setTestDriveAnchorAt(LocalDateTime testDriveAnchorAt) { this.testDriveAnchorAt = testDriveAnchorAt; }
    public LocalDateTime getPendingBackyardStartedAt() { return pendingBackyardStartedAt; }
    public PendingBackyardExpectation getPendingBackyardExpectation() { return pendingBackyardExpectation; }
    public void startPendingBackyard(LocalDateTime startedAt, PendingBackyardExpectation expectation) {
        this.pendingBackyardStartedAt = startedAt;
        this.pendingBackyardExpectation = expectation;
    }
    public void clearPendingBackyard() {
        this.pendingBackyardStartedAt = null;
        this.pendingBackyardExpectation = null;
    }
    public void addBackyardStage(LocalDateTime timeIn, LocalDateTime timeOut) {
        backyardStages.add(new StageWindow("Backyard", timeIn, timeOut));
    }
    public List<StageWindow> getBackyardStages() { return backyardStages; }
    public String getPath() { return path; }
    public List<String> getAlerts() { return alerts; }

    public String stageDurations() {
        StringBuilder sb = new StringBuilder();
        appendDuration(sb, "DriveIn", startedAt, driveInOutAt);
        appendDuration(sb, "Service", serviceInAt, serviceFirstFinishedAt);
        appendDuration(sb, "Post", postInAt, postOutAt);
        appendDuration(sb, "Service", secondServiceInAt, serviceOutAt);
        backyardStages.stream()
                .sorted(Comparator.comparing(StageWindow::timeIn, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(stage -> appendDuration(sb, stage.stageName(), stage.timeIn(), stage.timeOut()));
        appendDuration(sb, "Parking", parkingInAt, parkingOutAt);
        return sb.toString();
    }

    private void appendDuration(StringBuilder sb, String label, LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("; ");
        }
        sb.append(label).append('=').append(Duration.between(from, to).toMinutes()).append("m");
    }

    private void appendPath(String step) {
        this.path = this.path + " -> " + step;
    }

    public void addAlert(String message) {
        alerts.add(message);
    }

    public record StageWindow(String stageName, LocalDateTime timeIn, LocalDateTime timeOut) {
    }

    public enum PendingBackyardExpectation {
        SERVICE_IN,
        SERVICE_TO_DRIVE_IN
    }
}
