package io.github.preschoolller.agentjava.frontend.store;

import io.github.preschoolller.agentjava.backend.provider.common.ReasoningEffortEnum;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单个模型的元数据（设置面板"模型目录"维护）。
 * 名字是服务端标识（发给后端的 model 参数）；其余是这份模型的能力档案：
 * 上下文窗口是纯数字 token 数（消费方 Profile.getModelCtxWindow 是 Integer.parseInt 直读，
 * 空=未设置；最大输出 token 数先落盘存档（自由文本，空=未设置），消费方接入再定解析；
 * 图片输入能力同理（当前会话还不发图片）。
 * 默认推理等级接了输出区：选中该模型时思考强度下拉自动带出（空=跟随对话当前选择），
 * 取值是 ReasoningEffortEnum 的枚举名。
 */
public class ModelMeta {

    private String name;
    /** 上下文窗口（纯数字 token 数，空=未设置）；非法值不入库见 setCtxWindow */
    private String ctxWindow = "";
    /** 最大输出 token 数（自由文本，空=未设置） */
    private String maxOutput = "";
    /** 默认推理等级：ReasoningEffortEnum 的枚举名，空 = 跟随对话当前选择 */
    private String reasoning = "";
    /** 输入能力：文本（模型至少能收文本，默认有） */
    private boolean text = true;
    /** 输入能力：图片（当前会话还不发图片，先存档） */
    private boolean image = false;

    public ModelMeta() {
    }

    public ModelMeta(String name) {
        this.name = name;
    }

    public ModelMeta copy() {
        ModelMeta copy = new ModelMeta(name);
        copy.ctxWindow = ctxWindow;
        copy.maxOutput = maxOutput;
        copy.reasoning = reasoning;
        copy.text = text;
        copy.image = image;
        return copy;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCtxWindow() {
        return ctxWindow;
    }

    /** 空串/纯数字才是合法上下文窗口值（null/空白=未设置算合法，是清除语义） */
    public static boolean isValidCtxWindow(String value) {
        return value != null && value.matches("\\d+");
    }

    /**
     * 上下文窗口只接受纯数字 token 数（两侧空白 strip）：消费方 Profile.getModelCtxWindow
     * 是 Integer.parseInt 直读，非法值忽略保持原值（不静默洗掉旧落盘的自由文本，回滚显示由设置面板负责）。
     */
    public void setCtxWindow(String ctxWindow) {
        if (ctxWindow == null || ctxWindow.isBlank()) {
            this.ctxWindow = "";
            return;
        }
        String stripped = ctxWindow.strip();
        if (isValidCtxWindow(stripped)) {
            this.ctxWindow = stripped;
        }
    }

    public String getMaxOutput() {
        return maxOutput;
    }

    public void setMaxOutput(String maxOutput) {
        this.maxOutput = maxOutput;
    }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
    }

    /** 推理等级是枚举名字符串；非法值回落 ""（跟随对话） */
    public String normalizedReasoning() {
        for (ReasoningEffortEnum effort : ReasoningEffortEnum.values()) {
            if (effort.name().equals(reasoning)) {
                return reasoning;
            }
        }
        return "";
    }

    public boolean isText() {
        return text;
    }

    public void setText(boolean text) {
        this.text = text;
    }

    public boolean isImage() {
        return image;
    }

    public void setImage(boolean image) {
        this.image = image;
    }

    /** 落盘序列化：Map 形态交给 JsonKit.write（LinkedHashMap 键序稳定） */
    public Map<String, Object> toJson() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("ctxWindow", ctxWindow);
        map.put("maxOutput", maxOutput);
        map.put("reasoning", reasoning);
        map.put("text", text);
        map.put("image", image);
        return map;
    }

    /** 落盘反序列化：缺键落默认值（老 JSON/手工编辑都安全），名字空白的条目由调用方兜底 */
    public static ModelMeta fromJson(Map<String, Object> map) {
        ModelMeta meta = new ModelMeta(str(map, "name"));
        meta.ctxWindow = str(map, "ctxWindow");
        meta.maxOutput = str(map, "maxOutput");
        meta.reasoning = str(map, "reasoning");
        Object text = map.get("text");
        meta.text = text == null || Boolean.parseBoolean(String.valueOf(text)); // 缺键=字段默认 true
        Object image = map.get("image");
        meta.image = image != null && Boolean.parseBoolean(String.valueOf(image)); // 缺键=字段默认 false
        return meta;
    }

    private static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
