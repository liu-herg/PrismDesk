package com.echomind.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "echomind")
public class EchoMindProperties {

    private final Llm llm = new Llm();
    private final Memory memory = new Memory();
    private final Rag rag = new Rag();
    private final Monitor monitor = new Monitor();
    private final Eval eval = new Eval();
    private final Skills skills = new Skills();
    private final Pipeline pipeline = new Pipeline();

    public Llm getLlm() {
        return llm;
    }

    public Pipeline getPipeline() {
        return pipeline;
    }

    public Memory getMemory() {
        return memory;
    }

    public Rag getRag() {
        return rag;
    }

    public Monitor getMonitor() {
        return monitor;
    }

    public Eval getEval() {
        return eval;
    }

    public Skills getSkills() {
        return skills;
    }

    public static class Llm {
        private boolean fallbackEnabled = true;

        public boolean isFallbackEnabled() {
            return fallbackEnabled;
        }

        public void setFallbackEnabled(boolean fallbackEnabled) {
            this.fallbackEnabled = fallbackEnabled;
        }
    }

    public static class Memory {
        private long ttlSeconds = 86400;
        private int workingMax = 20;
        private int compressAt = 15;

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public int getWorkingMax() {
            return workingMax;
        }

        public void setWorkingMax(int workingMax) {
            this.workingMax = workingMax;
        }

        public int getCompressAt() {
            return compressAt;
        }

        public void setCompressAt(int compressAt) {
            this.compressAt = compressAt;
        }
    }

    public static class Rag {
        private int topK = 4;
        private double bm25Weight = 0.45;
        private double vectorWeight = 0.55;

        public int getTopK() {
            return topK;
        }

        public void setTopK(int topK) {
            this.topK = topK;
        }

        public double getBm25Weight() {
            return bm25Weight;
        }

        public void setBm25Weight(double bm25Weight) {
            this.bm25Weight = bm25Weight;
        }

        public double getVectorWeight() {
            return vectorWeight;
        }

        public void setVectorWeight(double vectorWeight) {
            this.vectorWeight = vectorWeight;
        }
    }

    public static class Monitor {
        private double successRateThreshold = 0.90;
        private double latencyMsThreshold = 3000;
        private String webhookUrl = "";

        public double getSuccessRateThreshold() {
            return successRateThreshold;
        }

        public void setSuccessRateThreshold(double successRateThreshold) {
            this.successRateThreshold = successRateThreshold;
        }

        public double getLatencyMsThreshold() {
            return latencyMsThreshold;
        }

        public void setLatencyMsThreshold(double latencyMsThreshold) {
            this.latencyMsThreshold = latencyMsThreshold;
        }

        public String getWebhookUrl() {
            return webhookUrl;
        }

        public void setWebhookUrl(String webhookUrl) {
            this.webhookUrl = webhookUrl;
        }
    }

    public static class Eval {
        private String baselinePath = "data/eval/baseline.json";

        public String getBaselinePath() {
            return baselinePath;
        }

        public void setBaselinePath(String baselinePath) {
            this.baselinePath = baselinePath;
        }
    }

    public static class Skills {
        private String rootDir = "skills";
        private int maxPromptChars = 5000;

        public String getRootDir() {
            return rootDir;
        }

        public void setRootDir(String rootDir) {
            this.rootDir = rootDir;
        }

        public int getMaxPromptChars() {
            return maxPromptChars;
        }

        public void setMaxPromptChars(int maxPromptChars) {
            this.maxPromptChars = maxPromptChars;
        }
    }

    /** 一轮对话的编排方式和各环节的超时。 */
    public static class Pipeline {
        /** true：意图识别和知识库检索并行执行；false：按原来的顺序串行执行（用于对比）。 */
        private boolean parallel = true;
        private long intentTimeoutMs = 5000;
        private long knowledgeTimeoutMs = 8000;
        private long verifyTimeoutMs = 5000;
        private long agentTimeoutMs = 30000;

        public boolean isParallel() {
            return parallel;
        }

        public void setParallel(boolean parallel) {
            this.parallel = parallel;
        }

        public long getIntentTimeoutMs() {
            return intentTimeoutMs;
        }

        public void setIntentTimeoutMs(long intentTimeoutMs) {
            this.intentTimeoutMs = intentTimeoutMs;
        }

        public long getKnowledgeTimeoutMs() {
            return knowledgeTimeoutMs;
        }

        public void setKnowledgeTimeoutMs(long knowledgeTimeoutMs) {
            this.knowledgeTimeoutMs = knowledgeTimeoutMs;
        }

        public long getVerifyTimeoutMs() {
            return verifyTimeoutMs;
        }

        public void setVerifyTimeoutMs(long verifyTimeoutMs) {
            this.verifyTimeoutMs = verifyTimeoutMs;
        }

        public long getAgentTimeoutMs() {
            return agentTimeoutMs;
        }

        public void setAgentTimeoutMs(long agentTimeoutMs) {
            this.agentTimeoutMs = agentTimeoutMs;
        }
    }
}
