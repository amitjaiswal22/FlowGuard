package com.flowguard.engine;

import com.flowguard.model.ProcessOutcome;
import com.flowguard.model.SignalEvent;

import java.util.List;
import java.util.Map;

public record ProcessingReport(
        long inputRows,
        long acceptedEvents,
        long duplicateEvents,
        long invalidEvents,
        List<SignalEvent> alerts,
        List<ProcessOutcome> deadLetters,
        Map<String, Long> alertsBySourceMetric,
        long elapsedNanos) {

    public double eventsPerSecond() {
        if (elapsedNanos == 0) {
            return 0;
        }
        return acceptedEvents * 1_000_000_000.0 / elapsedNanos;
    }
}
