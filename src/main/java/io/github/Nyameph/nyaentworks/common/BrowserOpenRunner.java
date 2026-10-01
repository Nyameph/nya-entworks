package io.github.Nyameph.nyaentworks.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import java.awt.Desktop;
import java.net.URI;

@Component
public class BrowserOpenRunner implements CommandLineRunner {
    @Value("${server.address}")
    String serverAddress;
    @Value("${server.port}")
    int serverPort;

    @Override
    public void run(String... args) throws Exception {
        // 确保应用已完全启动，可以加一点延迟（非必须）
        // Thread.sleep(1000);
        String url = "http://"+serverAddress+":"+serverPort;
//        openBrowser(url);
    }

    private void openBrowser(String url) {
        try {
            // 方式1：使用 Desktop API (推荐，跨平台)
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            } else {
                // 方式2：使用 Runtime 执行系统命令 (作为备选)
                String os = System.getProperty("os.name").toLowerCase();
                Runtime runtime = Runtime.getRuntime();
                if (os.contains("win")) {
                    // Windows 命令
                    runtime.exec("rundll32 url.dll,FileProtocolHandler " + url); /*0†L12-L13*/
                } else if (os.contains("mac")) {
                    // MacOS 命令
                    runtime.exec("open " + url);
                } else if (os.contains("nix") || os.contains("nux")) {
                    // Linux 命令
                    runtime.exec("xdg-open " + url);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}