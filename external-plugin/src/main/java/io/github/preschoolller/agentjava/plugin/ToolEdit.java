package io.github.preschoolller.agentjava.plugin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.preschoolller.agentjava.backend.constant.Constants;
import io.github.preschoolller.agentjava.backend.provider.common.ToolApprovalGate;
import io.github.preschoolller.agentjava.backend.tool.interfaces.NeedAdditionalApproval;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;
import org.tinylog.Logger;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件编辑工具，放在插件里是要学习服务加载
 */
public class ToolEdit implements Tool {

    private static final String PATH = "path";
    private static final String OLD_STRING = "old_string";
    private static final String NEW_STRING = "new_string";
    private static final String MODE = "mode";

    public ToolEdit() {
    }

    @Override
    public String name() {
        return "edit";
    }

    @Override
    public String description() {
        return "编辑文件，旧文本必须为整行匹配，字符串头部若有换行符会被忽略";
    }

    @Override
    public Map<String, Object> schema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        PATH, Map.of("type", "string", "description", "文件路径，相对路径以当前工作区为基准"),
                        OLD_STRING, Map.of("type", "string", "description", "旧文本"),
                        NEW_STRING, Map.of("type", "string", "description", "新文本"),
                        MODE, Map.of("type", "integer", "description", "0:仅新增新文本（文末）；1:仅删除旧文本；2:替换文本（默认，要求旧文本不能为空）；3:替换所有旧文本（若没有多处要改建议用2，省去多余检查）")
                ),
                "required", List.of(PATH, OLD_STRING, NEW_STRING, MODE)
        );
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public boolean needAdditionalApproval() {
        return true;
    }

    @Override
    public String execute(String arguments, String workspace, NeedAdditionalApproval gate) {
        ParsedArgs args = parse(arguments);
        if (args.errorMessage != null) {
            return args.errorMessage;
        }
        return edit(args, workspace, gate);
    }

    private String edit(ParsedArgs args, String workspace, NeedAdditionalApproval gate) {
        Path workDir = workspace == null || workspace.isBlank() ?
                Constants.DEFAULT_WORKSPACE_DIR : Path.of(workspace).toAbsolutePath().normalize();
        Path oldFile = workDir.resolve(args.path);
        if (!oldFile.toAbsolutePath().normalize().startsWith(workDir) && !requestApproval(gate, false)) {
            return ToolApprovalGate.DENIED_RESULT;
        }
        requestApproval(gate, true);
        String tmpName = UUID.randomUUID().toString();
        Path tmpFile = oldFile.resolveSibling(tmpName);
        try {
            switch (args.mode) {
                case APPEND -> {
                    try {
                        Files.copy(oldFile, tmpFile);
                        Files.writeString(tmpFile, args.newString, StandardOpenOption.APPEND);
                        Files.move(tmpFile, oldFile, StandardCopyOption.ATOMIC_MOVE);
                    } catch (IOException e) {
                        Logger.error(e, "append file {} failed", oldFile);
                        return "(edit error: append failed)";
                    }
                }
                case DELETE -> {
                    try {
                        String[] searchLines = args.oldString.split("\\R");
                        int searchPointer = 0;
                        long startPointer = 0; // 命中旧文本的行起点
                        long endPointer = 0; // 命中旧文本的下一行需保留的行起点
                        boolean found = false;
                        try (RandomAccessFile old = new RandomAccessFile(oldFile.toFile(), "r")) {
                            String line;
                            long filePointer = old.getFilePointer();
                            while ((line = old.readLine()) != null) {
                                line = new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                                if (line.equals(searchLines[searchPointer])) {
                                    if (searchPointer == 0) { // 命中首行，记录
                                        startPointer = filePointer;
                                    }
                                    if (++searchPointer == searchLines.length) { // 命中末行，记录
                                        endPointer = old.getFilePointer();
                                        found = true;
                                        break;
                                    }
                                } else if (line.equals(searchLines[0])) {
                                    // 中间行匹配失败，但首行又匹配成功了，记录
                                    startPointer = filePointer;
                                    searchPointer = 1;
                                } else {
                                    // 中间行匹配失败，重置
                                    searchPointer = 0;
                                }
                            }
                            if (searchPointer == 0 || !found) {
                                Logger.info("delete old string {} failed, not found in file {}", args.oldString, oldFile);
                                return "(edit error: no " + OLD_STRING + " found in the file)";
                            }
                        }
                        try (FileChannel oldChannel = FileChannel.open(oldFile, StandardOpenOption.READ);
                             FileChannel tmpChannel = FileChannel.open(tmpFile, StandardOpenOption.WRITE)) {
                            ensureTransfer(oldChannel, 0, startPointer, tmpChannel);
                            ensureTransfer(oldChannel, endPointer, oldChannel.size() - endPointer, tmpChannel);
                        }
                        Files.move(tmpFile, oldFile, StandardCopyOption.ATOMIC_MOVE);
                        Logger.info("edit file {} success", oldFile);
                        return "(edit success)";
                    } catch (IOException e) {
                        Logger.error(e, "edit file {} failed", oldFile);
                        return "(edit error: delete old string failed)";
                    }
                }
                case REPLACE, REPLACE_ALL -> {

                }
                default -> {}
            }
        } finally {
            try {
                Files.deleteIfExists(tmpFile);
            } catch (IOException e) {
                Logger.error(e, "tmp file {} delete failed", tmpFile);
            }
        }
        return null;
    }

    private static void ensureTransfer(FileChannel src, long start, long count, FileChannel dst) throws IOException {
        long max = start + count;
        long pos = start;
        while (pos < max) {
            long transferred = src.transferTo(pos, max - pos, dst);
            if (transferred <= 0) {
                break;
            }
            pos += transferred;
        }
    }

    private boolean requestApproval(NeedAdditionalApproval gate, boolean noMore) {
        if (gate == null) {
            return false;
        }
        return gate.requestApproval(noMore);
    }

    private ParsedArgs parse(String arguments) {
        JsonNode jsonNode;
        try {
            jsonNode = Constants.getObjectMapper().readTree(arguments);
        } catch (JsonProcessingException e) {
            return new ParsedArgs(null, null, null, "json parse error: " + e.getMessage(), null);
        }
        if (jsonNode == null) {
            return new ParsedArgs(null, null, null, "json parse error: wrong schema", null);
        }
        String path = jsonNode.path(PATH).asText();
        if (path == null || path.isBlank()) {
            return new ParsedArgs(null, null, null, "json parse error: wrong path", null);
        }
        String oldString = jsonNode.path(OLD_STRING).asText();
        String newString = jsonNode.path(NEW_STRING).asText();
        Mode mode = null;
        switch (mode = Mode.ofValue(jsonNode.path(MODE).asInt(2))) {
            case APPEND -> {
                if (newString == null || newString.isBlank()) {
                    return new ParsedArgs(null, null, null, "did nothing cause of blank " + NEW_STRING, null);
                }
                return new ParsedArgs(path, oldString, newString, null, mode);
            }
            case DELETE, REPLACE, REPLACE_ALL -> {
                if (oldString == null || oldString.isBlank()) {
                    return new ParsedArgs(null, null, null, "did nothing cause of blank " + OLD_STRING, null);
                }
                return new ParsedArgs(path, oldString, newString, null, mode);
            }
            default -> {
                return new ParsedArgs(null, null, null, "mode required, optional values [0,1,2,3]", null);
            }
        }
    }

    private enum Mode {
        APPEND(0),
        DELETE(1),
        REPLACE(2),
        REPLACE_ALL(3),
        ;

        private final int value;

        Mode(int i) {
            value = i;
        }

        private static Mode ofValue(int value) {
            for (Mode mode : values()) {
                if (value == mode.value) {
                    return mode;
                }
            }
            return REPLACE;
        }
    }

    private record ParsedArgs(String path, String oldString, String newString, String errorMessage, Mode mode) {}
}
