package io.github.easyagent.ai.claude;

import io.github.easyagent.ai.StreamEventListener;
import io.github.easyagent.ai.entity.AIResponse;
import io.github.easyagent.enums.MessageType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Claude CLI Provider 集成测试。
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
class ClaudeCLIProviderTest {

    @Test
    void shouldChatAndPrintResponses() throws Exception {
        String prompt = "say hi in one word";
        CountDownLatch latch = new CountDownLatch(1);
        List<AIResponse> responses = new ArrayList<>();

        StreamEventListener listener = new StreamEventListener() {
            @Override
            public void onResponse(AIResponse response) {
                responses.add(response);
                printResponse(response);
            }

            @Override
            public void onComplete() {
                System.out.println("\n========== Stream Complete ==========\n");
                latch.countDown();
            }

            @Override
            public void onError(Exception e) {
                System.err.println("[ERROR] " + e.getMessage());
                latch.countDown();
            }
        };

        System.out.println("\n========== Sending: " + prompt + " ==========\n");
        ClaudeCLIProvider provider = new ClaudeCLIProvider();
        provider.chat(prompt, listener);

        latch.await(120, TimeUnit.SECONDS);
        provider.shutdown();

        System.out.println("\n========== Summary: " + responses.size() + " events ==========");
    }

    private static void printResponse(AIResponse r) {
        switch (r.type()) {
            case STEP_START -> System.out.println("[STEP START] session: " + r.sessionId());
            case MESSAGE -> {
                String tag = r.message().messageType() == MessageType.THINKING ? "THINKING" : "TEXT";
                System.out.println("[" + tag + "] " + r.message().text());
            }
            case TOOL_USE -> {
                System.out.println("[TOOL] " + r.toolCall().toolName() + " | " + r.toolCall().title() + " | " + r.toolCall().status());
                System.out.println("[TOOL OUTPUT] " + r.toolCall().output());
            }
            case STEP_FINISH -> System.out.println("[STEP FINISH] reason: " + r.stepFinish().reason());
            case COMPACT -> System.out.println("[COMPACT] " + r.compact().reason());
            default -> System.out.println("[" + r.type() + "]");
        }
    }
}
