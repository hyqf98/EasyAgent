package io.github.easyagent.session.reader;

import io.github.easyagent.enums.ContentBlockType;
import io.github.easyagent.enums.SessionRole;
import io.github.easyagent.session.entity.ContentBlock;
import io.github.easyagent.session.entity.SessionMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ClaudeSessionReader 单元测试。
 *
 * @author haijun
 * @date 2026/5/8
 * @since 1.0.0
 */
class ClaudeSessionReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldMergeClaudeToolResultRowsIntoAssistantMessages() throws Exception {
        Path projectsDir = tempDir.resolve(".claude").resolve("projects");
        Path projectDir = projectsDir.resolve("-Users-haijun-Work-frontend-demo");
        Files.createDirectories(projectDir);

        Path sessionFile = projectDir.resolve("b2a108f9-ec2c-43bc-bbe3-e243af3e7987.jsonl");
        Files.writeString(sessionFile, String.join(System.lineSeparator(),
                "{\"type\":\"user\",\"uuid\":\"u-1\",\"message\":{\"role\":\"user\",\"content\":\"你现在重新编辑一下\"},\"timestamp\":\"2026-05-08T05:18:42.217Z\",\"cwd\":\"/Users/haijun/Work/frontend/demo\"}",
                "{\"type\":\"assistant\",\"uuid\":\"a-1\",\"parentUuid\":\"u-1\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"call_read\",\"name\":\"Read\",\"input\":{\"file_path\":\"/Users/haijun/Work/frontend/demo/demo.txt\"}}]},\"timestamp\":\"2026-05-08T05:18:50.197Z\",\"cwd\":\"/Users/haijun/Work/frontend/demo\"}",
                "{\"type\":\"user\",\"uuid\":\"t-1\",\"parentUuid\":\"a-1\",\"message\":{\"role\":\"user\",\"content\":[{\"tool_use_id\":\"call_read\",\"type\":\"tool_result\",\"content\":\"The file /Users/haijun/Work/frontend/demo/demo.txt has been updated successfully.\"}]},\"timestamp\":\"2026-05-08T05:18:50.233Z\",\"cwd\":\"/Users/haijun/Work/frontend/demo\"}",
                "{\"type\":\"assistant\",\"uuid\":\"a-2\",\"parentUuid\":\"a-1\",\"message\":{\"role\":\"assistant\",\"content\":[{\"type\":\"tool_use\",\"id\":\"call_edit\",\"name\":\"Edit\",\"input\":{\"file_path\":\"/Users/haijun/Work/frontend/demo/demo.txt\",\"old_string\":\"env: local\",\"new_string\":\"env: development\"}}]},\"timestamp\":\"2026-05-08T05:18:56.259Z\",\"cwd\":\"/Users/haijun/Work/frontend/demo\"}",
                "{\"type\":\"user\",\"uuid\":\"t-2\",\"parentUuid\":\"a-2\",\"message\":{\"role\":\"user\",\"content\":[{\"tool_use_id\":\"call_edit\",\"type\":\"tool_result\",\"content\":\"The file /Users/haijun/Work/frontend/demo/demo.txt has been updated successfully.\"}]},\"toolUseResult\":{\"filePath\":\"/Users/haijun/Work/frontend/demo/demo.txt\",\"oldString\":\"env: local\",\"newString\":\"env: development\",\"originalFile\":\"Start learning\\nuser_id: 1001\\nproject: demo\\nstatus: testing\\nnote: sample data for testing\\ncreated_at: 2026-04-27\\nenv: local\\nversion: 1.0.0\\nowner: haijun\\nrandom_note: midnight coffee, blue keyboard, quiet rain\\ntemp_tag: fox-482-lantern\\nmemo: testing a loose block of random text in demo.txt\\nsession_id: 73a1-breeze\\ndebug_seed: 48192\\nextra_note: orange lamp, silent tab, pixel dust\\nqueue_hint: river-cat-17\\n\",\"replaceAll\":false},\"timestamp\":\"2026-05-08T05:18:56.279Z\",\"cwd\":\"/Users/haijun/Work/frontend/demo\"}",
                "{\"type\":\"assistant\",\"uuid\":\"a-3\",\"parentUuid\":\"t-2\",\"message\":{\"role\":\"assistant\",\"content\":\"已将第 7 行 env: local 改为 env: development，可以继续测试追踪。\"},\"timestamp\":\"2026-05-08T05:19:01.774Z\",\"cwd\":\"/Users/haijun/Work/frontend/demo\"}"
        ));

        ClaudeSessionReader reader = new ClaudeSessionReader(projectsDir.toString());
        List<SessionMessage> messages = reader.readMessages("b2a108f9-ec2c-43bc-bbe3-e243af3e7987");

        assertThat(messages).hasSize(4);
        assertThat(messages.get(0).role()).isEqualTo(SessionRole.USER);
        assertThat(messages.get(0).contents()).hasSize(1);
        assertThat(messages.get(0).contents().get(0).type()).isEqualTo(ContentBlockType.TEXT);

        assertThat(messages.get(1).role()).isEqualTo(SessionRole.ASSISTANT);
        assertThat(messages.get(1).contents()).extracting(ContentBlock::type)
                .containsExactly(ContentBlockType.TOOL_USE, ContentBlockType.TOOL_RESULT);
        assertThat(messages.get(1).contents().get(1).toolOutput())
                .contains("The file /Users/haijun/Work/frontend/demo/demo.txt has been updated successfully.");

        assertThat(messages.get(2).role()).isEqualTo(SessionRole.ASSISTANT);
        assertThat(messages.get(2).contents()).extracting(ContentBlock::type)
                .containsExactly(ContentBlockType.TOOL_USE, ContentBlockType.TOOL_RESULT);
        assertThat(messages.get(2).contents().get(1).historicalFileEditData()).isNotNull();
        assertThat(messages.get(2).contents().get(1).historicalFileEditData().originalFile())
                .contains("env: local");

        assertThat(messages.get(3).role()).isEqualTo(SessionRole.ASSISTANT);
        assertThat(messages.get(3).contents()).hasSize(1);
        assertThat(messages.get(3).contents().get(0).text())
                .contains("已将第 7 行 env: local 改为 env: development");
    }
}
