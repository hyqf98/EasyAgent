package io.github.easyagent.settings.mcp;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

class McpConfigRoundTripTest {

    @Test
    void shouldLoadRealCodexConfigWithArgsAndEnv() {
        Path codexConfig = Path.of(System.getProperty("user.home"), ".codex", "config.toml");
        assumeThat(Files.exists(codexConfig)).isTrue();

        McpConfigService service = new McpConfigService();
        List<McpServerEntry> entries = service.loadMcpConfigs("CODEX", null);
        System.out.println("Codex MCP servers: " + entries.size());
        for (McpServerEntry e : entries) {
            System.out.println("  " + e.name() + ": cmd=" + e.command() + " args=" + e.args() + " env=" + e.env());
        }

        McpServerEntry easyDb = entries.stream()
                .filter(e -> "easy_db_mcp_server".equals(e.name()))
                .findFirst().orElse(null);
        assertThat(easyDb).isNotNull();
        assertThat(easyDb.command()).isEqualTo("npx");
        assertThat(easyDb.args()).anyMatch(a -> a.contains("easy_db_mcp_server"));
        assertThat(easyDb.env()).containsEntry("EASYDB_HOST", "mysql.dev.zhxx.site");
        assertThat(easyDb.env()).containsEntry("EASYDB_PASSWORD", "zhxx@123456");

        McpServerEntry playwright = entries.stream()
                .filter(e -> "playwright".equals(e.name()))
                .findFirst().orElse(null);
        assertThat(playwright).isNotNull();
        assertThat(playwright.command()).isEqualTo("npx");
        assertThat(playwright.args()).anyMatch(a -> a.contains("playwright"));
    }

    @Test
    void shouldLoadRealOpenCodeConfigWithEnvAndHeaders() {
        Path openCodeConfig = Path.of(System.getProperty("user.home"), ".config", "opencode", "opencode.json");
        assumeThat(Files.exists(openCodeConfig)).isTrue();

        McpConfigService service = new McpConfigService();
        List<McpServerEntry> entries = service.loadMcpConfigs("OPENCODE", null);
        System.out.println("OpenCode MCP servers: " + entries.size());
        for (McpServerEntry e : entries) {
            System.out.println("  " + e.name() + ": type=" + e.type() + " cmd=" + e.command() + " args=" + e.args() + " env=" + e.env() + " url=" + e.url());
        }

        McpServerEntry easyDb = entries.stream()
                .filter(e -> "easy_db_mcp_server".equals(e.name()))
                .findFirst().orElse(null);
        assertThat(easyDb).isNotNull();
        assertThat(easyDb.command()).isEqualTo("npx");
        assertThat(easyDb.args()).anyMatch(a -> a.contains("easy_db_mcp_server"));
        assertThat(easyDb.env()).containsEntry("EASYDB_HOST", "mysql.dev.zhxx.site");

        McpServerEntry webReader = entries.stream()
                .filter(e -> "web-reader".equals(e.name()))
                .findFirst().orElse(null);
        if (webReader != null) {
            assertThat(webReader.type()).isEqualTo("http");
            assertThat(webReader.url()).isNotEmpty();
            assertThat(webReader.env()).containsKey("Authorization");
            System.out.println("web-reader headers loaded as env: " + webReader.env());
        }

        McpServerEntry gitnexus = entries.stream()
                .filter(e -> "gitnexus".equals(e.name()))
                .findFirst().orElse(null);
        if (gitnexus != null) {
            assertThat(gitnexus.command()).isEqualTo("/opt/homebrew/bin/gitnexus");
            assertThat(gitnexus.args()).containsExactly("mcp");
            System.out.println("gitnexus parsed correctly: cmd=" + gitnexus.command() + " args=" + gitnexus.args());
        }
    }
}
