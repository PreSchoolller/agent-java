package io.github.preschoolller.agentjava.frontend;

import io.github.preschoolller.agentjava.core.repository.AppSettings;

/**
 * {@link AppPreferences} 的持久化实现：委托给 {@link AppSettings}。
 *
 * <p>这是前端与配置存储的唯一接触点——界面代码只认接口，不认 AppSettings。
 */
public final class AppSettingsPreferences implements AppPreferences {

    private final AppSettings settings;

    public AppSettingsPreferences() {
        this(AppSettings.get());
    }

    public AppSettingsPreferences(AppSettings settings) {
        this.settings = settings;
    }

    @Override
    public String uiTheme() {
        return settings.getUiTheme();
    }

    @Override
    public void setUiTheme(String theme) {
        settings.setUiTheme(theme);
    }

    @Override
    public String resolvedUiTheme() {
        return settings.resolvedUiTheme();
    }

    @Override
    public int contentFontSize() {
        return settings.getContentFontSize();
    }

    @Override
    public void setContentFontSize(int size) {
        settings.setContentFontSize(size);
    }

    @Override
    public boolean minimizeToTray() {
        return settings.isMinimizeToTray();
    }

    @Override
    public void setMinimizeToTray(boolean value) {
        settings.setMinimizeToTray(value);
    }

    @Override
    public int windowWidth() {
        return settings.getWindowWidth();
    }

    @Override
    public void setWindowWidth(int value) {
        settings.setWindowWidth(value);
    }

    @Override
    public int windowHeight() {
        return settings.getWindowHeight();
    }

    @Override
    public void setWindowHeight(int value) {
        settings.setWindowHeight(value);
    }

    @Override
    public int windowX() {
        return settings.getWindowX();
    }

    @Override
    public void setWindowX(int value) {
        settings.setWindowX(value);
    }

    @Override
    public int windowY() {
        return settings.getWindowY();
    }

    @Override
    public void setWindowY(int value) {
        settings.setWindowY(value);
    }

    @Override
    public int settingsDialogWidth() {
        return settings.getSettingsDialogWidth();
    }

    @Override
    public void setSettingsDialogWidth(int value) {
        settings.setSettingsDialogWidth(value);
    }

    @Override
    public int settingsDialogHeight() {
        return settings.getSettingsDialogHeight();
    }

    @Override
    public void setSettingsDialogHeight(int value) {
        settings.setSettingsDialogHeight(value);
    }

    @Override
    public int settingsDialogX() {
        return settings.getSettingsDialogX();
    }

    @Override
    public void setSettingsDialogX(int value) {
        settings.setSettingsDialogX(value);
    }

    @Override
    public int settingsDialogY() {
        return settings.getSettingsDialogY();
    }

    @Override
    public void setSettingsDialogY(int value) {
        settings.setSettingsDialogY(value);
    }

    @Override
    public int sidebarDivider() {
        return settings.getSidebarDivider();
    }

    @Override
    public void setSidebarDivider(int value) {
        settings.setSidebarDivider(value);
    }

    @Override
    public boolean approvalSound() {
        return settings.isApprovalSound();
    }

    @Override
    public void setApprovalSound(boolean value) {
        settings.setApprovalSound(value);
    }

    @Override
    public boolean approvalSystemNotify() {
        return settings.isApprovalSystemNotify();
    }

    @Override
    public void setApprovalSystemNotify(boolean value) {
        settings.setApprovalSystemNotify(value);
    }

    @Override
    public boolean approvalBringToFront() {
        return settings.isApprovalBringToFront();
    }

    @Override
    public void setApprovalBringToFront(boolean value) {
        settings.setApprovalBringToFront(value);
    }

    @Override
    public boolean logConsoleEnabled() {
        return settings.isLogConsoleEnabled();
    }

    @Override
    public void setLogConsoleEnabled(boolean value) {
        settings.setLogConsoleEnabled(value);
    }

    @Override
    public String logConsoleLevel() {
        return settings.getLogConsoleLevel();
    }

    @Override
    public void setLogConsoleLevel(String level) {
        settings.setLogConsoleLevel(level);
    }

    @Override
    public String logConsoleFormat() {
        return settings.getLogConsoleFormat();
    }

    @Override
    public void setLogConsoleFormat(String format) {
        settings.setLogConsoleFormat(format);
    }

    @Override
    public boolean logFileEnabled() {
        return settings.isLogFileEnabled();
    }

    @Override
    public void setLogFileEnabled(boolean value) {
        settings.setLogFileEnabled(value);
    }

    @Override
    public String logFileLevel() {
        return settings.getLogFileLevel();
    }

    @Override
    public void setLogFileLevel(String level) {
        settings.setLogFileLevel(level);
    }

    @Override
    public String logFilePath() {
        return settings.getLogFilePath();
    }

    @Override
    public void setLogFilePath(String path) {
        settings.setLogFilePath(path);
    }

    @Override
    public boolean logFileAppend() {
        return settings.isLogFileAppend();
    }

    @Override
    public void setLogFileAppend(boolean value) {
        settings.setLogFileAppend(value);
    }

    @Override
    public String logFileMaxSize() {
        return settings.getLogFileMaxSize();
    }

    @Override
    public void setLogFileMaxSize(String maxSize) {
        settings.setLogFileMaxSize(maxSize);
    }

    @Override
    public int logFileBackups() {
        return settings.getLogFileBackups();
    }

    @Override
    public void setLogFileBackups(int backups) {
        settings.setLogFileBackups(backups);
    }

    @Override
    public String logFileFormat() {
        return settings.getLogFileFormat();
    }

    @Override
    public void setLogFileFormat(String format) {
        settings.setLogFileFormat(format);
    }

    @Override
    public boolean isValidLogFileSize(String maxSize) {
        return AppSettings.isValidLogFileSize(maxSize);
    }

    @Override
    public void save() {
        settings.save();
    }
}
