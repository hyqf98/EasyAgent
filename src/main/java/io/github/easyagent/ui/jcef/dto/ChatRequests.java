package io.github.easyagent.ui.jcef.dto;

import io.github.easyagent.ui.service.entity.FileReferencePayload;

import java.util.List;

/**
 * 聊天消息相关请求 DTO。
 *
 * @author haijun
 * @date 2026/5/18
 * @since 1.1.0
 */
public final class ChatRequests {

    private ChatRequests() {
    }

    /**
     * 发送消息请求。
     *
     * @param action         动作名称
     * @param text           用户文本
     * @param cliType        CLI 类型
     * @param sessionId      会话 ID
     * @param modelId        模型 ID
     * @param reasoningLevel 推理级别
     * @param planMode       是否计划模式
     * @param fileReferences 文件引用列表
     */
    public record SendMessageRequest(String action, String text, String cliType, String sessionId,
                                      String modelId, String reasoningLevel, Boolean planMode,
                                      List<FileReferencePayload> fileReferences) implements JsRequest {
    }
}
