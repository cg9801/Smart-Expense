package com.occ.smartexpense.ai;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentLoop 纯单元测试：不启 Spring、不调外部模型（docs/11 §5.9）。
 */
class AgentLoopTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 固定返回值的假工具 */
    private AiTool fixedTool(String name, String result) {
        return new AiTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String execute(String argsJson) {
                return result;
            }
        };
    }

    /** 按剧本返回的假模型；脚本用完后重复最后一条，避免数组越界 */
    private AiChatClient scripted(String... replies) {
        AtomicInteger index = new AtomicInteger();
        return messages -> replies[Math.min(index.getAndIncrement(), replies.length - 1)];
    }

    @Test
    void toolThenFinal_success() {
        AiChatClient client = scripted(
                "{\"tool\":\"getBudget\",\"args\":{\"deptId\":2,\"period\":\"2026-10\"}}",
                "{\"final\":{\"riskLevel\":\"LOW\",\"confidence\":0.9}}");
        AgentLoop loop = new AgentLoop(
                List.of(fixedTool("getBudget", "{\"available\":\"46500.00\"}")), client, objectMapper);

        AgentLoop.AgentResult result = loop.run("{\"reportId\":1}");

        assertTrue(result.success());
        assertEquals(2, result.steps());
        assertEquals(1, result.toolCalls());
        assertEquals(0, result.toolErrors());
    }

    @Test
    void unknownTool_continuesAndCountsError() {
        AiChatClient client = scripted(
                "{\"tool\":\"dropTable\",\"args\":{}}",
                "{\"final\":{\"riskLevel\":\"LOW\"}}");
        AgentLoop loop = new AgentLoop(List.of(fixedTool("getBudget", "{}")), client, objectMapper);

        AgentLoop.AgentResult result = loop.run("{}");

        assertTrue(result.success());
        assertEquals(1, result.toolErrors());
        assertEquals(0, result.toolCalls());
    }

    @Test
    void invalidJson_retriedOnce() {
        AiChatClient client = scripted(
                "not-a-json",
                "{\"final\":{\"riskLevel\":\"LOW\"}}");
        AgentLoop loop = new AgentLoop(List.of(), client, objectMapper);

        AgentLoop.AgentResult result = loop.run("{}");

        assertTrue(result.success());
        assertEquals(2, result.steps());
    }

    @Test
    void stepLimit_stopsAtFive() {
        AiChatClient client = scripted("{\"tool\":\"getBudget\",\"args\":{\"deptId\":2}}");
        AgentLoop loop = new AgentLoop(
                List.of(fixedTool("getBudget", "{\"available\":\"1\"}")), client, objectMapper);

        AgentLoop.AgentResult result = loop.run("{}");

        assertFalse(result.success());
        assertEquals("STEP_LIMIT", result.error());
        assertEquals(5, result.steps());
    }

    @Test
    void modelError_returnsFailed() {
        AiChatClient client = messages -> {
            throw new IllegalStateException("model down");
        };
        AgentLoop loop = new AgentLoop(List.of(), client, objectMapper);

        AgentLoop.AgentResult result = loop.run("{}");

        assertFalse(result.success());
        assertEquals("MODEL_ERROR", result.error());
    }
}
