package com.vwww.mira;

import android.app.ActivityManager;
import android.content.Context;
import android.net.TrafficStats;
import android.os.Process;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

public final class MiraDeviceMetrics {
    private static long lastCpuTotal;
    private static long lastCpuIdle;
    private static long lastRxBytes = -1;
    private static long lastTxBytes = -1;
    private static long lastNetworkAt;

    private MiraDeviceMetrics() {
    }

    public static synchronized JSONObject snapshot(Context context) throws Exception {
        long now = System.currentTimeMillis();
        JSONObject metrics = new JSONObject();
        metrics.put("sampledAt", now);
        metrics.put("cpuPercent", cpuPercent());
        memory(metrics, context);
        network(metrics, now);
        return metrics;
    }

    private static double cpuPercent() {
        CpuSample sample = readCpuSample();
        if (sample == null) return -1d;
        double percent = -1d;
        if (lastCpuTotal > 0 && sample.total > lastCpuTotal) {
            long totalDelta = sample.total - lastCpuTotal;
            long idleDelta = sample.idle - lastCpuIdle;
            if (totalDelta > 0) percent = clamp(100d * (totalDelta - idleDelta) / totalDelta, 0d, 100d);
        }
        lastCpuTotal = sample.total;
        lastCpuIdle = sample.idle;
        return round1(percent);
    }

    private static CpuSample readCpuSample() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            String line = reader.readLine();
            if (line == null || !line.startsWith("cpu ")) return null;
            String[] parts = line.trim().split("\\s+");
            long user = parseLong(parts, 1);
            long nice = parseLong(parts, 2);
            long system = parseLong(parts, 3);
            long idle = parseLong(parts, 4);
            long iowait = parseLong(parts, 5);
            long irq = parseLong(parts, 6);
            long softirq = parseLong(parts, 7);
            long steal = parseLong(parts, 8);
            long total = user + nice + system + idle + iowait + irq + softirq + steal;
            return new CpuSample(total, idle + iowait);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void memory(JSONObject metrics, Context context) throws Exception {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) {
            metrics.put("memoryPercent", -1d);
            metrics.put("memoryUsedMb", -1d);
            metrics.put("memoryTotalMb", -1d);
            return;
        }
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        long total = info.totalMem;
        long used = Math.max(0L, total - info.availMem);
        metrics.put("memoryPercent", total > 0 ? round1(clamp(100d * used / total, 0d, 100d)) : -1d);
        metrics.put("memoryUsedMb", round1(used / 1024d / 1024d));
        metrics.put("memoryTotalMb", round1(total / 1024d / 1024d));
    }

    private static void network(JSONObject metrics, long now) throws Exception {
        long rx = TrafficStats.getUidRxBytes(Process.myUid());
        long tx = TrafficStats.getUidTxBytes(Process.myUid());
        if (rx == TrafficStats.UNSUPPORTED || tx == TrafficStats.UNSUPPORTED) {
            rx = TrafficStats.getTotalRxBytes();
            tx = TrafficStats.getTotalTxBytes();
        }
        double rxBps = -1d;
        double txBps = -1d;
        if (lastNetworkAt > 0 && rx >= 0 && tx >= 0 && lastRxBytes >= 0 && lastTxBytes >= 0 && now > lastNetworkAt) {
            double seconds = (now - lastNetworkAt) / 1000d;
            rxBps = Math.max(0d, (rx - lastRxBytes) / seconds);
            txBps = Math.max(0d, (tx - lastTxBytes) / seconds);
        }
        lastRxBytes = rx;
        lastTxBytes = tx;
        lastNetworkAt = now;
        metrics.put("rxBps", round1(rxBps));
        metrics.put("txBps", round1(txBps));
        metrics.put("networkBps", round1(Math.max(0d, rxBps) + Math.max(0d, txBps)));
    }

    private static long parseLong(String[] parts, int index) {
        if (index >= parts.length) return 0L;
        try {
            return Long.parseLong(parts[index]);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    private static double round1(double value) {
        if (value < 0d || Double.isNaN(value) || Double.isInfinite(value)) return -1d;
        return Math.round(value * 10d) / 10d;
    }

    private static final class CpuSample {
        final long total;
        final long idle;

        CpuSample(long total, long idle) {
            this.total = total;
            this.idle = idle;
        }
    }
}
