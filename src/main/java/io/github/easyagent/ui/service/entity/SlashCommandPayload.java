package io.github.easyagent.ui.service.entity;

import lombok.Builder;

import java.util.List;

/**
 * 前端可消费的斜杠命令载荷。
 *
 * @param cliType      所属 CLI 类型
 * @param name         命令名，不含前缀 {@code /}
 * @param commandText  展示给用户的命令文本
 * @param description  命令描述
 * @param sourceType   命令来源类型
 * @param aliases      命令别名列表
 * @param group        命令分组
 * @param actionType   执行方式
 * @author haijun
 * @date 2026/5/7
 * @since 1.0.0
 */
@Builder
public record SlashCommandPayload(
        String cliType,
        String name,
        String commandText,
        String description,
        String sourceType,
        List<String> aliases,
        String group,
        String actionType
) {}
