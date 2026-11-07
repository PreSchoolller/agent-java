package io.github.preschoolller.agentjava.additional;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * ToolBash 逻辑抽离探针：逐字复刻 tryBash / assembleProcessBuilder 的进程形状，
 * 用于配合 wsl 启停实测定位“WSL 慢/误判 git bash”。
 *
 * 用法：
 *   java WslProbe probe <次数> <超时秒>          # 复刻 tryBash(List.of("wsl","bash"), timeout)
 *   java WslProbe exec <工作目录> <次数>         # 复刻 execute：wsl bash -c "echo b64 | base64 -d | bash"
 */
public class WslProbe {

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        switch (mode) {
            case "probe" -> {
                int n = Integer.parseInt(args[1]);
                int timeoutSec = Integer.parseInt(args[2]);
                for (int i = 0; i < n; i++) {
                    System.out.println("probe#" + (i + 1) + " budget=" + timeoutSec + "s -> " + probe(timeoutSec));
                }
            }
            case "exec" -> {
                String cwd = args[1];
                int n = Integer.parseInt(args[2]);
                for (int i = 0; i < n; i++) {
                    System.out.println("exec#" + (i + 1) + " cwd=" + cwd + " -> " + exec(cwd));
                }
            }
            default -> System.out.println("unknown mode: " + mode);
        }
    }

    /** 逐字复刻 ToolBash.tryBash：head+--version、WSL_UTF8=1、合并 stderr、
     *  超时 destroyForcibly、输出含 "gnu bash"（忽略大小写）才判可用 */
    static String probe(int timeoutSec) throws Exception {
        long t0 = System.nanoTime();
        List<String> list = List.of("wsl", "bash", "--version");
        ProcessBuilder pb = new ProcessBuilder(list);
        pb.environment().put("WSL_UTF8", "1");
        pb.redirectErrorStream(true);
        String out = null;
        boolean exited = false;
        try {
            Process p = pb.start();
            ScheduledExecutorService killer = Executors.newSingleThreadScheduledExecutor();
            killer.schedule(p::destroyForcibly, timeoutSec, TimeUnit.SECONDS);
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = r.lines().collect(Collectors.joining("\n"));
            }
            exited = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
            }
            killer.shutdownNow();
        } catch (Exception e) {
            return "EXCEPTION " + e.getClass().getSimpleName() + " " + elapsed(t0);
        }
        boolean ok = exited && out != null && out.toLowerCase().contains("gnu bash");
        return (ok ? "OK " : "FAIL ") + elapsed(t0) + " exited=" + exited
                + " out=" + head(out, 60);
    }

    /** 复刻 execute 的进程形状：wsl bash -c "echo <b64> | base64 -d | bash"、
     *  directory(工作目录)（wsl.exe 子进程的 Windows cwd = workspace）、WSL_UTF8=1 */
    static String exec(String cwd) throws Exception {
        long t0 = System.nanoTime();
        // base64("echo hi") = ZWNobyBoaQ==，展开后应输出 hi
        String b64 = "ZWNobyBoaQ==";
        ProcessBuilder pb = new ProcessBuilder("wsl", "bash", "-c",
                "echo " + b64 + " | base64 -d | bash");
        pb.directory(new File(cwd));
        pb.environment().put("WSL_UTF8", "1");
        pb.redirectErrorStream(true);
        String out;
        try {
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = r.lines().collect(Collectors.joining("\n"));
            }
            boolean exited = p.waitFor(60, TimeUnit.SECONDS);
            boolean ok = exited && out.contains("hi");
            return (ok ? "OK " : "FAIL ") + elapsed(t0) + " out=" + head(out, 60);
        } catch (Exception e) {
            return "EXCEPTION " + e.getClass().getSimpleName() + " " + elapsed(t0);
        }
    }

    static String elapsed(long t0) {
        return (System.nanoTime() - t0) / 1_000_000 + "ms";
    }

    static String head(String s, int max) {
        if (s == null || s.isBlank()) {
            return "(empty)";
        }
        String one = s.replace("\n", " | ").replace("\r", "");
        return one.length() <= max ? one : one.substring(0, max) + "...";
    }
}
