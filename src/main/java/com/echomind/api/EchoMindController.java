package com.echomind.api;

import com.echomind.agent.AgentOrchestrator;
import com.echomind.auth.CurrentUser;
import com.echomind.notify.TicketNotice;
import com.echomind.notify.TicketNotifier;
import com.echomind.pipeline.StageListener;
import com.echomind.agent.AgentRequest;
import com.echomind.agent.AgentType;
import com.echomind.agent.AnswerVerifier;
import com.echomind.agent.OrchestratorResult;
import com.echomind.conversation.ChatTurn;
import com.echomind.conversation.ConversationService;
import com.echomind.evaluation.EndToEndEvaluator;
import com.echomind.intent.IntentCategory;
import com.echomind.intent.IntentRecognizer;
import com.echomind.intent.IntentResult;
import com.echomind.knowledge.KnowledgeBaseService;
import com.echomind.lock.ConversationLock;
import com.echomind.intent.UrgencyLevel;
import com.echomind.ticket.Escalation;
import com.echomind.ticket.TicketService;
import com.echomind.knowledge.SearchResult;
import com.echomind.memory.MemoryContext;
import com.echomind.memory.MemoryManager;
import com.echomind.memory.MessageRole;
import com.echomind.monitor.PerformanceMonitor;
import com.echomind.config.EchoMindProperties;
import com.echomind.pipeline.AsyncStage;
import com.echomind.pipeline.StageTimer;
import com.echomind.skill.SkillManager;
import com.echomind.tool.KnowledgeToolManager;
import com.echomind.tool.ToolResult;
import com.echomind.trace.ToolCallTrace;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.micrometer.core.instrument.Timer;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

@RestController
@Tag(name = "EchoMind API", description = "智能客服对话、知识库、监控和评测接口")
public class EchoMindController {

    private static final Logger log = LoggerFactory.getLogger(EchoMindController.class);

    private final AgentOrchestrator orchestrator;
    private final IntentRecognizer intentRecognizer;
    private final MemoryManager memoryManager;
    private final KnowledgeToolManager knowledgeToolManager;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AnswerVerifier answerVerifier;
    private final PerformanceMonitor performanceMonitor;
    private final EndToEndEvaluator evaluator;
    private final SkillManager skillManager;
    private final ObjectMapper objectMapper;
    private final PrometheusMeterRegistry prometheusMeterRegistry;
    private final ConversationService conversationService;
    private final ConversationLock conversationLock;
    private final EchoMindProperties properties;
    private final TicketService ticketService;
    private final Executor stageExecutor;
    private final Executor workerExecutor;
    private final Executor streamExecutor;
    private final TicketNotifier ticketNotifier;

    public EchoMindController(
            AgentOrchestrator orchestrator,
            IntentRecognizer intentRecognizer,
            MemoryManager memoryManager,
            KnowledgeToolManager knowledgeToolManager,
            KnowledgeBaseService knowledgeBaseService,
            AnswerVerifier answerVerifier,
            PerformanceMonitor performanceMonitor,
            EndToEndEvaluator evaluator,
            SkillManager skillManager,
            ObjectMapper objectMapper,
            PrometheusMeterRegistry prometheusMeterRegistry,
            ConversationService conversationService,
            ConversationLock conversationLock,
            EchoMindProperties properties,
            TicketService ticketService,
            @Qualifier("stageExecutor") Executor stageExecutor,
            @Qualifier("workerExecutor") Executor workerExecutor,
            @Qualifier("streamExecutor") Executor streamExecutor,
            TicketNotifier ticketNotifier
    ) {
        this.orchestrator = orchestrator;
        this.intentRecognizer = intentRecognizer;
        this.memoryManager = memoryManager;
        this.knowledgeToolManager = knowledgeToolManager;
        this.knowledgeBaseService = knowledgeBaseService;
        this.answerVerifier = answerVerifier;
        this.performanceMonitor = performanceMonitor;
        this.evaluator = evaluator;
        this.skillManager = skillManager;
        this.objectMapper = objectMapper;
        this.prometheusMeterRegistry = prometheusMeterRegistry;
        this.conversationService = conversationService;
        this.conversationLock = conversationLock;
        this.properties = properties;
        this.ticketService = ticketService;
        this.stageExecutor = stageExecutor;
        this.workerExecutor = workerExecutor;
        this.streamExecutor = streamExecutor;
        this.ticketNotifier = ticketNotifier;
    }

    @GetMapping("/health")
    @Operation(summary = "健康检查", description = "返回服务状态和 Agent 路由统计。")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "agents", orchestrator.stats());
    }

    @PostMapping("/chat")
    @Operation(summary = "智能客服对话", description = "执行完整对话链路：记忆读取、知识库检索、意图识别、多 Agent 路由、回答校验和记忆写入。")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request, @AuthenticationPrincipal Jwt jwt) {
        // 用户身份取自登录令牌；请求体里的 user_id（旧接口遗留）会被忽略，不能冒充别人
        String userId = CurrentUser.of(jwt).username();
        String conversationId = conversationIdOf(request);
        conversationService.assertWritable(userId, conversationId);
        // 同一会话的消息排队处理：读记忆 → 生成回复 → 写记忆 这一整轮期间持有会话锁，
        // 避免两条并发消息各自读到旧上下文、回复互相看不到对方。等待超时返回 429。
        return conversationLock.execute(userId, conversationId,
                () -> handleChat(request, userId, conversationId, new StageTimer()));
    }

    /**
     * 流式对话（SSE）。和 /chat 走同一条处理链路，区别是每个环节开始、结束时都会推一条事件，
     * 客户能实时看到"正在识别意图 / 正在检索知识库 / 正在生成回答"。
     *
     * <p>回答本身仍然在校验通过之后整段推送，而不是逐字推送：逐字推送意味着客户会先看到还没校验过的内容，
     * 校验不通过时已经撤不回来了。客服场景里"说出去的话"要先过校验。
     *
     * <p>事件：stage（环节开始 / 结束）、degraded（环节降级）、reply（最终回复，内容同 /chat）、error。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式对话（SSE）", description = "实时推送各处理环节的进度，最后推送完整回复。")
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request, @AuthenticationPrincipal Jwt jwt) {
        String userId = CurrentUser.of(jwt).username();
        String conversationId = conversationIdOf(request);
        conversationService.assertWritable(userId, conversationId);
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(3).toMillis());
        StageListener listener = new StageListener() {
            @Override
            public void started(String stage) {
                sendEvent(emitter, "stage", Map.of("stage", stage, "state", "started"));
            }

            @Override
            public void finished(String stage, long millis) {
                sendEvent(emitter, "stage", Map.of("stage", stage, "state", "finished", "ms", millis));
            }

            @Override
            public void degraded(String stage) {
                sendEvent(emitter, "degraded", Map.of("stage", stage));
            }
        };
        try {
            // 整轮处理放到专门的线程池里跑，请求线程马上返回，Tomcat 线程不被长时间占住
            streamExecutor.execute(() -> {
                try {
                    sendEvent(emitter, "accepted", Map.of("conv_id", conversationId));
                    ChatResponse response = conversationLock.execute(userId, conversationId,
                            () -> handleChat(request, userId, conversationId, new StageTimer(listener)));
                    sendEvent(emitter, "reply", response);
                    emitter.complete();
                } catch (ResponseStatusException e) {
                    sendEvent(emitter, "error", Map.of("status", e.getStatusCode().value(), "message", String.valueOf(e.getReason())));
                    emitter.complete();
                } catch (Exception e) {
                    log.error("stream chat failed conv={}", conversationId, e);
                    sendEvent(emitter, "error", Map.of("status", 500, "message", "处理失败，请稍后再试"));
                    emitter.complete();
                }
            });
        } catch (RejectedExecutionException e) {
            sendEvent(emitter, "error", Map.of("status", 503, "message", "当前咨询人数较多，请稍后再试"));
            emitter.complete();
        }
        return emitter;
    }

    private static String conversationIdOf(ChatRequest request) {
        return request.conversationId() == null || request.conversationId().isBlank()
                ? UUID.randomUUID().toString()
                : request.conversationId();
    }

    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            // 客户关掉了页面：处理照常完成并落库，只是推不出去了
            log.debug("SSE send failed: {}", e.getMessage());
        }
    }

    private ChatResponse handleChat(ChatRequest request, String userId, String conversationId, StageTimer timer) {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        String message = request.message();
        EchoMindProperties.Pipeline pipeline = properties.getPipeline();

        MemoryContext memoryContext = timer.record("memory", () -> memoryManager.getContext(userId, conversationId, message));
        String memoryText = memoryContext.toPromptText(objectMapper);
        List<Map<String, String>> history = memoryContext.recentMessages().stream()
                .skip(Math.max(0, memoryContext.recentMessages().size() - 5))
                .map(m -> Map.of("role", m.role().name().toLowerCase(), "content", m.content()))
                .toList();

        IntentResult intentResult;
        ToolResult<List<SearchResult>> knowledge;
        boolean useKnowledge;
        if (pipeline.isParallel()) {
            // 意图识别和知识库检索互不依赖，并行执行。检索是"投机"的：
            // 先用不调 LLM 的规则预判，只有明显不需要知识库（如打招呼）时才不提前检索，避免白白多调一次 LLM。
            IntentResult guess = intentRecognizer.recognizeOffline(message);
            boolean speculate = shouldUseKnowledge(guess.intent()) || guess.confidence() < 0.8;
            CompletableFuture<IntentResult> intentFuture = AsyncStage.supply(
                    () -> timer.record("intent", () -> intentRecognizer.recognize(message, history)),
                    stageExecutor,
                    pipeline.getIntentTimeoutMs(),
                    error -> {
                        // 超时或失败：降级为规则预判的结果，保证还能路由到正确的 Agent
                        log.warn("Intent stage degraded request={}: {}", requestId, error.toString());
                        timer.markDegraded("intent");
                        return guess;
                    });
            CompletableFuture<ToolResult<List<SearchResult>>> knowledgeFuture = speculate
                    ? knowledgeAsync(message, requestId, timer, pipeline.getKnowledgeTimeoutMs())
                    : null;
            intentResult = intentFuture.join();
            useKnowledge = shouldUseKnowledge(intentResult.intent());
            if (!useKnowledge) {
                knowledge = emptyKnowledge();
            } else if (knowledgeFuture != null) {
                knowledge = knowledgeFuture.join();
            } else {
                // 预判说不需要、但 LLM 判定需要：补一次检索
                knowledge = knowledgeAsync(message, requestId, timer, pipeline.getKnowledgeTimeoutMs()).join();
            }
        } else {
            intentResult = timer.record("intent", () -> intentRecognizer.recognize(message, history));
            useKnowledge = shouldUseKnowledge(intentResult.intent());
            knowledge = useKnowledge
                    ? timer.record("knowledge", () -> knowledgeToolManager.searchWithRewrite(message, 3))
                    : emptyKnowledge();
        }

        List<ToolCallTrace> toolTraces = useKnowledge
                ? List.of(new ToolCallTrace(
                        knowledge.toolName() == null || knowledge.toolName().isBlank() ? "knowledge_search" : knowledge.toolName(),
                        knowledge.success(),
                        !knowledge.success() || knowledge.error() != null,
                        knowledge.cached(),
                        knowledge.reranked(),
                        knowledge.latencyMs(),
                        knowledge.error() == null ? "" : knowledge.error()))
                : List.of();
        String knowledgeText = buildKnowledgeContext(knowledge.data());
        String fullContext = join(memoryText, knowledgeText);
        AgentRequest agentRequest = AgentRequest.of(message, userId, conversationId, fullContext, history, intentResult, requestId);
        OrchestratorResult result = timer.record("generate", () -> orchestrator.run(agentRequest, toolTraces));

        // 回答校验是辅助环节：超时就按"通过"处理，不让它拖住已经生成好的回答
        AnswerVerifier.VerificationResult verification = AsyncStage.supply(
                () -> timer.record("verify", () -> answerVerifier.verify(message, result.response(), fullContext)),
                workerExecutor,
                pipeline.getVerifyTimeoutMs(),
                error -> {
                    log.warn("Verify stage degraded request={}: {}", requestId, error.toString());
                    timer.markDegraded("verify");
                    return new AnswerVerifier.VerificationResult(true, !fullContext.isBlank(), false, "verifier timeout");
                }).join();
        boolean escalated = result.escalated() || verification.needEscalation();
        timer.record("persist", () -> {
            memoryManager.addMessage(userId, conversationId, MessageRole.USER, message);
            memoryManager.addMessage(userId, conversationId, MessageRole.ASSISTANT, result.response());
            return null;
        });
        memoryManager.updateProfile(userId, conversationId);
        timer.record("persist", () -> {
            persistTurn(new ChatTurn(
                conversationId,
                userId,
                result.requestId(),
                request.message(),
                result.response(),
                result.intent() == null ? "other" : result.intent().name().toLowerCase(Locale.ROOT),
                result.agentType().name().toLowerCase(Locale.ROOT),
                escalated,
                result.latencyMs()
            ));
            return null;
        });
        Long ticketId = escalated
                ? timer.record("ticket", () -> openTicket(conversationId, userId, requestId, message, result, intentResult, verification))
                : null;
        long totalMs = timer.totalMillis();
        Map<String, Long> stageTimings = timer.snapshot();
        recordStageMetrics(stageTimings, totalMs);
        log.info("chat request={} parallel={} total={}ms stages={} degraded={}",
                requestId, pipeline.isParallel(), totalMs, stageTimings, timer.degradedStages());
        ChatResponse response = new ChatResponse(
                conversationId,
                result.requestId(),
                result.response(),
                result.intent() == null ? "other" : result.intent().name().toLowerCase(),
                intentResult.intentGroup(),
                result.agentType().name().toLowerCase(),
                agentNames(result.agentTypes()),
                result.primaryAgent() == null ? null : result.primaryAgent().name().toLowerCase(Locale.ROOT),
                agentNames(result.supportingAgents()),
                result.routingReason(),
                result.routingConfidence(),
                escalated,
                result.latencyMs(),
                knowledge.success() && knowledge.data() != null && !knowledge.data().isEmpty(),
                verification.pass(),
                verification.grounded(),
                intentResult.entities(),
                round(intentResult.confidence(), 4),
                intentResult.sourceScores(),
                totalMs,
                stageTimings,
                timer.degradedStages(),
                ticketId
        );
        saveTrace(response);
        return response;
    }

    /**
     * 把这条回复的处理轨迹（意图、路由、各环节耗时、降级、校验）存到消息上，
     * 坐席查看历史对话时能看到每条回复"是怎么来的"。尽力而为，失败不影响回复。
     */
    private void saveTrace(ChatResponse r) {
        try {
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("intent", r.intent());
            trace.put("intent_confidence", r.intentConfidence());
            trace.put("agent_type", r.agentType());
            trace.put("primary_agent", r.primaryAgent());
            trace.put("supporting_agents", r.supportingAgents());
            trace.put("routing_reason", r.routingReason());
            trace.put("knowledge_used", r.knowledgeUsed());
            trace.put("verified", r.verified());
            trace.put("grounded", r.grounded());
            trace.put("escalated", r.escalated());
            trace.put("ticket_id", r.ticketId());
            trace.put("total_ms", r.totalMs());
            trace.put("stage_timings_ms", r.stageTimingsMs());
            trace.put("degraded_stages", r.degradedStages());
            trace.put("request_id", r.requestId());
            conversationService.saveTrace(r.requestId(), objectMapper.writeValueAsString(trace));
        } catch (Exception ex) {
            log.warn("Failed to save trace request={}: {}", r.requestId(), ex.getMessage());
        }
    }

    /**
     * 转人工时建单。和对话落库一样，建单失败不影响本次回复，只记错误日志。
     * 同一会话已有未结工单时返回那一张（幂等）。
     */
    private Long openTicket(String conversationId, String userId, String requestId, String message,
                            OrchestratorResult result, IntentResult intentResult,
                            AnswerVerifier.VerificationResult verification) {
        try {
            List<String> reasons = new ArrayList<>();
            IntentCategory intent = intentResult.intent();
            if (intent == IntentCategory.HUMAN_HANDOFF || intent == IntentCategory.ESCALATION) {
                reasons.add("用户要求人工");
            }
            if (intentResult.urgency() == UrgencyLevel.CRITICAL) {
                reasons.add("紧急程度 CRITICAL");
            }
            if (verification.needEscalation()) {
                reasons.add("回答校验建议转人工：" + verification.reason());
            }
            if (reasons.isEmpty()) {
                reasons.add("Agent 回答建议转人工");
            }
            String summary = "用户：" + message + "\nAI：" + result.response();
            TicketService.EscalationResult opened = ticketService.openForEscalation(new Escalation(
                    conversationId,
                    userId,
                    intentResult.urgency() == null ? UrgencyLevel.MEDIUM.name() : intentResult.urgency().name(),
                    intent == null ? null : intent.name().toLowerCase(Locale.ROOT),
                    String.join("；", reasons),
                    summary,
                    requestId
            ));
            if (opened.created()) {
                log.info("Ticket {} opened for conv={} request={} reason={}", opened.ticket().getId(), conversationId, requestId, reasons);
                // openForEscalation 是 @Transactional 方法，返回时已提交；通知在线坐席有新工单
                ticketNotifier.publish(TicketNotice.created(opened.ticket()));
            }
            return opened.ticket().getId();
        } catch (Exception ex) {
            log.error("Failed to open ticket conv={} request={}: {}", conversationId, requestId, ex.getMessage());
            return null;
        }
    }

    /** 知识库检索：超时或失败时降级为不改写、不重排的直接检索（纯内存计算，毫秒级）。 */
    private CompletableFuture<ToolResult<List<SearchResult>>> knowledgeAsync(String message, String requestId, StageTimer timer, long timeoutMs) {
        return AsyncStage.supply(
                () -> timer.record("knowledge", () -> knowledgeToolManager.searchWithRewrite(message, 3)),
                stageExecutor,
                timeoutMs,
                error -> {
                    log.warn("Knowledge stage degraded request={}: {}", requestId, error.toString());
                    timer.markDegraded("knowledge");
                    return knowledgeToolManager.search(message, 3);
                });
    }

    private ToolResult<List<SearchResult>> emptyKnowledge() {
        return new ToolResult<>(true, List.of(), "knowledge_search", null, false, 0, false);
    }

    /** 各环节耗时写入 Micrometer，/metrics 里可以按 stage 看 P95。 */
    private void recordStageMetrics(Map<String, Long> stageTimings, long totalMs) {
        try {
            stageTimings.forEach((stage, millis) -> Timer.builder("prismdesk.chat.stage")
                    .tag("stage", stage)
                    .publishPercentileHistogram()
                    .register(prometheusMeterRegistry)
                    .record(Duration.ofMillis(millis)));
            Timer.builder("prismdesk.chat.total")
                    .tag("parallel", String.valueOf(properties.getPipeline().isParallel()))
                    .publishPercentileHistogram()
                    .register(prometheusMeterRegistry)
                    .record(Duration.ofMillis(totalMs));
        } catch (Exception ex) {
            log.debug("Failed to record stage metrics: {}", ex.getMessage());
        }
    }

    @PostMapping("/search")
    @Operation(summary = "知识库检索", description = "对用户查询做查询改写、并行召回和 LLM rerank。")
    public Map<String, Object> search(
            @Parameter(description = "检索关键词或用户问题", example = "退款多久能到账") @RequestParam String query,
            @Parameter(description = "返回结果数量", example = "5") @RequestParam(defaultValue = "5") int topK) {
        ToolResult<List<SearchResult>> result = knowledgeToolManager.searchWithRewrite(query, topK);
        return Map.of("query", query, "results", result.data(), "reranked", result.reranked());
    }

    @PostMapping("/knowledge/add")
    @Operation(summary = "批量添加知识文档", description = "将文档切片后写入 Java 版持久化知识库。")
    public Map<String, Object> addKnowledge(@Valid @RequestBody BatchDocInput input) {
        List<Map<String, String>> docs = input.documents().stream()
                .map(d -> Map.of("title", d.title(), "content", d.content()))
                .toList();
        int added = knowledgeBaseService.addDocuments(docs);
        return Map.of("added_chunks", added, "total_chunks", knowledgeBaseService.docCount());
    }

    @PostMapping(value = "/knowledge/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传知识库文件", description = "支持上传 .txt、.md、.json 文件，最大 10MB。JSON 文件格式为数组：[{\"title\":\"...\",\"content\":\"...\"}]。")
    public Map<String, Object> uploadKnowledge(@Parameter(description = "知识库文件") @RequestParam("file") MultipartFile file) throws Exception {
        if (file.getSize() > 10L * 1024 * 1024) {
            throw new IllegalArgumentException("文件大小超过 10MB 限制");
        }
        String filename = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        String text = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        List<Map<String, String>> docs;
        if (filename.endsWith(".json")) {
            docs = parseJsonDocs(text);
        } else {
            String title = filename.contains(".") ? filename.substring(0, filename.lastIndexOf('.')) : filename;
            docs = List.of(Map.of("title", title, "content", text));
        }
        int added = knowledgeBaseService.addDocuments(docs);
        return Map.of("message", "文件 " + filename + " 导入成功", "added_chunks", added, "total_chunks", knowledgeBaseService.docCount());
    }

    @GetMapping("/knowledge/stats")
    @Operation(summary = "知识库统计", description = "返回当前知识库片段数量。")
    public Map<String, Object> knowledgeStats() {
        return Map.of("total_chunks", knowledgeBaseService.docCount());
    }

    @GetMapping("/monitor")
    @Operation(summary = "监控摘要", description = "返回 Agent 指标、工具统计、告警和优化建议。")
    public Map<String, Object> monitor() {
        return performanceMonitor.summary();
    }

    @GetMapping("/trace/tool/{requestId}")
    @Operation(summary = "查看单次请求的工具轨迹", description = "返回指定 requestId 对应的工具调用详情。")
    public Map<String, Object> toolTrace(@Parameter(description = "请求 ID") @org.springframework.web.bind.annotation.PathVariable String requestId) {
        return orchestrator.getToolTrace(requestId)
                .<Map<String, Object>>map(trace -> Map.of("found", true, "trace", trace))
                .orElseGet(() -> Map.of("found", false, "trace", Map.of()));
    }

    @GetMapping("/trace/tools")
    @Operation(summary = "查看最近工具轨迹", description = "返回最近 N 次请求的工具调用详情。")
    public Map<String, Object> recentToolTraces(@RequestParam(defaultValue = "20") int limit) {
        return Map.of("items", orchestrator.getRecentToolTraces(limit));
    }

    @GetMapping("/skills")
    @Operation(summary = "Skills 摘要", description = "查看当前已加载的动态 Skills，便于确认热加载结果和排查解析错误。")
    public Map<String, Object> skills() {
        return skillManager.summary();
    }

    @PostMapping("/skills/reload")
    @Operation(summary = "重新加载 Skills", description = "运行时重新扫描 Skill 目录，不需要重启服务。")
    public Map<String, Object> reloadSkills() {
        skillManager.reload();
        return skillManager.summary();
    }

    @GetMapping(value = "/metrics", produces = "text/plain; version=0.0.4; charset=utf-8")
    @Operation(summary = "Prometheus 指标", description = "返回 Prometheus 文本格式指标，兼容 Python 版 /metrics 路径。")
    public ResponseEntity<String> metrics() {
        return ResponseEntity.ok(prometheusMeterRegistry.scrape());
    }

    @PostMapping(value = "/eval/run", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "运行评测", description = "运行意图识别和对话质量评测；请求体为空时使用内置默认用例。")
    public Map<String, Object> eval(@RequestBody(required = false) EvalRunRequest request) {
        return evaluator.run(request);
    }

    /**
     * 回答已经生成，落库失败不应该让用户拿不到回复，所以这里只记录错误。
     * 代价是这一轮可能在历史里缺失；后续改成 MQ 异步落库 + 重试来补上可靠性。
     */
    private void persistTurn(ChatTurn turn) {
        try {
            conversationService.recordTurn(turn);
        } catch (Exception ex) {
            log.error("Failed to persist chat turn conv={} request={}: {}", turn.conversationId(), turn.requestId(), ex.getMessage());
        }
    }

    private boolean shouldUseKnowledge(IntentCategory intent) {
        if (intent == null) {
            return false;
        }
        return switch (intent) {
            case QUERY,
                    COMPLAINT,
                    REQUEST,
                    TECHNICAL,
                    BILLING,
                    ACCOUNT,
                    ORDER_STATUS,
                    LOGISTICS,
                    REFUND,
                    INVOICE,
                    PAYMENT_ISSUE,
                    ACCOUNT_SECURITY,
                    TECHNICAL_LOGIN,
                    TECHNICAL_CRASH -> true;
            case GREETING, ESCALATION, HUMAN_HANDOFF, FEEDBACK, OTHER -> false;
        };
    }

    private String buildKnowledgeContext(List<SearchResult> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        parts.add("[知识库检索结果]");
        for (int i = 0; i < results.size(); i++) {
            SearchResult item = results.get(i);
            parts.add((i + 1) + ". 标题: " + item.title() + "\n   相关度: " + item.score() + "\n   内容: " + item.content());
        }
        parts.add("请优先依据以上知识库内容回答；如果知识库内容不足，再结合通用客服能力说明。");
        return String.join("\n", parts);
    }

    private String join(String left, String right) {
        if (left == null || left.isBlank()) {
            return right == null ? "" : right;
        }
        if (right == null || right.isBlank()) {
            return left;
        }
        return left + "\n\n" + right;
    }

    private List<String> agentNames(List<AgentType> agentTypes) {
        if (agentTypes == null) {
            return List.of();
        }
        return agentTypes.stream()
                .map(agentType -> agentType.name().toLowerCase(Locale.ROOT))
                .toList();
    }

    private double round(double value, int digits) {
        double factor = Math.pow(10, digits);
        return Math.round(value * factor) / factor;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> parseJsonDocs(String text) throws Exception {
        List<?> values = objectMapper.readValue(text, List.class);
        List<Map<String, String>> docs = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> map) {
                Map<String, String> doc = new LinkedHashMap<>();
                Object title = map.containsKey("title") ? map.get("title") : "未命名文档";
                Object content = map.containsKey("content") ? map.get("content") : "";
                doc.put("title", String.valueOf(title));
                doc.put("content", String.valueOf(content));
                docs.add(doc);
            }
        }
        return docs;
    }
}
