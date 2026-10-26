package com.flowguard;

import com.flowguard.engine.ProcessingReport;
import com.flowguard.engine.SignalProcessor;
import com.flowguard.io.CsvCodec;
import com.flowguard.model.ProcessOutcome;
import com.flowguard.model.SignalEvent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FlowGuard {
    private FlowGuard() {
    }

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 4) {
            usage();
            System.exit(2);
        }

        try {
            Path input = Path.of(args[0]);
            Path outputDirectory = Path.of(args[1]);
            int workers = args.length >= 3 ? Integer.parseInt(args[2]) : Runtime.getRuntime().availableProcessors();
            int queueCapacity = args.length >= 4 ? Integer.parseInt(args[3]) : Math.max(workers * 4, 16);

            Files.createDirectories(outputDirectory);
            ProcessingReport report = new SignalProcessor().process(input, workers, queueCapacity);
            writeAlerts(outputDirectory.resolve("alerts.csv"), report);
            writeDeadLetters(outputDirectory.resolve("dead-letter.csv"), report);
            printSummary(report, workers, queueCapacity);
        } catch (NumberFormatException exception) {
            System.err.println("ERROR: worker and queue values must be integers.");
            System.exit(2);
        } catch (IOException exception) {
            System.err.println("ERROR: " + exception.getMessage());
            System.exit(1);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            System.err.println("ERROR: processing was interrupted.");
            System.exit(1);
        }
    }

    private static void writeAlerts(Path path, ProcessingReport report) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("event_id,timestamp,source,metric,value,threshold");
            writer.newLine();
            for (SignalEvent event : report.alerts()) {
                writer.write(String.join(",",
                        CsvCodec.escape(event.eventId()),
                        CsvCodec.escape(event.timestamp().toString()),
                        CsvCodec.escape(event.source()),
                        CsvCodec.escape(event.metric()),
                        Double.toString(event.value()),
                        Double.toString(event.threshold())));
                writer.newLine();
            }
        }
    }

    private static void writeDeadLetters(Path path, ProcessingReport report) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("line_number,reason,raw_record");
            writer.newLine();
            for (ProcessOutcome outcome : report.deadLetters()) {
                writer.write(outcome.lineNumber()
                        + "," + CsvCodec.escape(outcome.error())
                        + "," + CsvCodec.escape(outcome.rawLine()));
                writer.newLine();
            }
        }
    }

    private static void printSummary(ProcessingReport report, int workers, int queueCapacity) {
        System.out.printf("FlowGuard completed in %.2f ms%n", report.elapsedNanos() / 1_000_000.0);
        System.out.printf("Rows: %d | accepted: %d | alerts: %d | duplicates: %d | invalid: %d%n",
                report.inputRows(),
                report.acceptedEvents(),
                report.alerts().size(),
                report.duplicateEvents(),
                report.invalidEvents());
        System.out.printf("Throughput: %.0f accepted events/sec | workers: %d | queue capacity: %d%n",
                report.eventsPerSecond(), workers, queueCapacity);
        report.alertsBySourceMetric().forEach(
                (key, count) -> System.out.printf("Alert group: %s = %d%n", key, count));
    }

    private static void usage() {
        System.err.println("Usage: java com.flowguard.FlowGuard <input.csv> <output-directory> [workers] [queue-capacity]");
        System.err.println("Input columns: " + CsvCodec.HEADER);
    }
}
