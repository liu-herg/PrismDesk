# PrismDesk

智能客服后端。基于 EchoMind（Python 版）的 Java/Spring 重构版扩展而来，在原有的意图识别、多 Agent 路由、混合检索和分层记忆之上，补齐后端能力：MySQL 持久化、会话管理、工单、并发控制等。

原版说明见 [docs/EchoMind-Java原始说明.md](docs/EchoMind-Java原始说明.md)。

## 技术栈

Java 21 · Spring Boot 3.5 · Spring AI 1.1 · MyBatis-Plus · MySQL 8.4 · Flyway · Redis · Redisson · Micrometer/Prometheus · Docker Compose

## 存储分工

| 存储 | 放什么 |
|---|---|
| MySQL | 会话、消息、知识库切片、情景记忆、用户画像（权威数据） |
| Redis | 最近 N 条工作记忆、会话摘要（LLM 上下文，会过期） |

## 进度

- [x] P1-1 MySQL 数据层：见 [docs/P1-数据层.md](docs/P1-数据层.md)
- [x] P1-2 同一会话并发控制（Redisson 分布式锁）：见 [docs/P1-并发控制.md](docs/P1-并发控制.md)
- [x] P1-3 线程池、并行化、超时降级：见 [docs/P1-线程池与并行化.md](docs/P1-线程池与并行化.md)
- [ ] P1-4 工单系统（状态机 + 乐观锁）

## 运行

见 [docs/P1-数据层.md 的"本地怎么跑"](docs/P1-数据层.md#本地怎么跑)。
