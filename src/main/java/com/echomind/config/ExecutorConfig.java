package com.echomind.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 两个线程池，按"任务层级"分开：
 *
 * <ul>
 *   <li>stageExecutor：一轮对话里的顶层环节（意图识别、知识库检索）。</li>
 *   <li>workerExecutor：叶子任务（子查询检索、多 Agent 执行、回答校验、画像更新），
 *       这些任务内部不会再向线程池提交任务。</li>
 * </ul>
 *
 * <p>为什么要分开：知识库检索这个顶层环节内部会并行执行多个子查询并等待结果。
 * 如果父任务和子任务共用一个池，池满时父任务占着线程等子任务，子任务却在队列里等线程，
 * 就会互相等待（线程饥饿死锁）。分成两个池后，父任务等待的子任务永远在另一个池里执行。
 *
 * <p>为什么不用默认的 ForkJoinPool.commonPool()：它的线程数是 CPU 核数 - 1，为计算型任务设计；
 * 这里的任务是调用 LLM 的阻塞 IO，几个慢请求就能把它占满，并且会影响到进程里所有用到公共池的代码。
 *
 * <p>参数怎么定：任务几乎全是等待网络返回，线程数不按 CPU 核数算，而是按"要支持的并发对话数"估：
 * 每轮对话在 stage 池最多占 2 个线程，core=16 约支持 8 轮同时处理，突发时扩到 max。
 * 队列故意设得不大——ThreadPoolExecutor 只有队列满了才会创建超过 core 的线程，
 * 队列太大等于 max 永远不生效，任务只会排队变慢。
 * 拒绝策略用 CallerRunsPolicy：池和队列都满时由提交任务的线程自己执行，
 * 相当于自动退化成串行，请求变慢但不丢任务，同时对上游形成反压。
 */
@Configuration
public class ExecutorConfig {

    @Bean("stageExecutor")
    public ThreadPoolTaskExecutor stageExecutor(
            @Value("${echomind.executor.stage.core:16}") int core,
            @Value("${echomind.executor.stage.max:32}") int max,
            @Value("${echomind.executor.stage.queue:32}") int queue) {
        return build("stage-", core, max, queue);
    }

    @Bean("workerExecutor")
    public ThreadPoolTaskExecutor workerExecutor(
            @Value("${echomind.executor.worker.core:16}") int core,
            @Value("${echomind.executor.worker.max:48}") int max,
            @Value("${echomind.executor.worker.queue:64}") int queue) {
        return build("worker-", core, max, queue);
    }

    private ThreadPoolTaskExecutor build(String prefix, int core, int max, int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setKeepAliveSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 停机时先把已提交的任务执行完，最多等 10 秒
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
