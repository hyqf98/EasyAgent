package io.github.easyagent.ui.jcef.handler;

import io.github.easyagent.ui.enums.JsCallback;
import io.github.easyagent.settings.skills.SkillEntry;
import io.github.easyagent.settings.skills.SkillsConfigService;
import io.github.easyagent.ui.enums.JsAction;
import io.github.easyagent.ui.jcef.dto.CallbackPayloads;
import io.github.easyagent.ui.jcef.dto.SkillRequests;

import java.util.List;
import java.util.Map;

/**
 * Skills 管理 handler，负责 Skills 的 CRUD 操作和远程仓库列表查询。
 *
 * @author haijun
 * @date 2026/5/19
 * @since 1.1.0
 */
public class SkillHandler implements MessageHandler {

    @Override
    public void register(BridgeContext ctx, Map<JsAction, QueryHandlerRecord<?>> handlers) {
        ctx.registerHandler(handlers, JsAction.GET_SKILLS, SkillRequests.GetSkillsRequest.class,
                request -> this.handleGetSkills(ctx, request));
        ctx.registerHandler(handlers, JsAction.INSTALL_SKILL, SkillRequests.InstallSkillRequest.class,
                request -> this.handleInstallSkill(ctx, request));
        ctx.registerHandler(handlers, JsAction.DELETE_SKILL, SkillRequests.DeleteSkillRequest.class,
                request -> this.handleDeleteSkill(ctx, request));
        ctx.registerHandler(handlers, JsAction.READ_SKILL_CONTENT, SkillRequests.ReadSkillContentRequest.class,
                request -> this.handleReadSkillContent(ctx, request));
        ctx.registerHandler(handlers, JsAction.SAVE_SKILL_CONTENT, SkillRequests.SaveSkillContentRequest.class,
                request -> this.handleSaveSkillContent(ctx, request));
        ctx.registerHandler(handlers, JsAction.LIST_KNOWN_REPOS, SkillRequests.ListKnownReposRequest.class,
                request -> this.handleListKnownRepos(ctx, request));
        ctx.registerHandler(handlers, JsAction.LIST_REMOTE_SKILLS, SkillRequests.ListRemoteSkillsRequest.class,
                request -> this.handleListRemoteSkills(ctx, request));
    }

    private void handleGetSkills(BridgeContext ctx, SkillRequests.GetSkillsRequest request) {
        ctx.asyncExecutor().submit(() -> {
            try {
                List<SkillEntry> skills = ctx.skillsConfigService().loadSkills(request.cliType());
                ctx.invokeJSCallback(JsCallback.SKILLS, skills);
            } catch (Exception e) {
                ctx.invokeJSCallback(JsCallback.SKILLS, List.of());
            }
        });
    }

    private void handleInstallSkill(BridgeContext ctx, SkillRequests.InstallSkillRequest request) {
        ctx.asyncExecutor().submit(() -> {
            try {
                String cliType = request.cliType();
                SkillsConfigService.InstallResult result = ctx.skillsConfigService().installSkill(
                        cliType, request.githubUrl(), request.skillName(), request.scope());
                ctx.invokeJSCallback(JsCallback.SKILL_INSTALLED,
                        new CallbackPayloads.SkillActionPayload(result.success(), cliType, result.message()));
                if (result.success()) {
                    List<SkillEntry> skills = ctx.skillsConfigService().loadSkills(cliType);
                    ctx.invokeJSCallback(JsCallback.SKILLS, skills);
                }
            } catch (Exception e) {
                ctx.invokeJSCallback(JsCallback.SKILL_INSTALLED,
                        new CallbackPayloads.SkillActionPayload(false, request.cliType(), e.getMessage()));
            }
        });
    }

    private void handleDeleteSkill(BridgeContext ctx, SkillRequests.DeleteSkillRequest request) {
        ctx.asyncExecutor().submit(() -> {
            try {
                String cliType = request.cliType();
                SkillsConfigService.DeleteResult result = ctx.skillsConfigService().deleteSkill(
                        cliType, request.skillName(), request.skillPath());
                ctx.invokeJSCallback(JsCallback.SKILL_DELETED,
                        new CallbackPayloads.SkillActionPayload(result.success(), cliType, result.message()));
                if (result.success()) {
                    List<SkillEntry> skills = ctx.skillsConfigService().loadSkills(cliType);
                    ctx.invokeJSCallback(JsCallback.SKILLS, skills);
                }
            } catch (Exception e) {
                ctx.invokeJSCallback(JsCallback.SKILL_DELETED,
                        new CallbackPayloads.SkillActionPayload(false, request.cliType(), e.getMessage()));
            }
        });
    }

    private void handleReadSkillContent(BridgeContext ctx, SkillRequests.ReadSkillContentRequest request) {
        ctx.asyncExecutor().submit(() -> {
            try {
                String content = ctx.skillsConfigService().readSkillContent(request.skillPath());
                ctx.invokeJSCallback(JsCallback.SKILL_CONTENT,
                        new CallbackPayloads.SkillContentPayload(request.skillPath(), content != null ? content : ""));
            } catch (Exception e) {
                ctx.invokeJSCallback(JsCallback.SKILL_CONTENT,
                        new CallbackPayloads.SkillContentPayload(request.skillPath(), ""));
            }
        });
    }

    private void handleSaveSkillContent(BridgeContext ctx, SkillRequests.SaveSkillContentRequest request) {
        ctx.asyncExecutor().submit(() -> {
            try {
                boolean success = ctx.skillsConfigService().saveSkillContent(request.skillPath(), request.content());
                ctx.invokeJSCallback(JsCallback.SKILL_CONTENT_SAVED,
                        new CallbackPayloads.SaveContentResultPayload(success, request.skillPath()));
            } catch (Exception e) {
                ctx.invokeJSCallback(JsCallback.SKILL_CONTENT_SAVED,
                        new CallbackPayloads.SaveContentResultPayload(false, request.skillPath()));
            }
        });
    }

    private void handleListKnownRepos(BridgeContext ctx, SkillRequests.ListKnownReposRequest request) {
        ctx.asyncExecutor().submit(() -> {
            try {
                var repos = ctx.skillsConfigService().listKnownRepos(request.cliType());
                ctx.invokeJSCallback(JsCallback.KNOWN_REPOS, repos);
            } catch (Exception e) {
                ctx.invokeJSCallback(JsCallback.KNOWN_REPOS, List.of());
            }
        });
    }

    private void handleListRemoteSkills(BridgeContext ctx, SkillRequests.ListRemoteSkillsRequest request) {
        ctx.asyncExecutor().submit(() -> {
            try {
                var skills = ctx.skillsConfigService().listRemoteSkills(request.ownerRepo());
                ctx.invokeJSCallback(JsCallback.REMOTE_SKILLS, skills);
            } catch (Exception e) {
                ctx.invokeJSCallback(JsCallback.REMOTE_SKILLS, List.of());
            }
        });
    }
}
