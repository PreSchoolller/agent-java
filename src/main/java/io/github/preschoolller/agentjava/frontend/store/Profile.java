package io.github.preschoolller.agentjava.frontend.store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一套后端请求配置（模型方案）。名字存在 AppSettings 的 Map 键里，这里只放参数本身。 */
public class Profile {

    /** 后端 API 类型：response=OpenAI Responses、chatcompletion=OpenAI Chat Completions、
     *  messages=Anthropic Messages；该类型下用哪个 provider 见 activeProviderName */
    public static final String API_CHAT_COMPLETION = "chatcompletion";
    public static final String API_RESPONSE = "response";
    public static final String API_MESSAGES = "messages";
    public static final List<String> API_TYPES = List.of(API_CHAT_COMPLETION, API_RESPONSE, API_MESSAGES);

    private String backendUrl = "http://127.0.0.1:8080/chat";
    private String key = "123456789";
    private String model = "default";
    /** 可选模型目录（带元数据），model 是其中的默认项（输入区下拉的选项来源） */
    private List<ModelMeta> models = new ArrayList<>(List.of(new ModelMeta("default")));
    private String apiType = API_RESPONSE;
    /** 每个 API 类型下选用的 provider 名（Provider 页分组单选落盘）；getActiveProviderName
     *  按**当前 apiType** 取值——后端拿到的语义仍是"当前类型的选择"，无需感知多组存储 */
    private Map<String, String> activeProviderNames = new LinkedHashMap<>();
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
        copy.models = new ArrayList<>();
        for (ModelMeta meta : models) {
            copy.models.add(meta.copy());
        }
        copy.apiType = apiType;
        copy.activeProviderNames = new LinkedHashMap<>(activeProviderNames);
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

    public String getApiType() {
        return apiType;
    }

    /** 非法值回落 response（旧文件缺键/手工改坏配置的兜底：后端未支持的类型顶多被当 response 跑） */
    public void setApiType(String apiType) {
        this.apiType = API_TYPES.contains(apiType) ? apiType : API_RESPONSE;
    }

    /** 当前 API 类型下选用的 provider 名；未指定返回 null（后端回落默认） */
    public String getActiveProviderName() {
        return activeProviderNames.get(apiType);
    }

    /** 当前 API 类型的 provider 选择；null/空白=清除该类型下的指定。名字是否在注册表里由后端解析时兜底 */
    public void setActiveProviderName(String activeProviderName) {
        if (activeProviderName == null || activeProviderName.isBlank()) {
            activeProviderNames.remove(apiType);
        } else {
            activeProviderNames.put(apiType, activeProviderName.strip());
        }
    }

    /** 全部 API 类型的 provider 选择（只读视图；落盘/深拷贝用） */
    public Map<String, String> getActiveProviderNames() {
        return Map.copyOf(activeProviderNames);
    }

    /** 整体替换各 API 类型的 provider 选择（load 恢复用）；空白值丢弃 */
    public void setActiveProviderNames(Map<String, String> names) {
        activeProviderNames.clear();
        if (names != null) {
            for (Map.Entry<String, String> entry : names.entrySet()) {
                if (entry.getKey() != null && API_TYPES.contains(entry.getKey())
                        && entry.getValue() != null && !entry.getValue().isBlank()) {
                    activeProviderNames.put(entry.getKey(), entry.getValue().strip());
                }
            }
        }
    }

    public List<String> getModels() {
        List<String> names = new ArrayList<>();
        for (ModelMeta meta : models) {
            names.add(meta.getName());
        }
        return names;
    }

    /** 更新可选模型列表（默认元数据）：老调用方/旧落盘文件用；默认项不在新列表里落到第一个 */
    public void setModels(List<String> models) {
        if (models == null || models.isEmpty()) {
            return;
        }
        List<ModelMeta> metas = new ArrayList<>();
        for (String name : models) {
            metas.add(new ModelMeta(name));
        }
        applyModelMetas(metas);
    }

    /** 模型目录（含元数据）的深拷贝读口 */
    public List<ModelMeta> getModelMetas() {
        List<ModelMeta> copy = new ArrayList<>();
        for (ModelMeta meta : models) {
            copy.add(meta.copy());
        }
        return copy;
    }

    /** 更新模型目录：默认项不在新目录里落到第一个，目录空则保持原样 */
    public void setModelMetas(List<ModelMeta> metas) {
        if (metas == null || metas.isEmpty()) {
            return;
        }
        applyModelMetas(new ArrayList<>(metas.stream().map(ModelMeta::copy).toList()));
    }

    /** 目录替换的共有尾部：塞进不可见拷贝 + 默认项兜底（setModels/setModelMetas 同一规则） */
    private void applyModelMetas(List<ModelMeta> metas) {
        models = metas;
        if (!containsModel(model)) {
            model = models.get(0).getName();
        }
    }

    /** 名字（strip）在目录里？默认项校验/重命名冲突都看它 */
    public boolean containsModel(String name) {
        if (name == null) {
            return false;
        }
        for (ModelMeta meta : models) {
            if (name.equals(meta.getName())) {
                return true;
            }
        }
        return false;
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
