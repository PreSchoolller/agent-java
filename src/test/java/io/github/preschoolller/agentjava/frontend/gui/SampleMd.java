package io.github.preschoolller.agentjava.frontend.gui;

/** Markdown 渲染样例：解析断言和 6 号截图共用，覆盖渲染器支持的全部块类型（原 Shot.java 平移） */
final class SampleMd {

    static final String SAMPLE_MD = """
            ## 回答要点

            这是 **加粗**贴字、*斜体*贴字、`行内代码`、[链接](https://example.com)、~~删除线~~ 的段落，
            第二行紧跟着上一行，还有裸地址 https://example.com/path?q=1。

            工具一览：

            | 工具 | 权限 | 说明 |
            | --- | --- | --- |
            | read | 只读 | 读取文本文件内容 |
            | write | 需确认 | 写入文件前经用户放行 |

            - 无序列表一
            - 无序列表二
                - 嵌套项
            1. 有序列表
            - [x] 已完成任务
            - [ ] 待办任务

            ```java
            public class Demo {
                public static void main(String[] args) {
                    System.out.println("hello 中文注释");
                }
            }
            ```

            > 引用第一行
            > 引用第二行

            ---

            文件修改 diff：

            ```diff
            --- a/src/Demo.java
            +++ b/src/Demo.java
            @@ -12,7 +12,9 @@
             public class Demo {
            -    private static final int SIZE = 10;
            +    private static final int SIZE = 20;
                 private Buffer buffer;
             }
            ```

            结尾段落。
            """;

    private SampleMd() {
    }
}
