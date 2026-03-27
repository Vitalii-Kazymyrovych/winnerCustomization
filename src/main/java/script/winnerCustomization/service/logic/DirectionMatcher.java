package script.winnerCustomization.service.logic;
 
/**
 * Utility for circular direction matching.
 * Direction values are 0-359 degrees. Matching uses a 90-degree tolerance by default.
 */
public class DirectionMatcher {
 
    public static final int DEFAULT_TOLERANCE = 90;
 
    /**
     * Compute the angular distance between two directions (shortest arc).
     * angularDistance(a, b) = min(|a - b|, 360 - |a - b|)
     */
    public static int angularDistance(int a, int b) {
        int diff = Math.abs(a - b);
        return Math.min(diff, 360 - diff);
    }
 
    /**
     * Check if a detected direction matches a configured direction within the given tolerance.
     */
    public static boolean matches(int detectedDirection, int configuredDirection, int tolerance) {
        return angularDistance(detectedDirection, configuredDirection) <= tolerance;
    }
 
    /**
     * Check if a detected direction matches a configured direction with default 90-degree tolerance.
     */
    public static boolean matches(int detectedDirection, int configuredDirection) {
        return matches(detectedDirection, configuredDirection, DEFAULT_TOLERANCE);
    }
 
    /**
     * Check if two direction ranges overlap.
     * Each range is centered on a direction with the given tolerance.
     */
    public static boolean rangesOverlap(Integer dir1, int tol1, Integer dir2, int tol2) {
        if (dir1 == null || dir2 == null) {
            return true; // null direction means "any" — always overlaps
        }
        int dist = angularDistance(dir1, dir2);
        return dist < (tol1 + tol2);
    }
}