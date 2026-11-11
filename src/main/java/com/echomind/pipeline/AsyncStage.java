package com.echomind.pipeline;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/** 把一个环节放到指定线程池异步执行，带超时和降级。 */
public final class AsyncStage {

    private AsyncStage() {
    }

    /**
     * 在 executor 上执行 task。超时或抛异常时，用 fallback 的结果代替，返回的 Future 不会以异常结束。
     *
     * <p>注意：超时只是让调用方不再等待，并不会中断正在执行的任务——那个线程会继续跑到下游调用返回为止。
     * 所以超时要配合线程池容量和下游客户端自身的超时一起使用。
     */
    public static <T> CompletableFuture<T> supply(Supplier<T> task,
                                                  Executor executor,
                                                  long timeoutMillis,
                                                  Function<Throwable, T> fallback) {
        return CompletableFuture.supplyAsync(task, executor)
                .orTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
                .exceptionally(error -> fallback.apply(unwrap(error)));
    }

    /** 任务里抛出的异常会被包成 CompletionException，这里取出真正的原因。 */
    public static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
