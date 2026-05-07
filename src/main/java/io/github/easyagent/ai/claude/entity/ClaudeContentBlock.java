package io.github.easyagent.ai.claude.entity;

import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import io.github.easyagent.ai.claude.enums.ClaudeContentType;
import io.github.easyagent.ai.entity.AbstractContentBlock;
import io.github.easyagent.util.RawJsonStringAdapter;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Claude 消息内容块。
 * <p>
 * 表示 Claude 响应消息中的单个内容块，继承自 {@link AbstractContentBlock}。
 * 不同类型的 ContentBlock 字段差异：
 * <ul>
 *     <li>thinking：包含 {@code thinking} 推理内容和 {@code signature} 签名</li>
 *     <li>text：包含 {@code text} 文本内容</li>
 *     <li>tool_use：包含 {@code toolUseId}、{@code name}、{@code input} 工具调用信息</li>
 *     <li>tool_result：包含 {@code toolUseId}、{@code content}、{@code isError} 工具执行结果</li>
 * </ul>
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
public class ClaudeContentBlock extends AbstractContentBlock<ClaudeContentType> {

    /** 工具使用 ID，tool_use / tool_result 类型时有值。 */
    @SerializedName("tool_use_id")
    private String toolUseId;

    /** 工具调用 ID，tool_result 类型时有值。 */
    private String id;

    /** 思考内容，thinking 类型时有值。 */
    private String thinking;

    /** 思考签名，thinking 类型时有值。 */
    private String signature;

    /** 工具输入参数的 JSON 字符串，tool_use 类型时有值。 */
    @JsonAdapter(RawJsonStringAdapter.class)
    private String input;

    /** 工具结果内容，tool_result 类型时有值。 */
    private String content;

    /** 工具结果是否为错误，tool_result 类型时有值。 */
    @SerializedName("is_error")
    private Boolean isError;
}
