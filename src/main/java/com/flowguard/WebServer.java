package com.flowguard;

import com.flowguard.engine.ProcessingReport;
import com.flowguard.engine.SignalProcessor;
import com.flowguard.io.CsvCodec;
import com.flowguard.model.ProcessOutcome;
import com.flowguard.model.SignalEvent;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

public final class WebServer {
    private static final int MAX_REQUEST_BYTES = 2 * 1024 * 1024;
    private static final int MAX_RESULTS = 20;
    private static final long RESULT_TTL_MILLIS = 15 * 60 * 1000L;
    private static final int PROCESSING_WORKERS = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    private static final Semaphore ACTIVE_JOBS = new Semaphore(2);
    private static final ConcurrentHashMap<UUID, StoredResult> RESULTS = new ConcurrentHashMap<>();
    private static final ArrayDeque<UUID> RESULT_ORDER = new ArrayDeque<>();

    private WebServer() {
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = createServer(port);
        server.start();
        System.out.println("FlowGuard web app listening on port " + port);
    }

    static HttpServer createServer(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 32);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        server.setExecutor(executor);
        server.createContext("/health", exchange -> respond(exchange, 200, "text/plain; charset=utf-8", "ok"));
        server.createContext("/download/", WebServer::download);
        server.createContext("/", WebServer::home);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> stopServer(server)));
        return server;
    }

    static void stopServer(HttpServer server) {
        server.stop(0);
        if (server.getExecutor() instanceof ExecutorService executor) {
            executor.shutdownNow();
        }
    }

    private static void home(HttpExchange exchange) throws IOException {
        if ("/".equals(exchange.getRequestURI().getPath()) && "GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 200, "text/html; charset=utf-8", page(null, null));
            return;
        }
        if (!"/".equals(exchange.getRequestURI().getPath()) || !"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain; charset=utf-8", "Method not allowed");
            return;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.startsWith("application/x-www-form-urlencoded")) {
            respond(exchange, 415, "text/plain; charset=utf-8", "Submit CSV as a form.");
            return;
        }

        byte[] requestBody = readLimited(exchange, MAX_REQUEST_BYTES);
        if (requestBody == null) {
            respond(exchange, 413, "text/plain; charset=utf-8", "CSV is too large. Maximum request size is 2 MB.");
            return;
        }
        String csv = formValue(new String(requestBody, StandardCharsets.UTF_8), "csv");
        if (csv == null || csv.isBlank()) {
            respond(exchange, 400, "text/html; charset=utf-8", page("Paste or choose a CSV file first.", null));
            return;
        }
        if (!ACTIVE_JOBS.tryAcquire()) {
            respond(exchange, 429, "text/html; charset=utf-8", page("The demo is busy. Please try again shortly.", null));
            return;
        }

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("flowguard-upload-", ".csv");
            Files.writeString(tempFile, csv, StandardCharsets.UTF_8);
            ProcessingReport report = new SignalProcessor().process(tempFile, PROCESSING_WORKERS, PROCESSING_WORKERS * 4);
            UUID resultId = storeResult(report);
            respond(exchange, 200, "text/html; charset=utf-8", page(null, resultId));
        } catch (IOException | IllegalArgumentException exception) {
            respond(exchange, 400, "text/html; charset=utf-8", page(exception.getMessage(), null));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            respond(exchange, 503, "text/html; charset=utf-8", page("Processing was interrupted. Please retry.", null));
        } finally {
            ACTIVE_JOBS.release();
            if (tempFile != null) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    private static void download(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "text/plain; charset=utf-8", "Method not allowed");
            return;
        }
        String[] parts = exchange.getRequestURI().getPath().split("/");
        if (parts.length != 4) {
            respond(exchange, 404, "text/plain; charset=utf-8", "Result not found");
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(parts[2]);
        } catch (IllegalArgumentException exception) {
            respond(exchange, 404, "text/plain; charset=utf-8", "Result not found");
            return;
        }
        StoredResult stored = RESULTS.get(id);
        if (stored == null || isExpired(stored)) {
            RESULTS.remove(id);
            respond(exchange, 404, "text/plain; charset=utf-8", "Result expired. Submit the CSV again.");
            return;
        }
        String content;
        if ("alerts".equals(parts[3])) {
            content = alertsCsv(stored.report());
        } else if ("dead-letters".equals(parts[3])) {
            content = deadLettersCsv(stored.report());
        } else {
            respond(exchange, 404, "text/plain; charset=utf-8", "Result not found");
            return;
        }
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + parts[3] + ".csv\"");
        respond(exchange, 200, "text/csv; charset=utf-8", content);
    }

    private static UUID storeResult(ProcessingReport report) {
        UUID id = UUID.randomUUID();
        long now = Instant.now().toEpochMilli();
        synchronized (RESULT_ORDER) {
            while (!RESULT_ORDER.isEmpty()) {
                UUID oldestId = RESULT_ORDER.peekFirst();
                StoredResult oldest = RESULTS.get(oldestId);
                if (oldest != null && now - oldest.createdAtMillis() <= RESULT_TTL_MILLIS) {
                    break;
                }
                RESULT_ORDER.removeFirst();
                RESULTS.remove(oldestId);
            }
            while (RESULT_ORDER.size() >= MAX_RESULTS) {
                RESULTS.remove(RESULT_ORDER.removeFirst());
            }
            RESULTS.put(id, new StoredResult(report, now));
            RESULT_ORDER.addLast(id);
        }
        return id;
    }

    private static boolean isExpired(StoredResult result) {
        return Instant.now().toEpochMilli() - result.createdAtMillis() > RESULT_TTL_MILLIS;
    }

    private static String alertsCsv(ProcessingReport report) {
        StringBuilder csv = new StringBuilder("event_id,timestamp,source,metric,value,threshold\n");
        for (SignalEvent event : report.alerts()) {
            csv.append(String.join(",",
                    CsvCodec.escape(event.eventId()),
                    CsvCodec.escape(event.timestamp().toString()),
                    CsvCodec.escape(event.source()),
                    CsvCodec.escape(event.metric()),
                    Double.toString(event.value()),
                    Double.toString(event.threshold()))).append('\n');
        }
        return csv.toString();
    }

    private static String deadLettersCsv(ProcessingReport report) {
        StringBuilder csv = new StringBuilder("line_number,reason,raw_record\n");
        for (ProcessOutcome outcome : report.deadLetters()) {
            csv.append(outcome.lineNumber())
                    .append(',').append(CsvCodec.escape(outcome.error()))
                    .append(',').append(CsvCodec.escape(outcome.rawLine()))
                    .append('\n');
        }
        return csv.toString();
    }

    private static String page(String error, UUID resultId) {
        StringBuilder html = new StringBuilder("""
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>FlowGuard | Event Monitor</title>
                  <style>
                    :root{color-scheme:dark;--bg:#101827;--panel:#19253a;--line:#30415e;--ink:#edf3fc;--muted:#a8b7cc;--accent:#6ee7b7;--warn:#fbbf24}
                    *{box-sizing:border-box}body{margin:0;background:radial-gradient(ellipse at 10% 0,#23385d,var(--bg) 50%);font:16px/1.5 system-ui,Segoe UI,sans-serif;color:var(--ink)}
                    main{max-width:920px;margin:48px auto;padding:0 20px}.brand{color:var(--accent);font-size:13px;letter-spacing:.2em;text-transform:uppercase;font-weight:700}
                    h1{font-size:clamp(34px,6vw,58px);line-height:1.05;margin:12px 0}p{color:var(--muted)}.panel{background:var(--panel);border:1px solid var(--line);border-radius:16px;padding:24px;margin:24px 0;box-shadow:0 18px 50px #0003}
                    textarea{width:100%;min-height:240px;background:#0d1523;color:var(--ink);border:1px solid var(--line);border-radius:10px;padding:14px;font:13px/1.6 ui-monospace,Consolas,monospace}
                    input[type=file]{display:block;margin:12px 0;color:var(--muted)}button,.link{display:inline-block;background:var(--accent);color:#10231f;border:0;border-radius:8px;padding:11px 16px;font-weight:750;text-decoration:none;cursor:pointer;margin:12px 10px 0 0}
                    .muted{font-size:13px}.error{color:#fecaca;background:#451f2b;padding:12px;border-radius:8px}.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(145px,1fr));gap:12px}.card{padding:16px;background:#111c2c;border:1px solid var(--line);border-radius:10px}.number{font-size:26px;font-weight:750;color:var(--accent)}.label{font-size:13px;color:var(--muted)}
                    footer{padding:20px 0;color:var(--muted);font-size:13px}
                  </style>
                </head>
                <body><main>
                  <div class="brand">Java 17 · Concurrent event processing</div>
                  <h1>FlowGuard</h1>
                  <p>Upload an operational events CSV. FlowGuard checks thresholds, filters duplicate IDs, and separates invalid rows.</p>
                  <section class="panel">
                    <form method="post" enctype="application/x-www-form-urlencoded">
                      <label for="file">Choose a CSV file</label><input id="file" type="file" accept=".csv,text/csv">
                      <label for="csv">Or paste CSV content</label>
                      <textarea id="csv" name="csv" required>event_id,timestamp,source,metric,value,threshold
                evt-1,2026-10-23T08:00:00Z,checkout-api,error_rate,7.2,5.0
                evt-2,2026-10-23T08:00:01Z,checkout-api,latency_ms,180,250
                evt-3,2026-10-23T08:00:02Z,payment-service,failure_rate,3.4,2.0
                </textarea>
                      <div class="muted">Maximum request size: 2 MB. Do not upload confidential or personal data. Results expire after 15 minutes.</div>
                      <button type="submit">Analyze events</button>
                    </form>
                  </section>
                """);
        if (error != null) {
            html.append("<div class=\"error\">").append(escapeHtml(error)).append("</div>");
        }
        if (resultId != null) {
            StoredResult stored = RESULTS.get(resultId);
            if (stored != null) {
                ProcessingReport report = stored.report();
                html.append("<section class=\"panel\"><h2>Processing result</h2><div class=\"cards\">");
                card(html, "Rows", report.inputRows());
                card(html, "Accepted", report.acceptedEvents());
                card(html, "Alerts", report.alerts().size());
                card(html, "Duplicates", report.duplicateEvents());
                card(html, "Invalid", report.invalidEvents());
                html.append("</div><p>Throughput: ")
                        .append(String.format("%.0f", report.eventsPerSecond()))
                        .append(" accepted events/sec · ")
                        .append(String.format("%.2f", report.elapsedNanos() / 1_000_000.0))
                        .append(" ms · ").append(PROCESSING_WORKERS).append(" workers</p>")
                        .append("<a class=\"link\" href=\"/download/").append(resultId)
                        .append("/alerts\">Download alerts.csv</a><a class=\"link\" href=\"/download/")
                        .append(resultId).append("/dead-letters\">Download dead-letter.csv</a></section>");
            }
        }
        html.append("<footer>Demo only: uploads are processed in memory-backed temporary storage and result downloads expire after 15 minutes.</footer>")
                .append("<script>document.getElementById('file').addEventListener('change',async e=>{const f=e.target.files[0];if(f){document.getElementById('csv').value=await f.text()}})</script>")
                .append("</main></body></html>");
        return html.toString();
    }

    private static void card(StringBuilder html, String label, long value) {
        html.append("<div class=\"card\"><div class=\"number\">").append(value)
                .append("</div><div class=\"label\">").append(label).append("</div></div>");
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String formValue(String form, String key) {
        Map<String, String> values = new HashMap<>();
        for (String pair : form.split("&")) {
            int separator = pair.indexOf('=');
            String name = separator < 0 ? pair : pair.substring(0, separator);
            String value = separator < 0 ? "" : pair.substring(separator + 1);
            values.put(URLDecoder.decode(name, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return values.get(key);
    }

    private static byte[] readLimited(HttpExchange exchange, int maxBytes) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = exchange.getRequestBody().read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                return null;
            }
            body.write(buffer, 0, read);
        }
        return body.toByteArray();
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; form-action 'self'; base-uri 'none'");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private record StoredResult(ProcessingReport report, long createdAtMillis) {
    }
}
