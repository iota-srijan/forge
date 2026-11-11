package forge;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws benchmark charts as PNG files with plain Java2D (no chart library).
 * hero.png is 1200x627, the size LinkedIn uses for a shared image.
 */
final class Charts {

    private static final Color BG = new Color(0x0f1115);
    private static final Color PANEL = new Color(0x181b22);
    private static final Color GRID = new Color(0x2a2f3a);
    private static final Color TEXT = new Color(0xe8eaf0);
    private static final Color MUTED = new Color(0x8b93a7);
    private static final Map<String, Color> ENGINE_COLOR = Map.of(
            "single", new Color(0x9aa3b5),
            "thread", new Color(0xff9f5b),
            "pool", new Color(0xc084fc),
            "virtual", new Color(0x5b9dff),
            "nio1", new Color(0x2dd4bf),
            "nio", new Color(0x86efac));

    private Charts() {}

    static void writeAll(List<Bench.Row> rows, Path dir, int workMs, int cpuMs, int cores) throws IOException {
        List<Bench.Row> io = rows.stream().filter(r -> r.scenario().equals("io")).toList();
        List<Bench.Row> cpu = rows.stream().filter(r -> r.scenario().equals("cpu")).toList();

        if (!io.isEmpty()) {
            int[] levels = levels(io);
            int engineCount = (int) io.stream().map(Bench.Row::engine).distinct().count();
            save(dir.resolve("io-throughput.png"), 1000, 560, g -> lineChart(g, 0, 0, 1000, 560,
                    "Throughput vs users (each request waits " + workMs + " ms)", levels,
                    series(io, levels, r -> r.median().okPerSec()), "successful requests / sec", false));
            save(dir.resolve("io-p99.png"), 1000, 560, g -> lineChart(g, 0, 0, 1000, 560,
                    "p99 latency vs users (lower is better, log scale)", levels,
                    series(io, levels, r -> r.median().okPerSec() > 0 ? r.median().p99() : Double.NaN),
                    "p99 latency (ms)", true));
            int top = levels[levels.length - 1];
            save(dir.resolve("io-threads.png"), 1000, 560, g -> barChart(g, 0, 0, 1000, 560,
                    "OS threads in the server at " + top + " users", atLevelSampled(io, top, r -> r.median().peakThreads()),
                    "", null, null));
            save(dir.resolve("io-memory.png"), 1000, 560, g -> barChart(g, 0, 0, 1000, 560,
                    "Memory committed by the server process at " + top + " users (MB)",
                    atLevelSampled(io, top, r -> r.median().peakCommittedMb()), "", null, null));

            // The image meant for the LinkedIn post.
            save(dir.resolve("hero.png"), 1200, 627, g -> {
                g.setColor(TEXT);
                g.setFont(new Font("SansSerif", Font.BOLD, 30));
                g.drawString("One Java web server, " + engineCount + " threading models", 40, 52);
                g.setColor(MUTED);
                g.setFont(new Font("SansSerif", Font.PLAIN, 17));
                g.drawString("Same HTTP code, different concurrency. Every request waits " + workMs
                        + " ms, like a database call.", 40, 82);
                lineChart(g, 30, 100, 700, 505, "Successful requests / sec", levels,
                        series(io, levels, r -> r.median().okPerSec()), "", false);
                barChart(g, 745, 100, 425, 505, "OS threads at " + top + " users",
                        atLevelSampled(io, top, r -> r.median().peakThreads()), "", null, null);
            });
        }

        if (!cpu.isEmpty()) {
            int top = levels(cpu)[levels(cpu).length - 1];
            double ceiling = cores * 1000.0 / cpuMs;   // every core busy 100% of the time
            save(dir.resolve("cpu-throughput.png"), 1000, 560, g -> barChart(g, 0, 0, 1000, 560,
                    "CPU-bound (" + cpuMs + " ms of compute each), " + top + " users: requests / sec",
                    atLevel(cpu, top, r -> r.median().okPerSec()), "",
                    ceiling, String.format("ideal ceiling: %d hardware threads x %d req/s = %.0f", cores, 1000 / cpuMs, ceiling)));
        }
    }

    // ------------------------------------------------------------------ data shaping

    private static int[] levels(List<Bench.Row> rows) {
        return rows.stream().mapToInt(Bench.Row::users).distinct().sorted().toArray();
    }

    private static Map<String, double[]> series(List<Bench.Row> rows, int[] levels,
                                                java.util.function.ToDoubleFunction<Bench.Row> f) {
        Map<String, double[]> m = new LinkedHashMap<>();
        for (Bench.Row r : rows) {
            double[] arr = m.computeIfAbsent(r.engine(), k -> {
                double[] a = new double[levels.length];
                java.util.Arrays.fill(a, Double.NaN);
                return a;
            });
            for (int i = 0; i < levels.length; i++) if (levels[i] == r.users()) arr[i] = f.applyAsDouble(r);
        }
        return m;
    }

    /** Like atLevel, but a value of 0 or less means "the measuring probe got no sample": show n/a, not a fake 0. */
    private static Map<String, Double> atLevelSampled(List<Bench.Row> rows, int users,
                                                      java.util.function.ToDoubleFunction<Bench.Row> f) {
        Map<String, Double> m = atLevel(rows, users, f);
        m.replaceAll((k, v) -> v <= 0 ? Double.NaN : v);
        return m;
    }

    private static Map<String, Double> atLevel(List<Bench.Row> rows, int users,
                                               java.util.function.ToDoubleFunction<Bench.Row> f) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (Bench.Row r : rows) if (r.users() == users) m.put(r.engine(), f.applyAsDouble(r));
        return m;
    }

    // ------------------------------------------------------------------ drawing

    private interface Painter { void paint(Graphics2D g); }

    private static void save(Path file, int w, int h, Painter p) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, w, h);
        p.paint(g);
        g.dispose();
        ImageIO.write(img, "png", file.toFile());
    }

    private static void panel(Graphics2D g, int x, int y, int w, int h, String title) {
        g.setColor(PANEL);
        g.fillRoundRect(x + 10, y + 10, w - 20, h - 20, 18, 18);
        g.setColor(TEXT);
        g.setFont(new Font("SansSerif", Font.BOLD, 18));
        g.drawString(title, x + 30, y + 42);
    }

    static void lineChart(Graphics2D g, int x, int y, int w, int h, String title, int[] xs,
                          Map<String, double[]> series, String yLabel, boolean logY) {
        panel(g, x, y, w, h, title);
        int left = x + 90, right = x + w - 40, top = y + 95, bottom = y + h - 70;

        double max = 1, min = Double.MAX_VALUE;
        for (double[] s : series.values()) for (double v : s) if (!Double.isNaN(v) && v > 0) {
            max = Math.max(max, v);
            min = Math.min(min, v);
        }
        if (min == Double.MAX_VALUE) min = 1;
        double lo = logY ? Math.pow(10, Math.floor(Math.log10(min))) : 0;
        double hi = logY ? Math.pow(10, Math.ceil(Math.log10(max))) : niceCeil(max);

        java.util.function.DoubleUnaryOperator yOf = v -> logY
                ? bottom - (Math.log10(v) - Math.log10(lo)) / (Math.log10(hi) - Math.log10(lo)) * (bottom - top)
                : bottom - v / hi * (bottom - top);

        // grid + y labels
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        List<Double> ticks = new java.util.ArrayList<>();
        if (logY) for (double t = lo; t <= hi * 1.0001; t *= 10) ticks.add(t);
        else for (int i = 0; i <= 4; i++) ticks.add(hi * i / 4);
        for (double t : ticks) {
            int ty = (int) yOf.applyAsDouble(t);
            g.setColor(GRID);
            g.drawLine(left, ty, right, ty);
            g.setColor(MUTED);
            String s = fmt(t);
            g.drawString(s, left - 12 - g.getFontMetrics().stringWidth(s), ty + 5);
        }
        // x labels: categorical, evenly spaced
        for (int i = 0; i < xs.length; i++) {
            int tx = xFor(i, xs.length, left, right);
            String s = xs[i] + "";
            g.setColor(MUTED);
            g.drawString(s, tx - g.getFontMetrics().stringWidth(s) / 2, bottom + 22);
        }
        g.drawString("concurrent users", (left + right) / 2 - 50, bottom + 45);
        if (!yLabel.isEmpty()) g.drawString(yLabel, x + 30, y + 70);

        // lines
        int legendX = left + 10, legendY = top + 4;
        for (var e : series.entrySet()) {
            Color c = ENGINE_COLOR.getOrDefault(e.getKey(), TEXT);
            g.setColor(c);
            g.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D path = new Path2D.Double();
            boolean started = false;
            double[] v = e.getValue();
            for (int i = 0; i < v.length; i++) {
                if (Double.isNaN(v[i]) || (logY && v[i] <= 0)) { started = false; continue; }
                double px = xFor(i, v.length, left, right);
                double py = yOf.applyAsDouble(Math.max(v[i], logY ? lo : 0));
                if (!started) { path.moveTo(px, py); started = true; } else path.lineTo(px, py);
                g.fillOval((int) px - 5, (int) py - 5, 10, 10);
            }
            g.draw(path);
            g.setStroke(new BasicStroke(1f));
            // legend
            g.fillRoundRect(legendX, legendY, 14, 14, 4, 4);
            g.setColor(TEXT);
            g.drawString(e.getKey(), legendX + 20, legendY + 12);
            legendX += 30 + g.getFontMetrics().stringWidth(e.getKey()) + 10;
        }
    }

    static void barChart(Graphics2D g, int x, int y, int w, int h, String title, Map<String, Double> values,
                         String unit, Double refLine, String refLabel) {
        panel(g, x, y, w, h, title);
        int left = x + 40, right = x + w - 40, top = y + 90, bottom = y + h - 60;
        double max = values.values().stream().filter(v -> !Double.isNaN(v)).mapToDouble(Double::doubleValue).max().orElse(1);
        if (refLine != null) max = Math.max(max, refLine);
        double hi = niceCeil(max * 1.08);

        int n = values.size();
        int slot = (right - left) / Math.max(1, n);
        int bw = (int) (slot * 0.62);
        int i = 0;
        g.setFont(new Font("SansSerif", Font.BOLD, 15));
        for (var e : values.entrySet()) {
            int bx = left + i * slot + (slot - bw) / 2;
            boolean missing = Double.isNaN(e.getValue());
            int bh = missing ? 0 : (int) (e.getValue() / hi * (bottom - top));
            g.setColor(ENGINE_COLOR.getOrDefault(e.getKey(), TEXT));
            g.fillRoundRect(bx, bottom - bh, bw, Math.max(bh, 2), 8, 8);
            g.setColor(TEXT);
            String label = missing ? "n/a" : fmt(e.getValue()) + unit;
            FontMetrics fm = g.getFontMetrics();
            g.drawString(label, bx + bw / 2 - fm.stringWidth(label) / 2, bottom - bh - 8);
            g.setColor(MUTED);
            g.setFont(new Font("SansSerif", Font.PLAIN, 14));
            fm = g.getFontMetrics();
            g.drawString(e.getKey(), bx + bw / 2 - fm.stringWidth(e.getKey()) / 2, bottom + 22);
            g.setFont(new Font("SansSerif", Font.BOLD, 15));
            i++;
        }
        if (refLine != null) {
            int ry = (int) (bottom - refLine / hi * (bottom - top));
            g.setColor(new Color(0xfbbf24));
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{8f, 6f}, 0f));
            g.drawLine(left, ry, right, ry);
            g.setStroke(new BasicStroke(1f));
            g.setFont(new Font("SansSerif", Font.PLAIN, 13));
            g.drawString(refLabel, left + 4, ry - 6);
        }
        g.setColor(GRID);
        g.drawLine(left, bottom, right, bottom);
    }

    private static int xFor(int i, int n, int left, int right) {
        return n == 1 ? (left + right) / 2 : left + 20 + (right - left - 40) * i / (n - 1);
    }

    private static double niceCeil(double v) {
        if (v <= 0) return 1;
        double mag = Math.pow(10, Math.floor(Math.log10(v)));
        for (double step : new double[]{1, 2, 2.5, 5, 10}) if (step * mag >= v) return step * mag;
        return 10 * mag;
    }

    private static String fmt(double v) {
        if (v >= 10_000) return String.format("%.1fk", v / 1000).replace(".0k", "k");
        if (v >= 100) return String.format("%.0f", v);
        if (v >= 1) return String.format("%.0f", v);
        return String.format("%.1f", v);
    }
}
