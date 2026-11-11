package com.echomind.lock;

import java.util.function.Supplier;

/**
 * 按会话加锁：同一个会话的操作串行执行，不同会话互不影响。
 * 抽成接口是为了让测试可以换成"不加锁"的实现，对比加锁前后的行为。
 */
public interface ConversationLock {

    /**
     * 拿到锁后执行 action 并返回结果；在等待时间内拿不到锁则抛出 {@link ConversationBusyException}。
     * 同一线程可以重复进入（可重入）。
     */
    <T> T execute(String userId, String conversationId, Supplier<T> action);

    default void run(String userId, String conversationId, Runnable action) {
        execute(userId, conversationId, () -> {
            action.run();
            return null;
        });
    }
}
