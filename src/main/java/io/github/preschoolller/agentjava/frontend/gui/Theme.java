package io.github.preschoolller.agentjava.frontend.gui;

import java.awt.Color;

/**
 * 配色方案（组件计划 P2-1）：浅色 / 深色两套完整色板，界面上每一种颜色都在这里成对出值，
 * 方便逐个校对。{@link Ui} 里的同名常量只是"当前生效配色"的镜像，由 Ui.applyTheme 赋值；
 * 构造期捕获颜色的组件靠 Window 重建界面来换装（重建入口见 Window.retheme）。
 * 新增颜色时三处同步：本类 light()/dark() 各一行 + Ui 声明与镜像各一行。
 */
final class Theme {

    static final Theme LIGHT = light();
    static final Theme DARK = dark();

    /** 主区背景 */
    Color bg;
    /** 侧栏背景 */
    Color sidebarBg;
    /** 侧栏选中项背景 */
    Color sidebarSelected;
    /** 消息卡片底色（AI 卡片、按钮、弹出菜单共用） */
    Color cardBg;
    /** 用户消息卡片底色，微微偏蓝方便扫读 */
    Color userCardBg;
    /** 卡片描边 */
    Color border;
    /** 输入控件/按钮描边 */
    Color borderStrong;
    /** 主要文字 */
    Color text;
    /** 次要文字（时间、占位提示、列表 marker 等） */
    Color textSecondary;
    /** 主题色：AI 角色名、主按钮、聚焦描边、链接 */
    Color accent;
    /** 主题色悬停 */
    Color accentPressed;
    /** 实时数据小胶囊底色 */
    Color pillBg;
    /** 代码块底色与描边 */
    Color codeBg;
    Color codeBorder;
    /** 行内代码前景/底色 */
    Color inlineCodeFg;
    Color inlineCodeBg;
    /** diff 块（```diff 围栏）增/删行的前景与行底色 */
    Color diffAddFg;
    Color diffAddBg;
    Color diffDelFg;
    Color diffDelBg;
    /** 引用块底色、左竖条与文字色 */
    Color quoteBg;
    Color quoteBar;
    Color quoteText;
    /** 思考块（<think> 推理内容）底色与描边，文字用 textSecondary */
    Color thinkBg;
    Color thinkBorder;
    /** 弹出层（菜单/下拉列表）描边与底色 */
    Color popupBorder;
    Color menuBg;
    /** 菜单项/下拉列表选中底色 */
    Color menuSelected;
    /** 输入控件（文本框/微调框/下拉框值区）底色 */
    Color inputBg;
    /** 按钮常态/悬停底色 */
    Color buttonBg;
    Color buttonHoverBg;
    /** 分隔线：侧栏右缘、分栏分隔条、Markdown 水平线 */
    Color dividerLine;
    /** 搜索命中词高亮底色（侧栏 HTML） */
    Color markBg;
    /** 危险操作文字（删除类菜单项） */
    Color danger;
    /** 危险按钮悬停/按下底色 */
    Color dangerPressed;
    /** 警示文字：工具卡"待确认"徽标——要人注意但还没出错，比 danger 收敛 */
    Color warning;
    /** 滚动条拇指常态/悬停 */
    Color scrollThumb;
    Color scrollThumbActive;

    private Theme() {
    }

    private static Theme light() {
        Theme t = new Theme();
        t.bg = new Color(0xF4F5F7);
        t.sidebarBg = new Color(0xEDEFF2);
        t.sidebarSelected = new Color(0xDCE3ED);
        t.cardBg = Color.WHITE;
        t.userCardBg = new Color(0xF3F7FF);
        t.border = new Color(0xE3E5E9);
        t.borderStrong = new Color(0xD6D9DE);
        t.text = new Color(0x24292F);
        t.textSecondary = new Color(0x8A919F);
        t.accent = new Color(0x3D7EFF);
        t.accentPressed = new Color(0x2F66DB);
        t.pillBg = new Color(0xE9EBEF);
        t.codeBg = new Color(0xF6F8FA);
        t.codeBorder = new Color(0xE2E6EB);
        t.inlineCodeFg = new Color(0x9C3B57);
        t.inlineCodeBg = new Color(0xF0F1F5);
        t.diffAddFg = new Color(0x1A7F37);
        t.diffAddBg = new Color(0xE6FFEC);
        t.diffDelFg = new Color(0xCF222E);
        t.diffDelBg = new Color(0xFFECEC);
        t.quoteBg = new Color(0xF7F8FA);
        t.quoteBar = new Color(0xD3D9E0);
        t.quoteText = new Color(0x5B6470);
        t.thinkBg = new Color(0xF5F4F9);
        t.thinkBorder = new Color(0xE5E2EE);
        t.popupBorder = new Color(0xE0E3E8);
        t.menuBg = Color.WHITE;
        t.menuSelected = new Color(0xE9EEF6);
        t.inputBg = Color.WHITE;
        t.buttonBg = Color.WHITE;
        t.buttonHoverBg = new Color(0xF0F2F6);
        t.dividerLine = new Color(0xE0E3E8);
        t.markBg = new Color(0xFFE28A);
        t.danger = new Color(0xD9453B);
        t.dangerPressed = new Color(0xC23B32);
        t.warning = new Color(0xB45F06);
        t.scrollThumb = new Color(0xC7CCD5);
        t.scrollThumbActive = new Color(0xA6AEBB);
        return t;
    }

    private static Theme dark() {
        Theme t = new Theme();
        t.bg = new Color(0x1B1E23);
        t.sidebarBg = new Color(0x16181D);
        t.sidebarSelected = new Color(0x2C3542);
        t.cardBg = new Color(0x252930);
        t.userCardBg = new Color(0x232D3B);
        t.border = new Color(0x333840);
        t.borderStrong = new Color(0x40464F);
        t.text = new Color(0xE7E9EC);
        t.textSecondary = new Color(0x9AA3B0);
        t.accent = new Color(0x4E8BFF);
        t.accentPressed = new Color(0x3A78E6);
        t.pillBg = new Color(0x272B32);
        t.codeBg = new Color(0x1A1D23);
        t.codeBorder = new Color(0x343A42);
        t.inlineCodeFg = new Color(0xFF8B9E);
        t.inlineCodeBg = new Color(0x343A42);
        t.diffAddFg = new Color(0x3FB950);
        t.diffAddBg = new Color(0x1F3A2A);
        t.diffDelFg = new Color(0xF85149);
        t.diffDelBg = new Color(0x3D2226);
        t.quoteBg = new Color(0x22262D);
        t.quoteBar = new Color(0x4E5766);
        t.quoteText = new Color(0xA9B2BF);
        t.thinkBg = new Color(0x232732);
        t.thinkBorder = new Color(0x3D4252);
        t.popupBorder = new Color(0x3A414B);
        t.menuBg = new Color(0x252930);
        t.menuSelected = new Color(0x2F3B4E);
        t.inputBg = new Color(0x2B3038);
        t.buttonBg = new Color(0x252930);
        t.buttonHoverBg = new Color(0x2F343C);
        t.dividerLine = new Color(0x31363E);
        t.markBg = new Color(0x8A6A1F);
        t.danger = new Color(0xF0564A);
        t.dangerPressed = new Color(0xD94A40);
        t.warning = new Color(0xD29922);
        t.scrollThumb = new Color(0x4A515C);
        t.scrollThumbActive = new Color(0x5D6673);
        return t;
    }
}
