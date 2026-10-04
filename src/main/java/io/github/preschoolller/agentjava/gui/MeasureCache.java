package io.github.preschoolller.agentjava.gui;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文本高度测量缓存（组件计划 P2-2）：长会话切换/重排时避免重复做 StyledDocument + View 运算。
 * 键 = (文本, 字号, 宽度档位, 是否 Markdown)，值 = 行高数组（Markdown 是每行行高，普通文本是单元素数组）。
 * 宽度档位 8px 向下取整：档位宽 ≤ 实宽，按窄宽折行测出的高度只会偏高不会裁字；宽窄差 ≤8px
 * 体现为行尾最多约一字符的空隙，肉眼不可见。MarkdownPane 与 Window 的普通文本测量共用本缓存。
 * 只在 EDT 上访问，无需加锁；LRU 上限防止流式输出的中间文本把缓存撑大。
 */
final class MeasureCache {

    /** 宽度档位：向下取整到 8px */
    static int bucketWidth(int width) {
        return Math.max(0, width - width % 8);
    }

    private static final int MAX_ENTRIES = 4096;

    private record Key(String text, int fontSize, int width, boolean markdown) {
    }

    private static final Map<Key, int[]> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, int[]> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    static int[] get(String text, int fontSize, int width, boolean markdown) {
        return CACHE.get(new Key(text, fontSize, width, markdown));
    }

    static void put(String text, int fontSize, int width, boolean markdown, int[] rowHeights) {
        CACHE.put(new Key(text, fontSize, width, markdown), rowHeights);
    }

    private MeasureCache() {
    }
}
