package io.github.preschoolller.agentjava;

import io.github.preschoolller.agentjava.gui.Window;

import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.util.concurrent.CompletableFuture;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Window window = new Window("Agent");
            window.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            window.setSize(1280, 720);
            window.setLocation(640, 360);
            window.setVisible(true);

            // ===== 后端接入示例：把下面的模拟调用换成你自己的后端请求即可 =====
            window.onInputSubmitted((conversation, text) -> {
                window.setGenerating(true);
                // TODO: 在这里异步请求后端（别卡 UI 线程），配置从 AppSettings.get() 里拿：
                //   AppSettings.get().getBackendUrl() / getModel() / getTemperature() ...
                // 拿到回复后回到 UI 线程调 window.addAiMessage(回复) + setGenerating(false)。
                CompletableFuture.runAsync(() -> {
                    try {
                        Thread.sleep(600); // 模拟后端耗时
                    } catch (InterruptedException ignored) {
                    }
                    SwingUtilities.invokeLater(() -> {
                        window.addAiMessage("（示例回复）已收到：「" + text + "」。把这段模拟调用换成你的后端请求。");
                        window.setGenerating(false);
                    });
                });
            });
            window.onStopRequested(() -> {
                // TODO: 通知后端中断本次生成
            });
        });
    }
}
