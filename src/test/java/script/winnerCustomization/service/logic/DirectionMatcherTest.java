package script.winnerCustomization.service.logic;
 
import org.junit.jupiter.api.Test;
 
import static org.junit.jupiter.api.Assertions.*;
 
class DirectionMatcherTest {
 
    @Test
    void angularDistance_sameDirection() {
        assertEquals(0, DirectionMatcher.angularDistance(0, 0));
        assertEquals(0, DirectionMatcher.angularDistance(180, 180));
    }
 
    @Test
    void angularDistance_opposite() {
        assertEquals(180, DirectionMatcher.angularDistance(0, 180));
        assertEquals(180, DirectionMatcher.angularDistance(180, 0));
    }
 
    @Test
    void angularDistance_wrapsAround() {
        // Shortest arc from 350 to 10 is 20, not 340
        assertEquals(20, DirectionMatcher.angularDistance(350, 10));
        assertEquals(20, DirectionMatcher.angularDistance(10, 350));
    }
 
    @Test
    void angularDistance_90degrees() {
        assertEquals(90, DirectionMatcher.angularDistance(0, 90));
        assertEquals(90, DirectionMatcher.angularDistance(270, 0));
    }
 
    @Test
    void matches_withinTolerance() {
        assertTrue(DirectionMatcher.matches(0, 0));
        assertTrue(DirectionMatcher.matches(90, 0)); // exactly at boundary
        assertTrue(DirectionMatcher.matches(45, 0));
    }
 
    @Test
    void matches_outsideTolerance() {
        assertFalse(DirectionMatcher.matches(91, 0));
        assertFalse(DirectionMatcher.matches(180, 0));
    }
 
    @Test
    void matches_wrapsAround_direction300() {
        // configured=300, tolerance=90 -> range 210-360 and 0-30
        assertTrue(DirectionMatcher.matches(300, 300));
        assertTrue(DirectionMatcher.matches(210, 300)); // exactly at boundary
        assertTrue(DirectionMatcher.matches(30, 300));   // wraps around 0
        assertTrue(DirectionMatcher.matches(0, 300));    // angular distance = 60
        assertTrue(DirectionMatcher.matches(350, 300));  // angular distance = 50
        assertFalse(DirectionMatcher.matches(200, 300)); // angular distance = 100
        assertFalse(DirectionMatcher.matches(120, 300)); // angular distance = 180
    }
 
    @Test
    void matches_customTolerance() {
        assertTrue(DirectionMatcher.matches(10, 0, 10));
        assertFalse(DirectionMatcher.matches(11, 0, 10));
    }
 
    @Test
    void rangesOverlap_nullDirectionAlwaysOverlaps() {
        assertTrue(DirectionMatcher.rangesOverlap(null, 90, 180, 90));
        assertTrue(DirectionMatcher.rangesOverlap(180, 90, null, 90));
        assertTrue(DirectionMatcher.rangesOverlap(null, 90, null, 90));
    }
 
    @Test
    void rangesOverlap_sameDirection() {
        assertTrue(DirectionMatcher.rangesOverlap(0, 90, 0, 90));
    }
 
    @Test
    void rangesOverlap_adjacentRanges() {
        // Two 90-degree ranges centered 180° apart don't overlap
        assertFalse(DirectionMatcher.rangesOverlap(0, 90, 180, 90));
    }
 
    @Test
    void rangesOverlap_overlappingRanges() {
        // centered 100° apart with 90° tolerance each -> overlap
        assertTrue(DirectionMatcher.rangesOverlap(0, 90, 100, 90));
    }
}