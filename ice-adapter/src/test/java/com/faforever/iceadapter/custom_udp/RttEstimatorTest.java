package com.faforever.iceadapter.custom_udp;

import com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability.RttEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RttEstimatorTest {

    private RttEstimator estimator;

    @BeforeEach
    void setUp() {
        estimator = new RttEstimator(0.125, 0.25, 50, 5000);
    }

    @Test
    void testInitialRto() {
        // Before any measurement, should return minRto
        assertEquals(50, estimator.getRtoMs());
        assertFalse(estimator.isMeasured());
    }

    @Test
    void testFirstMeasurement() {
        estimator.recordRtt(100);

        assertTrue(estimator.isMeasured());
        // SRTT should be 100 (first measurement)
        assertEquals(100, estimator.getSrtt(), 0.01);
        // RTTVAR should be 50 (half of first RTT)
        assertEquals(50, estimator.getRttvar(), 0.01);
    }

    @Test
    void testSrttConvergence() {
        // Initial measurement
        estimator.recordRtt(100);

        // Add several measurements with similar RTT
        estimator.recordRtt(105);
        estimator.recordRtt(98);
        estimator.recordRtt(102);
        estimator.recordRtt(101);

        // SRTT should be converging towards ~100
        double srtt = estimator.getSrtt();
        assertTrue(srtt > 95 && srtt < 105);
    }

    @Test
    void testRttVariation() {
        estimator.recordRtt(100);
        estimator.recordRtt(200); // Large jump

        double rttvar = estimator.getRttvar();
        assertTrue(rttvar > 0);
    }

    @Test
    void testRtoBounded() {
        estimator.recordRtt(100);

        // After many measurements with consistent RTT
        for (int i = 0; i < 20; i++) {
            estimator.recordRtt(100);
        }

        long rto = estimator.getRtoMs();
        assertTrue(rto >= 50);
        assertTrue(rto <= 5000);
    }

    @Test
    void testRtoMinBound() {
        // Simulate very stable network with low RTT
        estimator.recordRtt(10);
        for (int i = 0; i < 50; i++) {
            estimator.recordRtt(10);
        }

        long rto = estimator.getRtoMs();
        assertEquals(50, rto); // Should be bounded to minRto
    }

    @Test
    void testRtoMaxBound() {
        // Simulate very unstable network
        estimator.recordRtt(4000);
        estimator.recordRtt(4500);
        for (int i = 0; i < 10; i++) {
            estimator.recordRtt(4800);
        }

        long rto = estimator.getRtoMs();
        assertEquals(5000, rto); // Should be bounded to maxRto
    }

    @Test
    void testReset() {
        estimator.recordRtt(100);
        assertTrue(estimator.isMeasured());

        estimator.reset();
        assertFalse(estimator.isMeasured());
        assertEquals(50, estimator.getRtoMs());
    }

    @Test
    void testCustomParameters() {
        RttEstimator custom = new RttEstimator(0.25, 0.5, 100, 3000);

        assertEquals(100, custom.getRtoMs()); // minRto
        custom.recordRtt(500);
        custom.recordRtt(2500);

        long rto = custom.getRtoMs();
        assertTrue(rto >= 100);
        assertTrue(rto <= 3000);
    }
}
