package io.github.preschoolller.agentjava.backend.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.preschoolller.agentjava.backend.constant.Constants;
import io.github.preschoolller.agentjava.backend.provider.common.ToolApprovalGate;
import io.github.preschoolller.agentjava.backend.tool.interfaces.NeedAdditionalApproval;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.github.preschoolller.agentjava.backend.constant.Constants.MAPPER;

/**
 * 读文件工具
 * path: 工作区相对路径
 * offset: 文件起始行数，默认从头开始
 * limit: 总读取数，默认2000
 * <p>
 * 规则：默认只能读取工作区目录下的文件，无工作区则以默认工作区目录为基准。
 * 若是绝对路径，或者是路径穿越出工作区外，发起审批流程。(待设计)
 */
public class ToolRead implements Tool {

    private static final String PATH = "path";
    private static final String OFFSET = "offset";
    private static final String LIMIT = "limit";
    private static final int DEFAULT_LIMIT = 2000;

    private static volatile ToolRead instance;

    private ToolRead() {
    }

    public static ToolRead getInstance() {
        if (instance == null) {
            synchronized (ToolRead.class) {
                if (instance == null) {
                    instance = new ToolRead();
                }
            }
        }
        return instance;
    }

    @Override
    public String name() {
        return "read";
    }

    @Override
    public String description() {
        return "读取文本文件内容（相对路径按当前工作区解析），非必要不要超出工作区，若是超出工作区则视情况需要用户审批，较为耗时";
    }

    @Override
    public boolean readOnly() {
        return true; // 只读文件，无副作用：编辑模式下不用确认
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        PATH, Map.of("type", "string", "description", "文件路径，相对路径以当前工作区为基准"),
                        OFFSET, Map.of("type", "integer", "description", "起始行数，默认第一行开始"),
                        LIMIT, Map.of("type", "integer", "description", "总限制行数，默认2000，最大2000")
                ),
                "required", List.of(PATH)
        );
    }

    @Override
    public String execute(String arguments, String workspace, NeedAdditionalApproval approvalGate) {
        try {
            JsonNode jsonNode = MAPPER.readTree(arguments);
            if (jsonNode == null) {
                return "json parse error: wrong schema";
            }
            String path = jsonNode.path(PATH).asText();
            if (path == null || path.isBlank()) {
                return "json parse error: wrong path";
            }
            int offset = Math.max(jsonNode.path(OFFSET).asInt(1), 1);
            int limit = Math.min(jsonNode.path(LIMIT).asInt(DEFAULT_LIMIT), DEFAULT_LIMIT);
            limit = limit < 0 ? DEFAULT_LIMIT : limit;

            Path target = Path.of(path);
            Path workSpaceDir = workspace == null || workspace.isBlank() ?
                    Constants.DEFAULT_WORKSPACE_DIR : Path.of(workspace);
            target = workSpaceDir.resolve(target).normalize();
            if (!target.startsWith(workSpaceDir) && !requestApproval(approvalGate, false)) {
                return ToolApprovalGate.DENIED_RESULT;
            }
            requestApproval(approvalGate, true);
            StringBuilder result = new StringBuilder();
            if (Files.isDirectory(target)) {
                result.append("requesting path is not a regular file.");
            } else {
                try (BufferedReader br = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
                    int cur = 1;
                    int excludeBoundary = offset + limit;
                    String line;
                    // TODO 或许会有超长单行OOM或者打爆上下文？
                    while (cur < excludeBoundary && (line = br.readLine()) != null) {
                        if (cur < offset) {
                            cur++;
                            continue;
                        }
                        result.append(cur).append(":").append(line).append("\n");
                        cur++;
                    }
                    if (cur != excludeBoundary) {
                        result.append("no more lines since ").append(cur);
                    }
                }
            }
            return result.toString();
        } catch (JsonProcessingException e) {
            return "json parse error: " + e.getMessage();
        } catch (IOException e) {
            return "file read error: " + e.getMessage();
        } finally {
            requestApproval(approvalGate, true);
        }
    }

    @Override
    public boolean needAdditionalApproval() {
        return true;
    }

    /**
     * noMore 为 true 停止审批门禁阻塞，外接开始超时等待工具执行完成。
     *
     * @return 审批是否通过，无门禁直接失败
     */
    private boolean requestApproval(NeedAdditionalApproval gate, boolean noMore) {
        if (gate == null) {
            return false;
        }
        return gate.requestApproval(noMore);
    }
}
