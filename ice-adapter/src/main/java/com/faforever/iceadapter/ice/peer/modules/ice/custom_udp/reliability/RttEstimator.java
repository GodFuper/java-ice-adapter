package com.faforever.iceadapter.ice.peer.modules.ice.custom_udp.reliability;

import lombok.Getter;
import lombok.ToString;

/**
 * RTT/SRTT/RTTVAR/RTO estimator adapted from TCP but tuned for gaming.
 * <p>
 * Uses exponential weighted moving averages:
 * SRTT = alpha * RTT + (1 - alpha) * SRTT
 * RTTVAR = beta * |RTT - SRTT| + (1 - beta) * RTTVAR
 * RTO = SRTT + 4 * RTTVAR
 * <p>
 * Configurable minimum and maximum RTO values.
 */
@Getter
@ToString
public class RttEstimator {

    private static final double DEFAULT_ALPHA = 0.125;
    private static final double DEFAULT_BETA = 0.25;

    private volatile double srtt;
    private volatile double rttvar;
    private volatile boolean measured;

    private final double alpha;
    private final double beta;
    private final long minRtoMs;
    private final long maxRtoMs;

    public RttEstimator() {
        this(DEFAULT_ALPHA, DEFAULT_BETA, 50, 5000);
    }

    public RttEstimator(double alpha, double beta, long minRtoMs, long maxRtoMs) {
        this.alpha = alpha;
        this.beta = beta;
        this.minRtoMs = minRtoMs;
        this.maxRtoMs = maxRtoMs;
    }

    /**
     * Record a new RTT measurement.
     *
     * @param rttMs the measured RTT in milliseconds
     */
    public synchronized void recordRtt(double rttMs) {
        if (!measured) {
            this.srtt = rttMs;
            this.rttvar = rttMs / 2;
            this.measured = true;
        } else {
            double delta = Math.abs(rttMs - srtt);
            this.srtt = alpha * rttMs + (1 - alpha) * srtt;
            this.rttvar = beta * delta + (1 - beta) * rttvar;
        }
    }

    /**
     * Get the current estimated RTO in milliseconds.
     */
    public synchronized long getRtoMs() {
        if (!measured) {
            return minRtoMs;
        }
        long rto = (long) (srtt + 4 * rttvar);
        return Math.max(minRtoMs, Math.min(maxRtoMs, rto));
    }

    /**
     * Reset the estimator.
     */
    public synchronized void reset() {
        this.srtt = 0;
        this.rttvar = 0;
        this.measured = false;
    }
}
