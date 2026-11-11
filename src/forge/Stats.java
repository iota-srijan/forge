package forge;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;

/**
 * Live counters shared by every thread, so they must be thread-safe.
 * AtomicInteger / LongAdder update without locks (the CAS idea from Lecture 54).
 * LongAdder is used for `total` because it is the hot counter hit by every request.
 */
public final class Stats {
    public volatile String engine = "";
    public final AtomicInteger active = new AtomicInteger();   // connections currently open
    public final LongAdder total = new LongAdder();            // requests answered
    public final LongAdder rejected = new LongAdder();         // connections turned away with 503
    public volatile IntSupplier queueDepth = () -> 0;          // pool engine plugs in its queue size

    public String json() {
        Runtime rt = Runtime.getRuntime();
        long heapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        // Platform (OS) threads only: virtual threads are deliberately NOT counted here,
        // which is exactly why the virtual engine shows a small number.
        int threads = ManagementFactory.getThreadMXBean().getThreadCount();
        double cpu = -1;
        long committedMb = -1;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            cpu = os.getProcessCpuLoad() * 100;          // % of the whole machine, -1 if not yet known
            // Memory the OS has actually committed to the process: heap AND thread stacks etc.
            // Heap alone hides the cost of thousands of OS threads, whose stacks live outside the heap.
            committedMb = os.getCommittedVirtualMemorySize() / (1024 * 1024);
        }
        return "{\"engine\":\"" + engine + "\""
                + ",\"active\":" + active.get()
                + ",\"queued\":" + queueDepth.getAsInt()
                + ",\"total\":" + total.sum()
                + ",\"rejected\":" + rejected.sum()
                + ",\"threads\":" + threads
                + ",\"heapMb\":" + heapMb
                + ",\"committedMb\":" + committedMb
                + ",\"cpuPct\":" + Math.round(cpu) + "}";
    }
}
