package io.github.preschoolller.agentjava.frontend.store;

import java.util.ArrayList;
import java.util.List;

/** 一套后端请求配置（模型方案）。名字存在 AppSettings 的 Map 键里，这里只放参数本身。 */
public class Profile {

    private String backendUrl = "http://127.0.0.1:8080/chat";
    private String key = "123456789";
    private String model = "default";
    /** 可选模型列表，model 是其中的默认项（输入区下拉的选项来源） */
    private List<String> models = new ArrayList<>(List.of("default"));
    private double temperature = 0.7;
    private int maxTokens = 2048;
    private String systemPrompt = "";

    public Profile() {
    }

    /** 深拷贝（设置面板"新建方案"复制当前参数用） */
    public Profile copy() {
        Profile copy = new Profile();
        copy.backendUrl = backendUrl;
        copy.key = key;
        copy.model = model;
        copy.models = new ArrayList<>(models);
        copy.temperature = temperature;
        copy.maxTokens = maxTokens;
        copy.systemPrompt = systemPrompt;
        return copy;
    }

    public String getBackendUrl() {
        return backendUrl;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public void setBackendUrl(String backendUrl) {
        this.backendUrl = backendUrl;
    }

    public String getModel() {
        return model;
    }

    public List<String> getModels() {
        return new ArrayList<>(models);
    }

    /** 更新可选模型列表；默认项不在新列表里时落到第一个，列表空则保持原样 */
    public void setModels(List<String> models) {
        if (models == null || models.isEmpty()) {
            return;
        }
        this.models = new ArrayList<>(models);
        if (!this.models.contains(model)) {
            model = this.models.get(0);
        }
    }

    public void setModel(String model) {
        this.model = model;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }
}
