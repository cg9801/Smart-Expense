package com.occ.smartexpense.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 详情出参（含明细），对应 docs/03-接口清单.md §2.2 GET /reports/{id}。
 */
@Data
public class ReportDetailVO {

    private Long id;/** id **/

    private String reportNo;/** 报销编号 **/

    private String title;/** 发票名 **/

    private String expenseType; /** 支付类型 **/

    private BigDecimal amountTotal;/** 总金额 **/

    private String currency;/** 现金 **/

    private String status;/** 状态 **/

    private Integer currentStep;/** 现在的步骤 **/

    private Integer totalSteps;/** 总步骤 **/

    private String remark;/** 标记 **/

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;/** 创建时间 **/

    /** 当前状态允许的动作，由 ReportStateMachine 生成，前端不自行推断（docs/03 §3.1） */
    private List<String> availableActions;/** 允许的操作 **/

    private List<ReportItemVO> items;/** 支付类型 **/

    /** AI 审批建议：缺失时为 null，前端显示"暂无 AI 建议"（docs/11 §5.7） */
    private AiAdviceVO aiAdvice;/** AI 建议 **/

    /** 该单关联的发票（v2 方案 A：上传后绑定，审批人可核对） */
    private List<InvoiceVO> invoices;
}
