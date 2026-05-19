package io.github.easyagent.ui.jcef.dto;

/**
 * 文件引用与编辑相关请求 DTO。
 *
 * @author haijun
 * @date 2026/5/18
 * @since 1.1.0
 */
public final class FileReferenceRequests {

    private FileReferenceRequests() {
    }

    /**
     * 搜索文件引用请求。
     *
     * @param action   动作名称
     * @param query    查询关键字
     * @param limit    最大结果数
     * @param requestId 请求 ID
     */
    public record SearchFileReferencesRequest(String action, String query, int limit,
                                               String requestId) implements JsRequest {
    }

    /**
     * 解析文件引用请求。
     *
     * @param action 动作名称
     * @param path   文件路径
     */
    public record ResolveFileReferenceRequest(String action, String path) implements JsRequest {
    }

    /**
     * 保存剪贴板图片请求。
     *
     * @param action   动作名称
     * @param dataUrl  图片 data URL
     * @param fileName 文件名
     */
    public record SaveClipboardImageRequest(String action, String dataUrl,
                                             String fileName) implements JsRequest {
    }

    /**
     * 打开文件 diff 请求。
     *
     * @param action     动作名称
     * @param editId     编辑 ID
     * @param toolCallId 工具调用 ID
     * @param path       文件路径
     */
    public record OpenFileEditDiffRequest(String action, String editId, String toolCallId,
                                           String path) implements JsRequest {
    }

    /**
     * 回撤文件编辑请求。
     *
     * @param action     动作名称
     * @param editId     编辑 ID
     * @param toolCallId 工具调用 ID
     * @param path       文件路径
     */
    public record RevertFileEditRequest(String action, String editId, String toolCallId,
                                          String path) implements JsRequest {
    }
}
