package com.echomind.pipeline;

/**
 * 环节进度回调，流式对话用它把"正在识别意图 / 检索完成"实时推给客户。
 * 回调可能来自不同线程（意图和检索并行），实现方需要线程安全。
 */
public interface StageListener {

    StageListener NONE = new StageListener() {
    };

    default void started(String stage) {
    }

    default void finished(String stage, long millis) {
    }

    default void degraded(String stage) {
    }
}
