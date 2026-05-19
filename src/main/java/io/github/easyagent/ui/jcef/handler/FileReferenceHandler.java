package io.github.easyagent.ui.jcef.handler;

import com.intellij.openapi.application.ApplicationManager;
import io.github.easyagent.ui.enums.JsAction;
import io.github.easyagent.ui.enums.JsCallback;
import io.github.easyagent.ui.jcef.dto.CallbackPayloads;
import io.github.easyagent.ui.jcef.dto.FileReferenceRequests;
import io.github.easyagent.ui.service.entity.FileReferenceCandidatePayload;
import io.github.easyagent.ui.service.entity.FileReferencePayload;

import java.util.List;
import java.util.Map;

/**
 * 文件引用管理 handler，负责文件引用搜索、解析、剪贴板图片保存、编辑 diff 打开与回撤。
 *
 * @author haijun
 * @date 2026/5/19
 * @since 1.1.0
 */
public class FileReferenceHandler implements MessageHandler {

    @Override
    public void register(BridgeContext ctx, Map<JsAction, QueryHandlerRecord<?>> handlers) {
        ctx.registerHandler(handlers, JsAction.SEARCH_FILE_REFERENCES, FileReferenceRequests.SearchFileReferencesRequest.class,
                request -> this.handleSearchFileReferences(ctx, request));
        ctx.registerHandler(handlers, JsAction.RESOLVE_FILE_REFERENCE, FileReferenceRequests.ResolveFileReferenceRequest.class,
                request -> this.handleResolveFileReference(ctx, request));
        ctx.registerHandler(handlers, JsAction.SAVE_CLIPBOARD_IMAGE, FileReferenceRequests.SaveClipboardImageRequest.class,
                request -> this.handleSaveClipboardImage(ctx, request));
        ctx.registerHandler(handlers, JsAction.OPEN_FILE_EDIT_DIFF, FileReferenceRequests.OpenFileEditDiffRequest.class,
                request -> this.handleOpenFileEditDiff(ctx, request));
        ctx.registerHandler(handlers, JsAction.REVERT_FILE_EDIT, FileReferenceRequests.RevertFileEditRequest.class,
                request -> this.handleRevertFileEdit(ctx, request));
    }

    private void handleSearchFileReferences(BridgeContext ctx, FileReferenceRequests.SearchFileReferencesRequest request) {
        String query = request.query();
        int limit = request.limit() > 0 ? request.limit() : 12;
        String requestId = request.requestId();
        ctx.asyncExecutor().submit(() -> {
            ctx.invokeJSCallback(JsCallback.FILE_REFERENCE_CANDIDATES,
                    new CallbackPayloads.FileReferenceCandidatesPayload(requestId != null ? requestId : "",
                            ctx.fileReferenceService() != null
                                    ? ctx.fileReferenceService().searchCandidates(query, limit)
                                    : List.of()));
        });
    }

    private void handleResolveFileReference(BridgeContext ctx, FileReferenceRequests.ResolveFileReferenceRequest request) {
        String filePath = request.path();
        if (ctx.fileReferenceService() == null || filePath == null || filePath.isBlank()) {
            return;
        }
        FileReferencePayload reference = ctx.fileReferenceService().createReference(filePath);
        if (reference != null) {
            this.pushFileReferences(ctx, List.of(reference));
        }
    }

    private void handleSaveClipboardImage(BridgeContext ctx, FileReferenceRequests.SaveClipboardImageRequest request) {
        String dataUrl = request.dataUrl();
        if (ctx.fileReferenceService() == null || dataUrl == null || dataUrl.isBlank()) {
            return;
        }
        ctx.asyncExecutor().submit(() -> {
            FileReferencePayload reference = ctx.fileReferenceService().createImageReference(dataUrl, request.fileName());
            if (reference != null) {
                FileReferenceHandler.this.pushFileReferences(ctx, List.of(reference));
            }
        });
    }

    public void pushFileReferences(BridgeContext ctx, List<FileReferencePayload> references) {
        if (references == null || references.isEmpty()) {
            return;
        }
        ctx.invokeJSCallback(JsCallback.INSERT_REFERENCES, references);
    }

    private void handleOpenFileEditDiff(BridgeContext ctx, FileReferenceRequests.OpenFileEditDiffRequest request) {
        if (request == null || (request.editId() == null || request.editId().isBlank())
                && (request.toolCallId() == null || request.toolCallId().isBlank())
                && (request.path() == null || request.path().isBlank())) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> ctx.fileEditService().openDiff(
                request.editId(), request.toolCallId(), request.path()));
    }

    private void handleRevertFileEdit(BridgeContext ctx, FileReferenceRequests.RevertFileEditRequest request) {
        if (request == null || (request.editId() == null || request.editId().isBlank())
                && (request.toolCallId() == null || request.toolCallId().isBlank())
                && (request.path() == null || request.path().isBlank())) {
            return;
        }
        ApplicationManager.getApplication().invokeLater(() -> ctx.fileEditService().revertEdit(
                request.editId(), request.toolCallId(), request.path()));
    }
}
