package com.flowguard.model;

import java.time.Instant;

public record SignalEvent(
        String eventId,
        Instant timestamp,
        String source,
        String metric,
        double value,
        double threshold) {
}
