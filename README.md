# PrismDesk

智能客服后端。基于 EchoMind（Python 版）的 Java/Spring 重构版扩展而来，在原有的意图识别、多 Agent 路由、混合检索和分层记忆之上，补齐后端能力：MySQL 持久化、会话管理、工单、并发控制等。

原版说明见 [docs/EchoMind-Java原始说明.md](docs/EchoMind-Java原始说明.md)。

## 技术栈

Java 21 · Spring Boot 3.5 · Spring Security（JWT）· Spring AI 1.1 · MyBatis-Plus · MySQL 8.4 · Flyway · Redis · Redisson · SSE · Vue 3 · Micrometer/Prometheus · Docker Compose

## 存储分工

| 存储 | 放什么 |
|---|---|
| MySQL | 会话、消息、知识库切片、情景记忆、用户画像（权威数据） |
| Redis | 最近 N 条工作记忆、会话摘要（LLM 上下文，会过期） |

## 进度

- [x] P1-1 MySQL 数据层：见 [docs/P1-数据层.md](docs/P1-数据层.md)
- [x] P1-2 同一会话并发控制（Redisson 分布式锁）：见 [docs/P1-并发控制.md](docs/P1-并发控制.md)
- [x] P1-3 线程池、并行化、超时降级：见 [docs/P1-线程池与并行化.md](docs/P1-线程池与并行化.md)
- [x] P1-4 转人工工单（状态机 + 乐观锁抢单 + 幂等建单）：见 [docs/P1-工单.md](docs/P1-工单.md)

- [x] P2 登录与权限（JWT + 刷新令牌 + 吊销）、流式对话、实时工单通知：见 [docs/P2-登录与实时.md](docs/P2-登录与实时.md)
- [x] 客服台前端（客户端、坐席端、管理端）：见 [frontend/README.md](frontend/README.md)

## 运行

```powershell
docker compose up -d --build
```

打开 http://127.0.0.1:8080/ ，用演示账号登录（密码都是 `demo123`）：

| 账号 | 角色 | 看什么 |
|---|---|---|
| `u1001` | 客户 | 发消息，看各处理环节实时进度；说"转人工"会自动建单 |
| `agent-01` / `agent-02` | 坐席 | 实时收到新工单、抢单；查看客户对话和每条回复的处理过程 |
| `admin` | 管理员 | 知识库、运行状态、账号管理 |

演示建议：开三个浏览器窗口（或一个普通窗口加两个无痕窗口），分别登录 u1001、agent-01、agent-02。

注意：流式对话和实时通知是 SSE 长连接，请直接访问 8080；经过 8081 的 Nginx 需要关闭响应缓冲（`proxy_buffering off`）。

更多说明见 [docs/P1-数据层.md 的"本地怎么跑"](docs/P1-数据层.md#本地怎么跑)。
