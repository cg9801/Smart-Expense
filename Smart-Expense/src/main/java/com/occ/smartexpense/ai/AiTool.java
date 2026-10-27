package com.occ.smartexpense.ai;

/**
 * Agent 可调用的只读工具，见 docs/11-AI识别与辅助审核.md §5.3。
 *
 * <p>硬约束：
 * <ul>
 *   <li>实现类禁止写库，只能查（Mapper 只读方法）；</li>
 *   <li>参数是模型给的 JSON，必须做类型/范围校验；</li>
 *   <li>返回 JSON 字符串，截断到 2KB / 10 行以内。</li>
 * </ul>
 */
public interface AiTool {

    /** 工具名，必须与 prompt 中的声明完全一致（如 getBudget） */
    String name();

    /**
     * 执行工具。
     *
     * @param argsJson 模型给出的参数 JSON
     * @return 结果 JSON；失败时返回 {"error":"..."}，不抛异常给 Agent 循环
     */
    String execute(String argsJson);
}
