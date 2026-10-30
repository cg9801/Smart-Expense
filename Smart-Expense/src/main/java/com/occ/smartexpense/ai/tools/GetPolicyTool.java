package com.occ.smartexpense.ai.tools;

import com.occ.smartexpense.ai.AiTool;
import org.springframework.stereotype.Component;

/**
 * 工具：查报销政策（docs/11 §5.3）。
 * 参数：{"expenseType":"TRAVEL"}
 * 返回：{"expenseType":"TRAVEL","limit":"5000.00","requireInvoice":true,"note":"超限需分管审批"}
 *
 * <p>当前骨架返回静态示例；后续可接入配置表或数据库。
 */
@Component
public class   GetPolicyTool implements AiTool {

    @Override
    public String name() {
        return "getPolicy";
    }

    @Override
    public String execute(String argsJson) {
        // TODO 按 expenseType 查政策（配置表/application.yaml），未知类型返回默认限额
        return """
                {"expenseType":"TRAVEL","limit":"5000.00","requireInvoice":true,"note":"超限需分管审批"}
                """;
    }
}
