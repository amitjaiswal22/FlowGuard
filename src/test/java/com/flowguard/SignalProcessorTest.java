package com.flowguard;

import com.flowguard.engine.ProcessingReport;
import com.flowguard.engine.SignalProcessor;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import com.sun.net.httpserver.HttpServer;

public final class SignalProcessorTest {
    private SignalProcessorTest() {
    }

    public static void main(String[] args) throws Exception {
        processesAlertsAndDeadLettersConcurrently();
        rejectsWrongHeader();
        servesInteractiveWebFlow();
        System.out.println("All FlowGuard tests passed.");
    }

    private static void processesAlertsAndDeadLettersConcurrently() throws Exception {
        Path input = Files.createTempFile("flowguard-test", ".csv");
        try {
            Files.writeString(input, String.join("\n",
                    "event_id,timestamp,source,metric,value,threshold",
                    "evt-1,2026-01-01T10:00:00Z,checkout,error_rate,8.5,5",
                    "evt-2,2026-01-01T10:00:01Z,checkout,latency_ms,80,100",
                    "evt-1,2026-01-01T10:00:02Z,checkout,error_rate,9,5",
                    "evt-3,not-a-date,checkout,error_rate,9,5",
                    "evt-4,2026-01-01T10:00:04Z,payments,failure_rate,3,1"),
                    StandardCharsets.UTF_8);

            ProcessingReport report = new SignalProcessor().process(input, 3, 2);
            check(report.inputRows() == 5, "input row count");
            check(report.acceptedEvents() == 3, "accepted event count");
            check(report.alerts().size() == 2, "alert count");
            check(report.duplicateEvents() == 1, "duplicate count");
            check(report.invalidEvents() == 1, "invalid event count");
            check(report.deadLetters().size() == 2, "dead-letter record count");
            check(report.alertsBySourceMetric().get("checkout/error_rate") == 1L, "alert grouping");
            check(report.alertsBySourceMetric().get("payments/failure_rate") == 1L, "second alert grouping");
        } finally {
            Files.deleteIfExists(input);
        }
    }

    private static void rejectsWrongHeader() throws Exception {
        Path input = Files.createTempFile("flowguard-header-test", ".csv");
        try {
            Files.writeString(input, "wrong,header\n", StandardCharsets.UTF_8);
            try {
                new SignalProcessor().process(input, 1, 1);
                throw new AssertionError("wrong header should fail");
            } catch (java.io.IOException expected) {
                check(expected.getMessage().contains("Input header"), "header error message");
            }
        } finally {
            Files.deleteIfExists(input);
        }
    }

    private static void servesInteractiveWebFlow() throws Exception {
        HttpServer server = WebServer.createServer(0);
        try {
            server.start();
            int port = ((InetSocketAddress) server.getAddress()).getPort();
            HttpClient client = HttpClient.newHttpClient();
            URI baseUri = URI.create("http://127.0.0.1:" + port);

            HttpResponse<String> home = client.send(
                    HttpRequest.newBuilder(baseUri).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            check(home.statusCode() == 200, "web home status");
            check(home.body().contains("FlowGuard"), "web home content");

            String csv = String.join("\n",
                    "event_id,timestamp,source,metric,value,threshold",
                    "web-1,2026-01-01T10:00:00Z,checkout,error_rate,8,5");
            String form = "csv=" + URLEncoder.encode(csv, StandardCharsets.UTF_8);
            HttpResponse<String> result = client.send(
                    HttpRequest.newBuilder(baseUri)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            check(result.statusCode() == 200, "web analyze status");
            check(result.body().contains("Processing result"), "web result content");

            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("/download/([0-9a-f-]+)/alerts")
                    .matcher(result.body());
            check(matcher.find(), "web result download link");
            URI alertsUri = baseUri.resolve("/download/" + matcher.group(1) + "/alerts");
            HttpResponse<String> download = client.send(
                    HttpRequest.newBuilder(alertsUri).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            check(download.statusCode() == 200, "web alert download status");
            check(download.body().contains("web-1"), "web downloaded alert");
        } finally {
            WebServer.stopServer(server);
        }
    }

    private static void check(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError("Failed: " + description);
        }
    }
}
