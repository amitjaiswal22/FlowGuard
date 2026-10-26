package com.flowguard.io;

import com.flowguard.model.SignalEvent;

import java.time.Instant;
import java.time.format.DateTimeParseException;

public final class CsvCodec {
    public static final String HEADER = "event_id,timestamp,source,metric,value,threshold";

    private CsvCodec() {
    }

    public static String eventIdIfPresent(String line) {
        int comma = line.indexOf(',');
        String firstColumn = comma < 0 ? line : line.substring(0, comma);
        String eventId = firstColumn.trim();
        return eventId.isEmpty() ? null : eventId;
    }

    public static SignalEvent parseEvent(String line) {
        String[] columns = line.split(",", -1);
        if (columns.length != 6) {
            throw new IllegalArgumentException("Expected 6 comma-separated columns");
        }

        String eventId = required(columns[0], "event_id");
        String timestampValue = required(columns[1], "timestamp");
        String source = required(columns[2], "source");
        String metric = required(columns[3], "metric");
        double value = parseFiniteDouble(columns[4], "value");
        double threshold = parseFiniteDouble(columns[5], "threshold");

        Instant timestamp;
        try {
            timestamp = Instant.parse(timestampValue);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("timestamp must be ISO-8601 UTC", exception);
        }
        return new SignalEvent(eventId, timestamp, source, metric, value, threshold);
    }

    public static String escape(String value) {
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String required(String value, String column) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(column + " must not be blank");
        }
        return trimmed;
    }

    private static double parseFiniteDouble(String value, String column) {
        try {
            double parsed = Double.parseDouble(value.trim());
            if (!Double.isFinite(parsed)) {
                throw new IllegalArgumentException(column + " must be a finite number");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(column + " must be a number", exception);
        }
    }
}
