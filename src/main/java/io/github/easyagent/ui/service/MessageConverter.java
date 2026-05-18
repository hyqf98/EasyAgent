package io.github.easyagent.ui.service;

import io.github.easyagent.ai.entity.AIResponse;
import io.github.easyagent.ai.entity.CompactContent;
import io.github.easyagent.ai.entity.MessageContent;
import io.github.easyagent.ai.entity.ToolCallContent;
import io.github.easyagent.enums.ResponseType;
import io.github.easyagent.session.entity.ContentBlock;
import io.github.easyagent.session.entity.SessionMessage;
import io.github.easyagent.ui.service.entity.FileEditPayload;
import io.github.easyagent.util.GsonUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    /** JSON 字段名：工具输入字符串。 */
    private static final String FIELD_INPUT = "input";

    /** JSON 字段名：工具输出字符串。 */
    private static final String FIELD_OUTPUT = "output";

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

    /** JSON 字段名：文件编辑元数据。 */
    private static final String FIELD_FILE_EDIT = "fileEdit";

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
    public static String toHistoryJson(@NotNull String sessionId, @NotNull List<SessionMessage> messages,
                                       @Nullable String projectPath) {
        List<SessionMessagePayload> payloads = messages.stream()
                .map(msg -> convertSessionMessage(sessionId, projectPath, msg))
                .toList();
        return GsonUtils.toJson(new HistoryPayload(sessionId, payloads));
    }

    /**
     * 将流式 AI 响应转换为前端 JSON 事件。
     *
     * @param response AI 响应对象
     * @return 包含 type 和对应数据的 JSON 字符串
     */
    public static String toStreamEventJson(@NotNull AIResponse response, @NotNull String sessionId,
                                           @Nullable String projectPath) {
        Object event = switch (response.type()) {
            case MESSAGE -> convertMessageEvent(sessionId, response);
            case TOOL_USE -> convertToolUseEvent(sessionId, projectPath, response);
            case STEP_START -> convertStepStartEvent(sessionId, response);
            case STEP_FINISH -> convertStepFinishEvent(sessionId, response);
            case COMPACT -> convertCompactEvent(sessionId, response);
            case ERROR -> convertErrorEvent(sessionId, response);
            case RETRY_STATUS -> convertRetryStatusEvent(sessionId, response);
        };
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
        return GsonUtils.toJson(new ErrorEventPayload(sessionId, ResponseType.ERROR.name(), message));
    }

    /**
     * 转换消息类型事件。
     *
     * @param response AI 响应对象
     * @param event    目标 JSON 对象
     */
    private static MessageEventPayload convertMessageEvent(String sessionId, AIResponse response) {
        MessageContent msg = response.message();
        return new MessageEventPayload(sessionId, ResponseType.MESSAGE.name(), msg.messageType().name(), msg.text());
    }

    /**
     * 转换工具调用事件。
     *
     * @param response AI 响应对象
     * @param event    目标 JSON 对象
     */
    private static ToolUseEventPayload convertToolUseEvent(String sessionId, String projectPath,
                                                           AIResponse response) {
        ToolCallContent tool = response.toolCall();
        return new ToolUseEventPayload(sessionId, ResponseType.TOOL_USE.name(), tool.toolName(), tool.title(),
                tool.status().name(), tool.toolCallId(), tool.input(), tool.output(),
                addFileEdit(sessionId, projectPath, tool));
    }

    /**
     * 转换步骤开始事件。
     *
     * @param response AI 响应对象
     * @param event    目标 JSON 对象
     */
    private static StepStartEventPayload convertStepStartEvent(String sessionId, AIResponse response) {
        return new StepStartEventPayload(sessionId, ResponseType.STEP_START.name(), response.stepStart().messageId());
    }

    /**
     * 转换步骤结束事件。
     *
     * @param response AI 响应对象
     * @param event    目标 JSON 对象
     */
    private static StepFinishEventPayload convertStepFinishEvent(String sessionId, AIResponse response) {
        return new StepFinishEventPayload(sessionId, ResponseType.STEP_FINISH.name(), response.stepFinish().reason(),
                response.stepFinish().tokenUsage());
    }

    /**
     * 转换上下文压缩事件。
     *
     * @param response AI 响应对象
     * @param event    目标 JSON 对象
     */
    private static CompactEventPayload convertCompactEvent(String sessionId, AIResponse response) {
        CompactContent compact = response.compact();
        return new CompactEventPayload(sessionId, ResponseType.COMPACT.name(), compact.reason(),
                compact.trigger(), compact.preTokens(), compact.postTokens(), compact.durationMs());
    }

    /**
     * 转换错误事件。
     *
     * @param response AI 响应对象
     * @param event    目标 JSON 对象
     */
    private static ErrorEventPayload convertErrorEvent(String sessionId, AIResponse response) {
        return new ErrorEventPayload(sessionId, ResponseType.ERROR.name(), response.error().message());
    }

    /**
     * 转换重试状态事件。
     *
     * @param response AI 响应对象
     * @param event    目标 JSON 对象
     */
    private static RetryStatusEventPayload convertRetryStatusEvent(String sessionId, AIResponse response) {
        return new RetryStatusEventPayload(sessionId, ResponseType.RETRY_STATUS.name(),
                response.retryStatus().currentAttempt(), response.retryStatus().maxAttempts(),
                response.retryStatus().reason());
    }

    /**
     * 将 {@link SessionMessage} 转换为前端 JSON 对象。
     *
     * @param msg 会话消息
     * @return JSON 对象
     */
    private static SessionMessagePayload convertSessionMessage(String sessionId, String projectPath, SessionMessage msg) {
        List<ContentBlockPayload> contents = msg.contents() == null || msg.contents().isEmpty()
                ? List.of()
                : msg.contents().stream()
                .map(block -> convertContentBlock(sessionId, projectPath, block))
                .toList();
        return new SessionMessagePayload(msg.uuid(), msg.role().name(), msg.model(), msg.timestamp(), contents,
                msg.tokenUsage());
    }

    /**
     * 将 {@link ContentBlock} 转换为前端 JSON 对象。
     *
     * @param block 内容块
     * @return JSON 对象
     */
    private static ContentBlockPayload convertContentBlock(String sessionId, String projectPath, ContentBlock block) {
        String title = block.toolName() != null && block.text() != null ? block.text() : null;
        return new ContentBlockPayload(block.type().name(), block.text(), block.thinking(), block.toolName(),
                block.toolUseId(), title, block.toolInput(), block.toolOutput(), block.isError(),
                block.durationMs(), addFileEdit(sessionId, projectPath, block));
    }

    /**
     * 为流式工具调用事件补充文件编辑元数据。
     *
     * @param sessionId   会话 ID
     * @param projectPath 项目路径
     * @param toolCall    工具调用内容
     * @return 文件编辑元数据
     */
    private static FileEditPayload addFileEdit(String sessionId, String projectPath,
                                               ToolCallContent toolCall) {
        return ToolMetadataSupport.resolveFileEdit(sessionId, projectPath, toolCall);
    }

    /**
     * 为历史工具调用内容块补充文件编辑元数据。
     *
     * @param sessionId   会话 ID
     * @param projectPath 项目路径
     * @param block       内容块
     * @return 文件编辑元数据
     */
    private static FileEditPayload addFileEdit(String sessionId, String projectPath,
                                               ContentBlock block) {
        return ToolMetadataSupport.resolveFileEdit(sessionId, projectPath, block);
    }

    /**
     * 历史消息载荷。
     *
     * @param sessionId 会话 ID
     * @param messages  消息列表
     */
    private record HistoryPayload(String sessionId, List<SessionMessagePayload> messages) {
    }

    /**
     * 历史消息载荷中的消息实体。
     *
     * @param uuid       消息 UUID
     * @param role       消息角色
     * @param model      模型名称
     * @param timestamp  时间戳
     * @param contents   内容块列表
     * @param tokenUsage 令牌使用统计
     */
    private record SessionMessagePayload(String uuid, String role, String model, Long timestamp,
                                         List<ContentBlockPayload> contents, Object tokenUsage) {
    }

    /**
     * 历史消息内容块载荷。
     *
     * @param type       内容块类型
     * @param text       文本内容
     * @param thinking   思考内容
     * @param toolName   工具名称
     * @param toolUseId  工具调用 ID
     * @param title      展示标题
     * @param toolInput  工具输入
     * @param toolOutput 工具输出
     * @param isError    是否出错
     * @param durationMs 执行耗时
     * @param fileEdit   文件编辑元数据
     */
    private record ContentBlockPayload(String type, String text, String thinking, String toolName,
                                       String toolUseId, String title, String toolInput, String toolOutput,
                                       Boolean isError, Long durationMs, FileEditPayload fileEdit) {
    }

    /**
     * 流式消息事件载荷。
     *
     * @param sessionId 会话 ID
     * @param type      事件类型
     * @param messageType 消息类型
     * @param text      文本内容
     */
    private record MessageEventPayload(String sessionId, String type, String messageType, String text) {
    }

    /**
     * 流式工具调用事件载荷。
     *
     * @param sessionId 会话 ID
     * @param type      事件类型
     * @param toolName  工具名称
     * @param title     展示标题
     * @param status    工具状态
     * @param toolUseId 工具调用 ID
     * @param input     工具输入
     * @param output    工具输出
     * @param fileEdit  文件编辑元数据
     */
    private record ToolUseEventPayload(String sessionId, String type, String toolName, String title, String status,
                                       String toolUseId, String input, String output, FileEditPayload fileEdit) {
    }

    /**
     * 步骤开始事件载荷。
     *
     * @param sessionId  会话 ID
     * @param type       事件类型
     * @param messageId  步骤消息 ID
     */
    private record StepStartEventPayload(String sessionId, String type, String messageId) {
    }

    /**
     * 步骤结束事件载荷。
     *
     * @param sessionId  会话 ID
     * @param type       事件类型
     * @param reason     结束原因
     * @param tokenUsage 令牌统计
     */
    private record StepFinishEventPayload(String sessionId, String type, String reason, Object tokenUsage) {
    }

    /**
     * 压缩事件载荷。
     *
     * @param sessionId 会话 ID
     * @param type      事件类型
     * @param reason    压缩原因
     */
    private record CompactEventPayload(String sessionId, String type, String reason,
                                       String trigger, Long preTokens, Long postTokens, Long durationMs) {
    }

    /**
     * 错误事件载荷。
     *
     * @param sessionId 会话 ID
     * @param type      事件类型
     * @param message   错误消息
     */
    private record ErrorEventPayload(String sessionId, String type, String message) {
    }

    /**
     * 重试状态事件载荷。
     *
     * @param sessionId      会话 ID
     * @param type           事件类型
     * @param currentAttempt 当前重试次数
     * @param maxAttempts    最大重试次数
     * @param reason         重试原因
     */
    private record RetryStatusEventPayload(String sessionId, String type, int currentAttempt, int maxAttempts,
                                           String reason) {
    }
}
