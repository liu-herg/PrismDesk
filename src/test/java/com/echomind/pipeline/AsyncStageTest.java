package com.echomind.pipeline;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncStageTest {

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);

    @AfterAll
    static void shutdown() {
        EXECUTOR.shutdownNow();
    }

    @Test
    void returnsTaskResultWhenFastEnough() {
        String value = AsyncStage.supply(() -> "ok", EXECUTOR, 1000, error -> "fallback").join();

        assertThat(value).isEqualTo("ok");
    }

    @Test
    void fallsBackOnTimeoutWithoutWaitingForTheSlowTask() {
        AtomicReference<Throwable> seen = new AtomicReference<>();
        long begin = System.nanoTime();

        String value = AsyncStage.supply(() -> {
            sleep(2000);
            return "too late";
        }, EXECUTOR, 100, error -> {
            seen.set(error);
            return "fallback";
        }).join();

        long elapsedMs = (System.nanoTime() - begin) / 1_000_000;
        assertThat(value).isEqualTo("fallback");
        assertThat(seen.get()).isInstanceOf(TimeoutException.class);
        // 调用方在超时后立即拿到降级结果，不会等慢任务跑完（2 秒）
        assertThat(elapsedMs).isLessThan(1000);
    }

    @Test
    void fallsBackOnExceptionAndExposesTheOriginalCause() {
        AtomicReference<Throwable> seen = new AtomicReference<>();

        String value = AsyncStage.<String>supply(() -> {
            throw new IllegalStateException("boom");
        }, EXECUTOR, 1000, error -> {
            seen.set(error);
            return "fallback";
        }).join();

        assertThat(value).isEqualTo("fallback");
        // 拿到的是原始异常，而不是包了一层的 CompletionException
        assertThat(seen.get()).isInstanceOf(IllegalStateException.class).hasMessage("boom");
    }

    @Test
    void stageTimerAccumulatesPerStageAndTracksDegradation() {
        StageTimer timer = new StageTimer();

        timer.record("a", () -> {
            sleep(30);
            return 1;
        });
        timer.record("a", () -> {
            sleep(30);
            return 2;
        });
        timer.record("b", () -> 3);
        timer.markDegraded("b");

        assertThat(timer.snapshot().keySet()).containsExactly("a", "b");
        assertThat(timer.snapshot().get("a")).isGreaterThanOrEqualTo(50);
        assertThat(timer.degradedStages()).containsExactly("b");
        assertThat(timer.totalMillis()).isGreaterThanOrEqualTo(50);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
