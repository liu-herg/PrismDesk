package com.echomind.intent;

import com.echomind.llm.LlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentRecognizerTest {

    private LlmGateway llm;
    private IntentRecognizer recognizer;

    @BeforeEach
    void setUp() {
        llm = mock(LlmGateway.class);
        recognizer = new IntentRecognizer(llm, new ObjectMapper());
    }

    @Test
    void llmFallbackSentenceIsTreatedAsFailureAndSemanticWins() {
        // 复现线上问题：API key 无效时，网关返回一句降级话术，没有 JSON
        when(llm.chat(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("根据当前知识库，退款通常需要先提交申请并等待审核。请提供订单号。");

        IntentResult result = recognizer.recognize("退款多久到账", List.of());

        assertThat(result.intent()).isEqualTo(IntentCategory.REFUND);
        assertThat(result.sourceScores().get("llm")).isEqualTo(0.0);
    }

    @Test
    void unknownIntentLabelFromLlmIsTreatedAsFailure() {
        when(llm.chat(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"intent\":\"refund_inquiry\",\"confidence\":0.9,\"reasoning\":\"x\"}");

        IntentResult result = recognizer.recognize("退款多久到账", List.of());

        assertThat(result.intent()).isEqualTo(IntentCategory.REFUND);
    }

    @Test
    void validLlmResultIsUsed() {
        when(llm.chat(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"intent\":\"refund\",\"confidence\":0.9,\"reasoning\":\"用户询问退款到账时间\"}");

        IntentResult result = recognizer.recognize("退款多久到账", List.of());

        assertThat(result.intent()).isEqualTo(IntentCategory.REFUND);
        assertThat(result.sourceScores().get("llm")).isEqualTo(0.9);
    }

    @Test
    void explicitOtherFromLlmIsNotTreatedAsFailure() {
        when(llm.chat(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"intent\":\"other\",\"confidence\":0.95,\"reasoning\":\"闲聊\"}");

        IntentResult result = recognizer.recognize("今天天气不错", List.of());

        assertThat(result.intent()).isEqualTo(IntentCategory.OTHER);
        assertThat(result.sourceScores().get("llm")).isEqualTo(0.95);
    }

    @Test
    void degradedResultIsNotCached() {
        when(llm.chat(anyString(), anyString(), anyDouble(), anyInt())).thenReturn("服务不可用");

        recognizer.recognize("退款多久到账", List.of());
        recognizer.recognize("退款多久到账", List.of());

        // 降级结果不缓存，第二次仍会尝试调用 LLM
        verify(llm, times(2)).chat(anyString(), anyString(), anyDouble(), anyInt());
    }

    @Test
    void successfulResultIsCached() {
        when(llm.chat(anyString(), anyString(), anyDouble(), anyInt()))
                .thenReturn("{\"intent\":\"refund\",\"confidence\":0.9,\"reasoning\":\"x\"}");

        recognizer.recognize("退款多久到账", List.of());
        recognizer.recognize("退款多久到账", List.of());

        verify(llm, times(1)).chat(anyString(), anyString(), anyDouble(), anyInt());
    }

    @Test
    void offlineRecognitionNeverCallsLlm() {
        IntentResult result = recognizer.recognizeOffline("退款多久到账");

        assertThat(result.intent()).isEqualTo(IntentCategory.REFUND);
        verify(llm, never()).chat(anyString(), anyString(), anyDouble(), anyInt());
    }
}
