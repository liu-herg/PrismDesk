package com.echomind.lock;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 基于 Redisson 的会话锁。
 *
 * <ul>
 *   <li>不指定锁的过期时间，由 Redisson 的看门狗自动续期（默认 30 秒，每 10 秒续一次）：
 *       一轮对话要调用 LLM，耗时不可预估，写死过期时间容易出现"业务没跑完锁先过期"。
 *       进程崩溃后看门狗停止续期，锁会自动过期，不会死锁。</li>
 *   <li>可重入：/chat 整个处理过程持有锁，内部写记忆时再次加锁不会把自己卡住。</li>
 *   <li>等待有上限：拿不到锁就返回"会话忙"，不让请求线程无限阻塞。</li>
 * </ul>
 */
@Component
public class RedissonConversationLock implements ConversationLock {

    private final RedissonClient redisson;
    private final long waitMillis;

    public RedissonConversationLock(RedissonClient redisson,
                                    @Value("${echomind.lock.wait-ms:15000}") long waitMillis) {
        this.redisson = redisson;
        this.waitMillis = waitMillis;
    }

    @Override
    public <T> T execute(String userId, String conversationId, Supplier<T> action) {
        RLock lock = redisson.getLock("lock:conv:" + userId + ":" + conversationId);
        boolean acquired;
        try {
            acquired = lock.tryLock(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ConversationBusyException();
        }
        if (!acquired) {
            throw new ConversationBusyException();
        }
        try {
            return action.get();
        } finally {
            // 只释放自己持有的锁，避免极端情况下（锁已过期被别人拿到）误删别人的锁
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
