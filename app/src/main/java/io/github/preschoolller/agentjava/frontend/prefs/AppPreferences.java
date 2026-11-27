package io.github.preschoolller.agentjava.frontend.prefs;

/**
 * 宿主界面偏好：主题、正文字号、窗口位置、托盘、审核提醒、日志。
 *
 * <p>只影响本机这一个前端，不属于会话或业务状态；换一个前端（Web/HTTP）不需要实现它，
 * 那些前端有自己的皮肤与布局。实现方负责落盘，{@link #save()} 立即写盘
 * （拖动窗口这类高频变更由调用方防抖）。
 */
public interface AppPreferences {

    // —— 外观 ——

    /** 落盘值：system / light / dark */
    String uiTheme();

    void setUiTheme(String theme);

    /** 解析后的主题（system 按操作系统当前配色），light / dark */
    String resolvedUiTheme();

    int contentFontSize();

    void setContentFontSize(int size);

    // —— 窗口 ——

    boolean minimizeToTray();

    void setMinimizeToTray(boolean value);

    /** 主窗口尺寸与位置；0 或负数 = 未记录（由窗口回落居中） */
    int windowWidth();

    void setWindowWidth(int value);

    int windowHeight();

    void setWindowHeight(int value);

    int windowX();

    void setWindowX(int value);

    int windowY();

    void setWindowY(int value);

    /** 设置对话框尺寸与位置；0 或负数 = 未记录（由对话框回落默认值） */
    int settingsDialogWidth();

    void setSettingsDialogWidth(int value);

    int settingsDialogHeight();

    void setSettingsDialogHeight(int value);

    int settingsDialogX();

    void setSettingsDialogX(int value);

    int settingsDialogY();

    void setSettingsDialogY(int value);

    /** 侧栏分隔条位置；负数 = 未记录 */
    int sidebarDivider();

    void setSidebarDivider(int value);

    // —— 审核提醒：三个通道互相独立 ——

    boolean approvalSound();

    void setApprovalSound(boolean value);

    boolean approvalSystemNotify();

    void setApprovalSystemNotify(boolean value);

    boolean approvalBringToFront();

    void setApprovalBringToFront(boolean value);

    // —— 日志：启动时读一次，改后重启生效 ——

    boolean logConsoleEnabled();

    void setLogConsoleEnabled(boolean value);

    String logConsoleLevel();

    void setLogConsoleLevel(String level);

    String logConsoleFormat();

    void setLogConsoleFormat(String format);

    boolean logFileEnabled();

    void setLogFileEnabled(boolean value);

    String logFileLevel();

    void setLogFileLevel(String level);

    String logFilePath();

    void setLogFilePath(String path);

    boolean logFileAppend();

    void setLogFileAppend(boolean value);

    /** 单文件上限，空 = 不轮转；格式见 {@link #isValidLogFileSize(String)} */
    String logFileMaxSize();

    void setLogFileMaxSize(String maxSize);

    int logFileBackups();

    void setLogFileBackups(int backups);

    String logFileFormat();

    void setLogFileFormat(String format);

    /** 单文件上限是否合法（数字 + kb/mb/gb） */
    boolean isValidLogFileSize(String maxSize);

    void save();
}
