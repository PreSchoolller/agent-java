package io.github.preschoolller.agentjava.plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 起进程跑 node bili.mjs <命令> [参数...]。
 *
 * 不经过 shell——参数直接进 ProcessBuilder，模型给的参数无论长什么样都不存在注入面；
 * 输出合并 stderr 后整体回给模型（超长截尾），超时强杀并如实报告。
 */
final class BiliRunner {

    /** 输出长度帽（字符）：debrief/评论类输出可能很长，模型上下文才是稀缺资源 */
    private static final int OUTPUT_CAP = 16000;
    private static final int DEFAULT_TIMEOUT_SEC = 120;
    private static final int MAX_TIMEOUT_SEC = 900;

    private BiliRunner() {
    }

    static int clampTimeout(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_TIMEOUT_SEC;
        }
        return Math.min(requested, MAX_TIMEOUT_SEC);
    }

    /** @param cmdArgs bili.mjs 之后的命令与参数（command 已经过白名单校验） */
    static String run(List<String> cmdArgs, int timeoutSec) {
        BiliLocator.Entry entry = BiliLocator.resolveEntry();
        if (!entry.ok()) {
            return entry.error();
        }
        String node = BiliLocator.resolveNode();
        if (node == null) {
            return """
                    未找到可用的 node 运行时（bili 工具链全部是 .mjs 脚本，需要 Node 18+）。
                    解决方式：装 Node 并确保在 PATH 里，或设置环境变量 BILI_NODE 指向 node 可执行文件。""";
        }
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add(node);
        cmd.add(entry.biliMjs().toString());
        cmd.addAll(cmdArgs);

        Process process;
        try {
            process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (IOException e) {
            BiliLocator.invalidateNode(); // 缓存的 node 可能已失效，下次重探
            return "(bili error: 进程启动失败 " + e.getMessage() + " —— node 路径可能已失效，已重置探测缓存)";
        }

        StringBuilder out = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[2048];
                int n;
                while ((n = r.read(buf)) != -1) {
                    if (out.length() < OUTPUT_CAP) {
                        out.append(buf, 0, n);
                    }
                }
            } catch (IOException ignored) {
                // 进程被杀时流的读取会以异常收尾，输出有多少算多少
            }
        });
        reader.setDaemon(true);
        reader.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return "(bili error: 等待被中断，进程已终止)";
        }
        if (!finished) {
            process.destroyForcibly();
            try {
                reader.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "(bili error: 超过 " + timeoutSec + " 秒未结束，进程已强杀。长时间命令（watch/debrief/watch-video）"
                    + "可把 timeout_sec 调大（上限 " + MAX_TIMEOUT_SEC + "）或改用对应的 --minutes 参数控制时长)\n"
                    + tail(out);
        }
        try {
            reader.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        int code = process.exitValue();
        String text = tail(out);
        if (code != 0) {
            text = "(bili exit " + code + ")\n" + text;
        }
        return text;
    }

    private static String tail(StringBuilder out) {
        if (out.length() <= OUTPUT_CAP) {
            return out.toString();
        }
        return "(输出过长，只保留末尾 " + OUTPUT_CAP + " 字符，完整结果请用更窄的参数重跑)\n"
                + out.substring(out.length() - OUTPUT_CAP);
    }
}
