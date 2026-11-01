package com.occ.smartexpense.vo;

import lombok.Data;

import java.math.BigDecimal;

/** 字段级识别结果（docs/11 §4）：前端据此预填表单并高亮低置信度字段 */
@Data
public class InvoiceFieldVO {

    private String name;

    private String value;

    private BigDecimal confidence;

    private String source;
}
