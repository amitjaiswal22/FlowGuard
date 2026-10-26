package com.flowguard.model;

public record ProcessOutcome(
        long lineNumber,
        String rawLine,
        SignalEvent event,
        String error) {

    public static ProcessOutcome accepted(long lineNumber, String rawLine, SignalEvent event) {
        return new ProcessOutcome(lineNumber, rawLine, event, null);
    }

    public static ProcessOutcome rejected(long lineNumber, String rawLine, String error) {
        return new ProcessOutcome(lineNumber, rawLine, null, error);
    }
}
