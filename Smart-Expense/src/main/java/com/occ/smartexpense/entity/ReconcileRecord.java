package com.occ.smartexpense.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 对账批次 reconcile_record，字段见 sql/schema.sql §7 与 docs/08-对账与自动补偿.md §4。
 * uk_biz_date：一个业务日只允许一个批次，重复触发直接返回已有批次。
 */
@Data
@TableName("reconcile_record")
public class ReconcileRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 对账业务日（T-1） */
    private LocalDate bizDate;

    private Integer scannedCount;

    private Integer diffCount;

    /** RUNNING / DONE / FAILED */
    private String status;

    /** 批次明细（JSON 字符串，失败时记异常摘要） */
    private String detail;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
