package io.github.nitesh050.sched.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * A small local web dashboard: {@code java -jar scheduler.jar --ui} then open
 * http://localhost:8080. It runs real experiments on this machine and shows the results.
 * Bound to the loopback address only, so nothing outside this computer can reach it.
 *
 * <pre>
 *   GET  /                 the page
 *   GET  /api/options      workloads, strategies, descriptions
 *   POST /api/run          {"workload", "workers", "strategy"}           one run
 *   POST /api/compare      {"workload", "workers", "strategies": [...]}  one run per strategy
 *   POST /api/deadlock     {"philosophers", "meals", "holdMicros"}       dining philosophers
 *   GET  /api/results      the Phase 4 summary (docs/report/figures/summary.csv), if present
 * </pre>
 */
final class Dashboard {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path SUMMARY = Path.of("docs", "report", "figures", "summary.csv");

    private Dashboard() {
    }

    static void serve(int port) throws IOException, InterruptedException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.createContext("/", Dashboard::page);
        server.createContext("/api/options", ex -> json(ex, () -> options()));
        server.createContext("/api/run", ex -> json(ex, () -> {
            JsonNode b = body(ex);
            return DashboardRunner.run(b.path("workload").asText("shifting"), b.path("workers").asInt(8),
                    b.path("strategy").asText("adaptive"), b.path("warmup").asInt(1));
        }));
        server.createContext("/api/compare", ex -> json(ex, () -> {
            JsonNode b = body(ex);
            List<Object> runs = new ArrayList<>();
            for (JsonNode s : b.path("strategies")) {
                runs.add(DashboardRunner.run(b.path("workload").asText("shifting"), b.path("workers").asInt(8),
                        s.asText(), b.path("warmup").asInt(1)));
            }
            return runs;
        }));
        server.createContext("/api/deadlock", ex -> json(ex, () -> {
            JsonNode b = body(ex);
            return DashboardRunner.deadlock(b.path("philosophers").asInt(5), b.path("meals").asInt(20),
                    b.path("holdMicros").asInt(200));
        }));
        server.createContext("/api/results", ex -> json(ex, Dashboard::results));
        server.start();
        System.out.printf("Dashboard running at http://localhost:%d  (Ctrl+C to stop)%n", port);
        Thread.currentThread().join();
    }

    private static Map<String, Object> options() {
        Map<String, Object> o = new LinkedHashMap<>();
        Map<String, String> strategies = new LinkedHashMap<>();
        for (String s : DashboardRunner.STRATEGIES) {
            strategies.put(s, DashboardRunner.label(s));
        }
        o.put("strategies", strategies);
        Map<String, Map<String, String>> workloads = new LinkedHashMap<>();
        for (String w : DashboardRunner.WORKLOADS) {
            Map<String, String> byWorkers = new LinkedHashMap<>();
            for (int n : new int[] {2, 4, 6, 8}) {
                byWorkers.put(Integer.toString(n), DashboardRunner.describe(w, n));
            }
            workloads.put(w, byWorkers);
        }
        o.put("workloads", workloads);
        o.put("cores", Runtime.getRuntime().availableProcessors());
        return o;
    }

    /** The Phase 4 summary as JSON rows, or an empty list if it has not been generated. */
    private static Map<String, Object> results() throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!Files.exists(SUMMARY)) {
            out.put("available", false);
            out.put("rows", List.of());
            return out;
        }
        List<String> lines = Files.readAllLines(SUMMARY);
        String[] header = lines.get(0).split(",", -1);
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] cells = line.split(",", -1);
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < header.length && i < cells.length; i++) {
                row.put(header[i], cells[i]);
            }
            rows.add(row);
        }
        out.put("available", true);
        out.put("rows", rows);
        return out;
    }

    // ---- plumbing -------------------------------------------------------------------------

    @FunctionalInterface
    private interface Handler {
        Object handle() throws Exception;
    }

    private static JsonNode body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readAllBytes();
            return bytes.length == 0 ? JSON.createObjectNode() : JSON.readTree(bytes);
        }
    }

    private static void json(HttpExchange ex, Handler handler) throws IOException {
        int status = 200;
        Object payload;
        try {
            payload = handler.handle();
        } catch (IllegalArgumentException e) {
            status = 400;
            payload = Map.of("error", e.getMessage());
        } catch (Exception e) {
            status = 500;
            payload = Map.of("error", String.valueOf(e));
        }
        send(ex, status, "application/json; charset=utf-8", JSON.writeValueAsBytes(payload));
    }

    /**
     * Serves the built web app (ui/ → npm run build → resources/ui/). Unknown paths fall back to
     * index.html; anything with ".." is rejected.
     */
    private static void page(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.contains("..")) {
            send(ex, 400, "text/plain; charset=utf-8", "bad path".getBytes(StandardCharsets.UTF_8));
            return;
        }
        String resource = path.equals("/") ? "/ui/index.html" : "/ui" + path;
        InputStream in = Dashboard.class.getResourceAsStream(resource);
        if (in == null && !path.startsWith("/assets/")) {
            resource = "/ui/index.html";
            in = Dashboard.class.getResourceAsStream(resource);
        }
        if (in == null) {
            String msg = resource.equals("/ui/index.html")
                    ? "The web UI is not built. Run: cd ui && npm install && npm run build, then rebuild the jar."
                    : "not found";
            send(ex, 404, "text/plain; charset=utf-8", msg.getBytes(StandardCharsets.UTF_8));
            return;
        }
        try (InputStream body = in) {
            send(ex, 200, contentType(resource), body.readAllBytes());
        }
    }

    private static String contentType(String resource) {
        String r = resource.toLowerCase(java.util.Locale.ROOT);
        if (r.endsWith(".html")) return "text/html; charset=utf-8";
        if (r.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (r.endsWith(".css")) return "text/css; charset=utf-8";
        if (r.endsWith(".svg")) return "image/svg+xml";
        if (r.endsWith(".png")) return "image/png";
        if (r.endsWith(".ico")) return "image/x-icon";
        if (r.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }
}
