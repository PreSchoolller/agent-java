package io.github.preschoolller.agentjava;

import io.github.preschoolller.agentjava.backend.maintenance.ShutDownJob;
import io.github.preschoolller.agentjava.backend.maintenance.TaskHandle;
import io.github.preschoolller.agentjava.core.interfaces.AgentApp;
import io.github.preschoolller.agentjava.frontend.SwingRenderer;
import io.github.preschoolller.agentjava.frontend.gui.Window;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.util.UUID;

/**
 * Swing 前端的组合根：建窗、订阅事件、把 UI 动作翻译成 {@link AgentApp} 的命令。
 *
 * <p>本类只依赖 {@code core.interfaces}：内核由外部传入，前端不提供任何实现。
 * {@code app == null} 表示实现尚未就绪——界面照常起来，所有命令都会提示"核心未就绪"。
 */
public final class SwingApp {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(SwingApp.class);

    private SwingApp() {
    }

    /**
     * 启动前端。
     *
     * @param app 内核实现；null = 尚未就绪，界面只读
     */
    public static void start(AgentApp app) {
        SwingUtilities.invokeLater(() -> {
            Window window = new Window("Agent");
            SwingRenderer renderer = new SwingRenderer(window, app);
            if (app != null) {
                try {
                    app.subscribe(0, renderer);
                } catch (RuntimeException e) {
                    LOG.error("订阅事件流失败", e);
                    window.showNotice(null, "订阅事件流失败：" + e.getMessage());
                }
            }
            wire(window, app);
            window.setVisible(true);
            if (app == null) {
                window.showNotice(null, "核心未就绪：AgentApp 尚无实现，当前界面只读。");
            }
        });
    }

    /** 入站回调：一律翻译成 AgentApp 的命令，不碰任何实现类 */
    private static void wire(Window window, AgentApp app) {
        window.onInputSubmitted((conversation, text) -> {
            if (app == null) {
                window.showNotice(conversation.getId(), "核心未就绪，消息未发送。");
                return;
            }
            app.submit(conversation.getId(), UUID.randomUUID().toString(), text);
        });

        window.onRegenerate(conversation -> {
            if (app == null) {
                window.showNotice(conversation.getId(), "核心未就绪，无法重新生成。");
                return;
            }
            app.regenerate(conversation.getId(), null);
        });

        window.onStopRequested(() -> {
            if (app == null) {
                return;
            }
            String sessionId = window.getStore().getActive().getId();
            // 已派发的工具还在后台跑完才收尾：胶囊给个说明，避免误以为卡死
            window.setRealtimeInfo("正在等待工具收尾…");
            app.cancel(sessionId);
        });

        // 工具卡的"终止后台任务"：契约里还没有对应命令，暂由进程层的 ShutDownJob 处理。
        // core 补上 killBackgroundTask 后改调 AgentApp，不要在这里续长实现。
        window.setBackGroundTaskControl(new Window.BackGroundTaskControl() {
            @Override
            public boolean kill(String conversationId, String callId) {
                TaskHandle killed = ShutDownJob.kill(callId);
                if (killed != null && killed.background()) {
                    SwingUtilities.invokeLater(() -> window.onBackGroundTaskKilled(conversationId, callId));
                }
                return killed != null;
            }

            @Override
            public String output(String conversationId, String callId) {
                return ShutDownJob.getBufferedOutput(callId);
            }
        });
    }
}
