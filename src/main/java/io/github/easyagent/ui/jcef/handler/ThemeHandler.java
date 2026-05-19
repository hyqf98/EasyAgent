package io.github.easyagent.ui.jcef.handler;

import io.github.easyagent.ui.enums.JsAction;
import io.github.easyagent.ui.enums.JsCallback;
import io.github.easyagent.ui.enums.ThemeType;
import io.github.easyagent.ui.jcef.dto.CallbackPayloads;
import io.github.easyagent.ui.jcef.dto.CommonRequests;

import javax.swing.UIManager;
import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主题处理 handler，负责从 IDE LaF 颜色推导 CSS 变量并推送到前端。
 *
 * @author haijun
 * @date 2026/5/18
 * @since 1.1.0
 */
public class ThemeHandler implements MessageHandler {

    @Override
    public void register(BridgeContext ctx, Map<JsAction, QueryHandlerRecord<?>> handlers) {
        ctx.registerHandler(handlers, JsAction.GET_THEME, CommonRequests.ActionRequest.class,
                request -> this.sendThemeUpdate(ctx));
    }

    /**
     * 同步当前 IDE 主题到前端。
     *
     * @param ctx bridge 上下文
     */
    public void sendThemeUpdate(BridgeContext ctx) {
        String lafName = UIManager.getLookAndFeel() != null
                ? UIManager.getLookAndFeel().getName() : "";

        Color panelBg = safeColor("Panel.background", 0xFFFFFF);
        Color labelFg = safeColor("Label.foreground", 0x000000);
        Color inputBg = safeColor("TextField.background", 0xFFFFFF);
        Color border = safeColor("Separator.foreground", safeColor("Component.borderColor", 0xE0E0E0));
        Color disabledFg = safeColor("Label.disabledForeground", null);
        Color accent = safeColor("List.selectionBackground", 0x4F7CFF);

        boolean isDark = ThemeType.fromUiColor(panelBg, lafName).isDark();
        if (isDark) {
            border = fixDarkBorder(border, panelBg);
        }
        Map<String, String> colors = buildThemeColors(panelBg, labelFg, inputBg, border,
                disabledFg, accent, isDark);

        ctx.invokeJSCallback(JsCallback.THEME_CHANGED, new CallbackPayloads.ThemePayload(isDark, colors));
    }

    private static Map<String, String> buildThemeColors(
            Color bg, Color fg, Color inputBg,
            Color border, Color disabledFg, Color accent, boolean isDark) {

        LinkedHashMap<String, String> c = new LinkedHashMap<>();
        Color tintTarget = isDark ? Color.WHITE : Color.BLACK;

        c.put("--ea-bg", toHex(bg));
        c.put("--ea-text", toHex(fg));
        c.put("--ea-border", toHex(border));
        c.put("--ea-border-light", toHex(blend(border, tintTarget, isDark ? 0.15f : 0.15f)));

        c.put("--ea-bg-secondary", toHex(blend(bg, tintTarget, isDark ? 0.04f : 0.03f)));
        c.put("--ea-bg-hover", toHex(blend(bg, tintTarget, isDark ? 0.08f : 0.06f)));
        c.put("--ea-bg-active", toHex(blend(bg, tintTarget, isDark ? 0.15f : 0.12f)));

        if (disabledFg != null) {
            c.put("--ea-text-secondary", toHex(isDark ? blend(fg, bg, 0.20f) : disabledFg));
        } else {
            c.put("--ea-text-secondary", toHex(blend(fg, bg, isDark ? 0.20f : 0.45f)));
        }
        c.put("--ea-text-muted", toHex(blend(fg, bg, isDark ? 0.30f : 0.60f)));

        c.put("--ea-input-bg", toHex(inputBg));
        c.put("--ea-input-border", toHex(border));
        c.put("--ea-input-focus", toHex(accent));

        c.put("--ea-user-bubble", toHex(blend(bg, tintTarget, isDark ? 0.08f : 0.04f)));
        c.put("--ea-ai-bubble", "transparent");

        Color yellow = new Color(0xFF, 0xFB, 0xEB);
        c.put("--ea-thinking-bg", toHex(blend(bg, yellow, isDark ? 0.10f : 0.08f)));
        c.put("--ea-thinking-border", toHex(blend(border, new Color(0xFD, 0xE6, 0x8A), 0.50f)));

        c.put("--ea-tool-bg", toHex(blend(bg, tintTarget, isDark ? 0.03f : 0.02f)));
        c.put("--ea-tool-border", toHex(blend(border, tintTarget, isDark ? 0.05f : 0.04f)));

        Color red = isDark ? new Color(0x2A, 0x15, 0x18) : new Color(0xFE, 0xF2, 0xF2);
        c.put("--ea-error-bg", toHex(blend(bg, red, 0.15f)));
        c.put("--ea-error-border", toHex(blend(border, new Color(0xFE, 0xCA, 0xCA), 0.40f)));

        c.put("--ea-code-bg", toHex(blend(bg, tintTarget, isDark ? 0.03f : 0.03f)));

        c.put("--ea-header-bg", String.format("rgba(%d,%d,%d,0.92)",
                bg.getRed(), bg.getGreen(), bg.getBlue()));
        c.put("--ea-popup-bg", toHex(blend(bg, tintTarget, isDark ? 0.05f : 0.01f)));
        c.put("--ea-popup-shadow", isDark
                ? "0 8px 30px rgba(0,0,0,0.4)" : "0 8px 30px rgba(0,0,0,0.12)");

        c.put("--ea-scrollbar", toHex(blend(border, tintTarget, isDark ? 0.15f : 0.15f)));
        c.put("--ea-scrollbar-hover", toHex(blend(fg, bg, 0.30f)));
        c.put("--ea-icon-color", toHex(blend(fg, bg, isDark ? 0.25f : 0.40f)));

        c.put("--ea-markdown-link", toHex(blend(accent, fg, 0.30f)));
        c.put("--ea-markdown-code-bg", toHex(blend(bg, tintTarget, isDark ? 0.03f : 0.03f)));
        c.put("--ea-markdown-blockquote-bg", toHex(blend(bg, tintTarget, isDark ? 0.04f : 0.02f)));
        c.put("--ea-table-header-bg", toHex(blend(bg, tintTarget, isDark ? 0.04f : 0.03f)));
        c.put("--ea-table-even-bg", toHex(blend(bg, tintTarget, isDark ? 0.03f : 0.02f)));

        c.put("--ea-accent", toHex(accent));
        c.put("--ea-accent-hover", toHex(blend(accent, Color.WHITE, 0.15f)));

        return c;
    }

    private static Color safeColor(String key, int defaultRgb) {
        Color c = UIManager.getColor(key);
        return c != null ? c : new Color(defaultRgb);
    }

    private static Color safeColor(String key, Color fallback) {
        Color c = UIManager.getColor(key);
        return c != null ? c : fallback;
    }

    private static String toHex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static Color blend(Color a, Color b, float t) {
        return new Color(
                Math.min(255, Math.max(0, Math.round(a.getRed() * (1 - t) + b.getRed() * t))),
                Math.min(255, Math.max(0, Math.round(a.getGreen() * (1 - t) + b.getGreen() * t))),
                Math.min(255, Math.max(0, Math.round(a.getBlue() * (1 - t) + b.getBlue() * t)))
        );
    }

    private static float luminance(Color c) {
        return (0.299f * c.getRed() + 0.587f * c.getGreen() + 0.114f * c.getBlue()) / 255.0f;
    }

    private static Color fixDarkBorder(Color border, Color bg) {
        float borderLum = luminance(border);
        float bgLum = luminance(bg);
        if (borderLum - bgLum > 0.25f) {
            return blend(bg, Color.WHITE, 0.18f);
        }
        return border;
    }
}
