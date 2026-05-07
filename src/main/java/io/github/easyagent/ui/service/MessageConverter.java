package io.github.easyagent.ui.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.util.containers.ContainerUtil;
import io.github.easyagent.ai.entity.AIResponse;
import io.github.easyagent.ai.entity.MessageContent;
import io.github.easyagent.ai.entity.ToolCallContent;
import io.github.easyagent.enums.ResponseType;
import io.github.easyagent.session.entity.ContentBlock;
import io.github.easyagent.session.entity.SessionMessage;
import io.github.easyagent.util.GsonUtils;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 消息转换器。
 * <p>
 * 将 Java 端的 {@link AIResponse} 和 {@link SessionMessage} 转换为
 * 前端 Vue3 应用可消费的 JSON 字符串格式。
 * </p>
 * <p>
 * 转换规则：
 * <ul>
 *   <li>历史会话：{@link SessionMessage} → JSON（包含 role、contents、timestamp）</li>
 *   <li>流式事件：{@link AIResponse} → JSON（包含 type、text、toolName 等）</li>
 *   <li>错误消息：异常信息 → JSON（包含 type=ERROR、message）</li>
 * </ul>
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
public class MessageConverter {

    /** JSON 字段名：会话 ID。 */
    private static final String FIELD_SESSION_ID = "sessionId";

    /** JSON 字段名：消息列表。 */
    private static final String FIELD_MESSAGES = "messages";

    /** JSON 字段名：事件类型。 */
    private static final String FIELD_TYPE = "type";

    /** JSON 字段名：消息内容。 */
    private static final String FIELD_TEXT = "text";

    /** JSON 字段名：消息类型。 */
    private static final String FIELD_MESSAGE_TYPE = "messageType";

    /** JSON 字段名：消息 UUID。 */
    private static final String FIELD_UUID = "uuid";

    /** JSON 字段名：消息角色。 */
    private static final String FIELD_ROLE = "role";

    /** JSON 字段名：模型名称。 */
    private static final String FIELD_MODEL = "model";

    /** JSON 字段名：时间戳。 */
    private static final String FIELD_TIMESTAMP = "timestamp";

    /** JSON 字段名：内容块列表。 */
    private static final String FIELD_CONTENTS = "contents";

    /** JSON 字段名：思考内容。 */
    private static final String FIELD_THINKING = "thinking";

    /** JSON 字段名：工具名称。 */
    private static final String FIELD_TOOL_NAME = "toolName";

    /** JSON 字段名：工具调用 ID。 */
    private static final String FIELD_TOOL_USE_ID = "toolUseId";

    /** JSON 字段名：工具输入。 */
    private static final String FIELD_TOOL_INPUT = "toolInput";

    /** JSON 字段名：工具输出。 */
    private static final String FIELD_TOOL_OUTPUT = "toolOutput";

    /** JSON 字段名：是否错误。 */
    private static final String FIELD_IS_ERROR = "isError";

    /** JSON 字段名：执行耗时。 */
    private static final String FIELD_DURATION_MS = "durationMs";

    /** JSON 字段名：工具标题。 */
    private static final String FIELD_TITLE = "title";

    /** JSON 字段名：工具调用状态。 */
    private static final String FIELD_STATUS = "status";

    /** JSON 字段名：步骤消息 ID。 */
    private static final String FIELD_MESSAGE_ID = "messageId";

    /** JSON 字段名：完成原因。 */
    private static final String FIELD_REASON = "reason";

    /** JSON 字段名：令牌使用统计。 */
    private static final String FIELD_TOKEN_USAGE = "tokenUsage";

    /** JSON 字段名：错误消息。 */
    private static final String FIELD_MESSAGE = "message";

    /** JSON 字段名：当前重试次数。 */
    private static final String FIELD_CURRENT_ATTEMPT = "currentAttempt";

    /** JSON 字段名：最大重试次数。 */
    private static final String FIELD_MAX_ATTEMPTS = "maxAttempts";



    /**
     * 将历史会话消息列表转换为前端 JSON。
     *
     * @param sessionId 会话 ID
     * @param messages  消息列表
     * @return 包含 sessionId 和 messages 数组的 JSON 字符串
     */
    public static String toHistoryJson(@NotNull String sessionId, @NotNull List<SessionMessage> messages) {
        JsonObject root = new JsonObject();
        root.addProperty(FIELD_SESSION_ID, sessionId);

        JsonArray msgArray = new JsonArray();
        for (SessionMessage msg : messages) {
            msgArray.add(convertSessionMessage(msg));
        }
        root.add(FIELD_MESSAGES, msgArray);

        return GsonUtils.toJson(root);
    }

    /**
     * 将流式 AI 响应转换为前端 JSON 事件。
     *
     * @param response AI 响应对象
     * @return 包含 type 和对应数据的 JSON 字符串
     */
    public static String toStreamEventJson(@NotNull AIResponse response) {
        JsonObject event = new JsonObject();
        event.addProperty(FIELD_SESSION_ID, response.sessionId());

        switch (response.type()) {
            case MESSAGE -> convertMessageEvent(response, event);
            case TOOL_USE -> convertToolUseEvent(response, event);
            case STEP_START -> convertStepStartEvent(response, event);
            case STEP_FINISH -> convertStepFinishEvent(response, event);
            case COMPACT -> convertCompactEvent(response, event);
            case ERROR -> convertErrorEvent(response, event);
            case RETRY_STATUS -> convertRetryStatusEvent(response, event);
        }
        return GsonUtils.toJson(event);
    }

    /**
     * 将错误信息转换为前端 JSON。
     *
     * @param message   错误消息文本
     * @param sessionId 会话 ID
     * @return 包含 type=ERROR、message 和 sessionId 的 JSON 字符串
     */
    public static String toErrorJson(@NotNull String message, String sessionId) {
        JsonObject event = new JsonObject();
        event.addProperty(FIELD_TYPE, ResponseType.ERROR.name());
        event.addProperty(FIELD_MESSAGE, message);
        if (sessionId != null) {
            event.addProperty(FIELD_SESSION_ID, sessionId);
        }
        return GsonUtils.toJson(event);
    }

    /**
     * 转换消息类型事件。
     *
     * @param response AI 响应对象
     * @param event    目标 JSON 对象
     */
    private static void convertMessageEvent(AIResponse response, JsonObject event) {
        MessageContent msg = response.message();
        event.addProperty(FIELD_TYPE, ResponseType.MESSAGE.name());
        event.addProperty(FIELD_MESSAGE_TYPE, msg.messageType().name());
        event.addProperty(FIELD_TEXT, msg.text());
    }

    /**
     * 转换工具调用事件。
     *
     * @param response AI 响应对象
     * @param event    目标 JSON 对象
     */
    private static void convertToolUseEvent(AIResponse response, JsonObject event) {
        ToolCallContent tool = response.toolCall();
        event.addProperty(FIELD_TYPE, ResponseType.TOOL_USE.name());
        event.addProperty(FIELD_TOOL_NAME, tool.toolName());
        event.addProperty(FIELD_TITLE, tool.title());
        event.addProperty(FIELD_STATUS, tool.status().name());
        event.addProperty(FIELD_TEXT, tool.input());
        event.addProperty(FIELD_TOOL_OUTPUT, tool.output());
    }

    /**
     * 转换步骤开始事件。
     *
     * @param response AI 响应对象
     * @param event    目标 JSON 对象
     */
    private static void convertStepStartEvent(AIResponse response, JsonObject event) {
        event.addProperty(FIELD_TYPE, ResponseType.STEP_START.name());
        event.addProperty(FIELD_MESSAGE_ID, response.stepStart().messageId());
    }

    /**
     * 转换步骤结束事件。
     *
     * @param response AI 响应对象
     * @param event    目标 JSON 对象
     */
    private static void convertStepFinishEvent(AIResponse response, JsonObject event) {
        event.addProperty(FIELD_TYPE, ResponseType.STEP_FINISH.name());
        event.addProperty(FIELD_REASON, response.stepFinish().reason());
        if (response.stepFinish().tokenUsage() != null) {
            event.add(FIELD_TOKEN_USAGE, JsonParser.parseString(
                    GsonUtils.toJson(response.stepFinish().tokenUsage())));
        }
    }

    /**
     * 转换上下文压缩事件。
     *
     * @param response AI 响应对象
     * @param event    目标 JSON 对象
     */
    private static void convertCompactEvent(AIResponse response, JsonObject event) {
        event.addProperty(FIELD_TYPE, ResponseType.COMPACT.name());
        event.addProperty(FIELD_REASON, response.compact().reason());
    }

    /**
     * 转换错误事件。
     *
     * @param response AI 响应对象
     * @param event    目标 JSON 对象
     */
    private static void convertErrorEvent(AIResponse response, JsonObject event) {
        event.addProperty(FIELD_TYPE, ResponseType.ERROR.name());
        event.addProperty(FIELD_MESSAGE, response.error().message());
    }

    /**
     * 转换重试状态事件。
     *
     * @param response AI 响应对象
     * @param event    目标 JSON 对象
     */
    private static void convertRetryStatusEvent(AIResponse response, JsonObject event) {
        event.addProperty(FIELD_TYPE, ResponseType.RETRY_STATUS.name());
        event.addProperty(FIELD_CURRENT_ATTEMPT, response.retryStatus().currentAttempt());
        event.addProperty(FIELD_MAX_ATTEMPTS, response.retryStatus().maxAttempts());
        event.addProperty(FIELD_REASON, response.retryStatus().reason());
    }

    /**
     * 将 {@link SessionMessage} 转换为前端 JSON 对象。
     *
     * @param msg 会话消息
     * @return JSON 对象
     */
    private static JsonObject convertSessionMessage(SessionMessage msg) {
        JsonObject obj = new JsonObject();
        obj.addProperty(FIELD_UUID, msg.uuid());
        obj.addProperty(FIELD_ROLE, msg.role().name());
        obj.addProperty(FIELD_MODEL, msg.model());
        if (msg.timestamp() != null) {
            obj.addProperty(FIELD_TIMESTAMP, msg.timestamp());
        }

        if (!ContainerUtil.isEmpty(msg.contents())) {
            JsonArray contents = new JsonArray();
            for (ContentBlock block : msg.contents()) {
                contents.add(convertContentBlock(block));
            }
            obj.add(FIELD_CONTENTS, contents);
        }

        if (msg.tokenUsage() != null) {
            obj.add(FIELD_TOKEN_USAGE, JsonParser.parseString(GsonUtils.toJson(msg.tokenUsage())));
        }

        return obj;
    }

    /**
     * 将 {@link ContentBlock} 转换为前端 JSON 对象。
     *
     * @param block 内容块
     * @return JSON 对象
     */
    private static JsonObject convertContentBlock(ContentBlock block) {
        JsonObject obj = new JsonObject();
        obj.addProperty(FIELD_TYPE, block.type().name());

        if (block.text() != null) {
            obj.addProperty(FIELD_TEXT, block.text());
        }
        if (block.thinking() != null) {
            obj.addProperty(FIELD_THINKING, block.thinking());
        }
        if (block.toolName() != null) {
            obj.addProperty(FIELD_TOOL_NAME, block.toolName());
        }
        if (block.toolUseId() != null) {
            obj.addProperty(FIELD_TOOL_USE_ID, block.toolUseId());
        }
        if (block.toolInput() != null) {
            obj.add(FIELD_TOOL_INPUT, JsonParser.parseString(GsonUtils.toJson(block.toolInput())));
        }
        if (block.toolOutput() != null) {
            obj.addProperty(FIELD_TOOL_OUTPUT, block.toolOutput());
        }
        if (block.isError() != null) {
            obj.addProperty(FIELD_IS_ERROR, block.isError());
        }
        if (block.durationMs() != null) {
            obj.addProperty(FIELD_DURATION_MS, block.durationMs());
        }
        return obj;
    }
}
