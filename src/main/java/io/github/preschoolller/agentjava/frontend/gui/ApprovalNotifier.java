package io.github.preschoolller.agentjava.frontend.gui;

import io.github.preschoolller.agentjava.frontend.store.AppSettings;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.swing.JFrame;
import javax.swing.Timer;
import java.awt.Toolkit;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 审核（工具审批）到来时的提醒（计划外，用户需求）：按设置里的三个开关各自动作——
 * 系统提示音 / 托盘气泡系统通知 / 窗口抢到前台。审批入口在 Window.ToolCallHandle.requestApproval
 * （EDT 上），提醒也只在那一刻发一次，用户点了允许/拒绝、或停止清理走 finish 判否都不再响。
 * 三通道相互独立，失败各自静默（无托盘/无声卡的机器缺一两个通道不给整套提醒添乱）。
 */
final class ApprovalNotifier {

    private ApprovalNotifier() {
    }

    /**
     * 审核到来：读偏好依次走启用的通道。须在 EDT 上调用（抢前台与托盘气泡都是 UI 操作；
     * 提示音单独放后台线程，对齐踩坑 #1：加载 wav 的 IO 不占 EDT）。
     */
    static void announce(JFrame window, String toolName, String argsSummary) {
        AppSettings settings = AppSettings.get();
        if (settings.isApprovalSound()) {
            playSound();
        }
        if (settings.isApprovalSystemNotify() && window instanceof Window appWindow) {
            appWindow.showTrayNotification("审核请求",
                    toolName + (argsSummary.isEmpty() ? "" : "：" + argsSummary));
        }
        if (settings.isApprovalBringToFront()) {
            bringToFront(window);
        }
    }

    /**
     * 系统提示音：优先播 Windows 通知音（认得出"来事了"的那种），依次尝试 Win11/Win10 与
     * 更旧的气球通知音；文件不存在（非 Windows）或按到一半失败（无声卡/解码不了）回落
     * Toolkit.beep（系统默认哔声）。播放在守护线程：加载与 Clip 打开有 IO，EDT 上做
     * 会让审批卡片多卡几十毫秒。
     */
    private static void playSound() {
        Thread player = new Thread(() -> {
            Path[] candidates = {
                    Path.of("C:\\Windows\\Media\\Windows Notify System Generic.wav"),
                    Path.of("C:\\Windows\\Media\\Windows Notify.wav"),
                    Path.of("C:\\Windows\\Media\\Windows Balloon.wav"),
            };
            for (Path candidate : candidates) {
                if (!Files.exists(candidate)) {
                    continue;
                }
                try (AudioInputStream stream = AudioSystem.getAudioInputStream(candidate.toFile())) {
                    Clip clip = AudioSystem.getClip();
                    clip.open(stream);
                    clip.start();
                    return;
                } catch (Exception e) {
                    break; // 文件在但放不出来（audio 管线问题等）：直接落 beep，不再试别的文件
                }
            }
            Toolkit.getDefaultToolkit().beep();
        }, "approval-sound");
        player.setDaemon(true);
        player.start();
    }

    /**
     * 窗口抢到前台：只在窗口没有焦点时做（用户正看着就让它去，反复抢焦点反而打断打字）。
     * 最小化的先还原、托盘隐藏的先显示。Windows 下光 toFront 对后台窗口只闪任务栏，
     * 抢到真实前台靠的是"短暂 alwaysOnTop 这一手"：置顶抬到最高（跨过别的置顶窗）→
     * toFront → 要焦点，1 秒后撤掉置顶——抢夺只发生在通知瞬间，别让窗口一直钉在最上。
     * Timer setRepeats(false) 不用拆卸；连发两条审核会各自续 1 秒，行为仍正确。
     */
    private static void bringToFront(JFrame window) {
        if (window.isActive()) {
            return;
        }
        if ((window.getExtendedState() & JFrame.ICONIFIED) != 0) {
            window.setExtendedState(JFrame.NORMAL);
        } else if (!window.isVisible()) {
            window.setVisible(true); // 托盘隐藏中：先回到屏幕上才谈得上置顶
        }
        window.setAlwaysOnTop(true);
        window.toFront();
        window.requestFocus();
        Timer unpin = new Timer(1000, e -> window.setAlwaysOnTop(false));
        unpin.setRepeats(false);
        unpin.start();
    }
}
