package com.echomind.pipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 记录一轮对话里各个环节的耗时。环节可能在不同线程上并行执行，所以内部是线程安全的。
 * 并行执行时，各环节耗时之和会大于总耗时，这正是并行省下来的时间。
 */
public class StageTimer {

    private final long startNanos = System.nanoTime();
    private final Map<String, Long> timings = Collections.synchronizedMap(new LinkedHashMap<>());
    private final List<String> degraded = Collections.synchronizedList(new ArrayList<>());

    public <T> T record(String stage, Supplier<T> task) {
        long begin = System.nanoTime();
        try {
            return task.get();
        } finally {
            timings.merge(stage, (System.nanoTime() - begin) / 1_000_000, Long::sum);
        }
    }

    /** 标记某个环节走了降级（超时或失败）。 */
    public void markDegraded(String stage) {
        degraded.add(stage);
    }

    public long totalMillis() {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 各环节耗时（毫秒）的快照，按首次记录的顺序排列。 */
    public Map<String, Long> snapshot() {
        synchronized (timings) {
            return new LinkedHashMap<>(timings);
        }
    }

    public List<String> degradedStages() {
        synchronized (degraded) {
            return List.copyOf(degraded);
        }
    }
}
