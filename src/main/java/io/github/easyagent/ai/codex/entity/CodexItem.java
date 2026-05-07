package io.github.easyagent.ai.codex.entity;

import com.google.gson.annotations.SerializedName;
import io.github.easyagent.ai.codex.enums.CodexItemType;
import io.github.easyagent.ai.entity.AbstractContentBlock;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Codex 消息项。
 * <p>
 * 对应 Codex item.completed 事件中的 item 字段，继承自 {@link AbstractContentBlock}。
 * 扩展项 ID、工具调用 ID 和参数字段。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CodexItem extends AbstractContentBlock<CodexItemType> {

    /** 项 ID。 */
    private String id;

    /** 工具调用 ID，tool_call 类型时有值。 */
    @SerializedName("call_id")
    private String callId;

    /** 工具调用参数 JSON。 */
    private String arguments;
}
