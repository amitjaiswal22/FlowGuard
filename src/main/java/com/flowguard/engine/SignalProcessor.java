package com.flowguard.engine;

import com.flowguard.io.CsvCodec;
import com.flowguard.model.ProcessOutcome;
import com.flowguard.model.SignalEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

public final class SignalProcessor {
    public ProcessingReport process(Path input, int workers, int queueCapacity)
            throws IOException, InterruptedException {
        if (workers < 1) {
            throw new IllegalArgumentException("workers must be at least 1");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queue capacity must be at least 1");
        }

        long startedAt = System.nanoTime();
        List<SignalEvent> alerts = new ArrayList<>();
        List<ProcessOutcome> deadLetters = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        ConcurrentHashMap<String, LongAdder> alertsBySourceMetric = new ConcurrentHashMap<>();
        long acceptedEvents = 0;
        long duplicateEvents = 0;
        long inputRows = 0;

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                workers,
                workers,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.CallerRunsPolicy());
        CompletionService<ProcessOutcome> completion = new ExecutorCompletionService<>(executor);
        int inFlight = 0;
        int maxInFlight = workers + queueCapacity;

        try (BufferedReader reader = Files.newBufferedReader(input, StandardCharsets.UTF_8)) {
            String header = reader.readLine();
            if (!CsvCodec.HEADER.equals(header)) {
                throw new IOException("Input header must be: " + CsvCodec.HEADER);
            }

            String line;
            long lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                inputRows++;
                String eventId = CsvCodec.eventIdIfPresent(line);
                if (eventId != null && !seenIds.add(eventId)) {
                    duplicateEvents++;
                    deadLetters.add(ProcessOutcome.rejected(lineNumber, line, "duplicate event_id: " + eventId));
                    continue;
                }

                String rawLine = line;
                long currentLineNumber = lineNumber;
                completion.submit(() -> processLine(currentLineNumber, rawLine, alertsBySourceMetric));
                inFlight++;
                if (inFlight >= maxInFlight) {
                    ProcessOutcome outcome = takeCompleted(completion);
                    inFlight--;
                    acceptedEvents += collect(outcome, alerts, deadLetters);
                }
            }

            while (inFlight > 0) {
                ProcessOutcome outcome = takeCompleted(completion);
                inFlight--;
                acceptedEvents += collect(outcome, alerts, deadLetters);
            }
        } finally {
            executor.shutdownNow();
        }

        alerts.sort((left, right) -> Long.compare(
                left.timestamp().toEpochMilli(), right.timestamp().toEpochMilli()));
        Map<String, Long> alertCounts = new TreeMap<>();
        alertsBySourceMetric.forEach((key, count) -> alertCounts.put(key, count.sum()));
        return new ProcessingReport(
                inputRows,
                acceptedEvents,
                duplicateEvents,
                deadLetters.size() - duplicateEvents,
                List.copyOf(alerts),
                List.copyOf(deadLetters),
                Map.copyOf(alertCounts),
                System.nanoTime() - startedAt);
    }

    private static ProcessOutcome processLine(
            long lineNumber,
            String rawLine,
            ConcurrentHashMap<String, LongAdder> alertsBySourceMetric) {
        try {
            SignalEvent event = CsvCodec.parseEvent(rawLine);
            if (event.value() > event.threshold()) {
                String key = event.source() + "/" + event.metric();
                alertsBySourceMetric.computeIfAbsent(key, ignored -> new LongAdder()).increment();
            }
            return ProcessOutcome.accepted(lineNumber, rawLine, event);
        } catch (IllegalArgumentException exception) {
            return ProcessOutcome.rejected(lineNumber, rawLine, exception.getMessage());
        }
    }

    private static ProcessOutcome takeCompleted(CompletionService<ProcessOutcome> completion)
            throws InterruptedException, IOException {
        try {
            Future<ProcessOutcome> completed = completion.take();
            return completed.get();
        } catch (java.util.concurrent.ExecutionException exception) {
            throw new IOException("Unexpected worker failure", exception.getCause());
        }
    }

    private static long collect(
            ProcessOutcome outcome,
            List<SignalEvent> alerts,
            List<ProcessOutcome> deadLetters) {
        if (outcome.error() != null) {
            deadLetters.add(outcome);
            return 0;
        }
        SignalEvent event = outcome.event();
        if (event.value() > event.threshold()) {
            alerts.add(event);
        }
        return 1;
    }
}
