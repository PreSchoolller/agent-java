package io.github.preschoolller.agentjava.backend.tool;

import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseFunctionToolCall;
import io.github.preschoolller.agentjava.backend.tool.interfaces.Tool;

public class BadTool implements Tool {

    private static BadTool instance;

    private BadTool() {
    }

    public static BadTool getInstance() {
        if (instance == null) {
            synchronized (BadTool.class) {
                if (instance == null) {
                    instance = new BadTool();
                }
            }
        }
        return instance;
    }

    @Override
    public String name() {
        return null;
    }

    @Override
    public String description() {
        return null;
    }

    @Override
    public FunctionTool.Parameters schema() {
        return null;
    }

    @Override
    public String execute(String arguments) {
        return null;
    }

    @Override
    public Boolean test(ResponseFunctionToolCall functionTool) {
        return false;
    }
}
