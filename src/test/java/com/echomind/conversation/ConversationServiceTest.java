package com.echomind.conversation;

import com.echomind.store.ChatMessageEntity;
import com.echomind.store.ChatMessageMapper;
import com.echomind.store.ConversationEntity;
import com.echomind.store.ConversationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceTest {

    private ConversationMapper conversationMapper;
    private ChatMessageMapper messageMapper;
    private ConversationService service;

    @BeforeEach
    void setUp() {
        conversationMapper = mock(ConversationMapper.class);
        messageMapper = mock(ChatMessageMapper.class);
        service = new ConversationService(conversationMapper, messageMapper);
    }

    @Test
    void recordTurnUpsertsConversationAndInsertsUserThenAssistant() {
        service.recordTurn(new ChatTurn("c1", "u1", "r1", "我想退款", "好的，请提供订单号", "refund", "billing", false, 120));

        verify(conversationMapper).upsertTurn(eq("c1"), eq("u1"), eq("我想退款"), eq(2), any());
        ArgumentCaptor<ChatMessageEntity> captor = ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(messageMapper, times(2)).insert(captor.capture());
        List<ChatMessageEntity> saved = captor.getAllValues();
        assertThat(saved).extracting(ChatMessageEntity::getRole).containsExactly("USER", "ASSISTANT");
        assertThat(saved.get(1).getAgentType()).isEqualTo("billing");
        assertThat(saved.get(1).getCreatedAt()).isAfter(saved.get(0).getCreatedAt());
    }

    @Test
    void titleIsTruncatedTo50Chars() {
        String longMessage = "啊".repeat(80);
        service.recordTurn(new ChatTurn("c1", "u1", "r1", longMessage, "ok", "other", "general", false, 1));
        verify(conversationMapper).upsertTurn(eq("c1"), eq("u1"), eq("啊".repeat(50)), eq(2), any());
    }

    @Test
    void writingToSomeoneElsesConversationIsForbidden() {
        when(conversationMapper.selectById("c1")).thenReturn(conversation("c1", "owner"));

        assertThatThrownBy(() -> service.assertWritable("intruder", "c1"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void newConversationIsWritable() {
        when(conversationMapper.selectById("new")).thenReturn(null);
        service.assertWritable("u1", "new");
    }

    @Test
    void readingSomeoneElsesMessagesLooksLikeNotFound() {
        when(conversationMapper.selectById("c1")).thenReturn(conversation("c1", "owner"));

        assertThatThrownBy(() -> service.listMessages("intruder", "c1", null, 20))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        verify(messageMapper, never()).pageBefore(any(), any(), anyInt());
    }

    @Test
    void pageReturnsChronologicalOrderAndCursorWhenMoreExist() {
        when(conversationMapper.selectById("c1")).thenReturn(conversation("c1", "u1"));
        // Mapper 按 id 倒序返回；size=3 时 Service 会多查一条（4 条）来判断是否还有下一页
        when(messageMapper.pageBefore(eq("c1"), isNull(), eq(4))).thenReturn(messages(10, 9, 8, 7));

        ConversationService.MessagePage page = service.listMessages("u1", "c1", null, 3);

        assertThat(page.items()).extracting(ChatMessageEntity::getId).containsExactly(8L, 9L, 10L);
        assertThat(page.nextBeforeId()).isEqualTo(8L);
    }

    @Test
    void lastPageHasNoCursor() {
        when(conversationMapper.selectById("c1")).thenReturn(conversation("c1", "u1"));
        when(messageMapper.pageBefore(eq("c1"), eq(8L), eq(4))).thenReturn(messages(7, 6));

        ConversationService.MessagePage page = service.listMessages("u1", "c1", 8L, 3);

        assertThat(page.items()).extracting(ChatMessageEntity::getId).containsExactly(6L, 7L);
        assertThat(page.nextBeforeId()).isNull();
    }

    @Test
    void pageSizeIsClamped() {
        when(conversationMapper.selectById("c1")).thenReturn(conversation("c1", "u1"));
        when(messageMapper.pageBefore(any(), any(), anyInt())).thenReturn(List.of());

        service.listMessages("u1", "c1", null, 10_000);

        verify(messageMapper).pageBefore("c1", null, ConversationService.MAX_PAGE_SIZE + 1);
    }

    private ConversationEntity conversation(String id, String userId) {
        ConversationEntity entity = new ConversationEntity();
        entity.setId(id);
        entity.setUserId(userId);
        return entity;
    }

    private List<ChatMessageEntity> messages(long... ids) {
        List<ChatMessageEntity> list = new ArrayList<>();
        for (long id : ids) {
            ChatMessageEntity m = new ChatMessageEntity();
            m.setId(id);
            list.add(m);
        }
        return list;
    }
}
