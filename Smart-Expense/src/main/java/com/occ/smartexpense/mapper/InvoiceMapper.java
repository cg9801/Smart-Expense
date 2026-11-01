package com.occ.smartexpense.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.occ.smartexpense.entity.Invoice;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 发票 Mapper，表结构见 sql/schema.sql §6 */
@Mapper
public interface InvoiceMapper extends BaseMapper<Invoice> {

    /**
     * 复核队列分页（docs/03 §2.5）：手写 SQL 而不是 selectPage，
     * 既避免给项目引入 jsqlparser 依赖，也和各列表页的"过滤下沉 SQL"风格一致。
     */
    @Select("""
            <script>
            SELECT * FROM invoice
            <where>
              <if test="ocrStatus != null and ocrStatus != ''">ocr_status = #{ocrStatus}</if>
            </where>
            ORDER BY created_at DESC, id DESC
            LIMIT #{size} OFFSET #{offset}
            </script>
            """)
    List<Invoice> selectPageByStatus(@Param("ocrStatus") String ocrStatus,
                                     @Param("offset") int offset,
                                     @Param("size") int size);

    @Select("""
            <script>
            SELECT COUNT(*) FROM invoice
            <where>
              <if test="ocrStatus != null and ocrStatus != ''">ocr_status = #{ocrStatus}</if>
            </where>
            </script>
            """)
    long countByStatus(@Param("ocrStatus") String ocrStatus);

    // ===== 指标（docs/19 §2、docs/10）=====

    @Select("SELECT COUNT(*) FROM invoice")
    long countAll();

    @Select("SELECT COUNT(*) FROM invoice WHERE degraded = 1")
    long countDegraded();

    /**
     * 识别耗时 P95（毫秒）：MySQL 8 窗口函数 PERCENT_RANK。
     * 数据量少时（如只有 1 条，pr=0）没有 pr>=0.95 的行，用 COALESCE 兜底成最大值，
     * 避免"明明识别过却显示 0ms"。
     */
    @Select("""
            SELECT COALESCE(
                     MIN(CASE WHEN pr >= 0.95 THEN latency_ms END),
                     MAX(latency_ms)
                   ) AS p95
            FROM (
              SELECT latency_ms, PERCENT_RANK() OVER (ORDER BY latency_ms) AS pr
              FROM invoice WHERE latency_ms IS NOT NULL
            ) t
            """)
    Integer selectP95LatencyMs();

    // ===== 发票查重（硬规则用，docs/19 §4）=====

    /** 同 file_hash 被其他上传人上传的次数（跨人重复 = 疑似一票多报） */
    @Select("SELECT COUNT(*) FROM invoice WHERE file_hash = #{fileHash} AND uploader_id != #{uploaderId}")
    int countOtherUploadersByFileHash(@Param("fileHash") String fileHash,
                                      @Param("uploaderId") Long uploaderId);

    /** 同 (invoice_code, invoice_no) 已被其他报销单使用次数 */
    @Select("""
            SELECT COUNT(*) FROM invoice
             WHERE invoice_code = #{invoiceCode}
               AND invoice_no = #{invoiceNo}
               AND report_id IS NOT NULL
               AND report_id != #{reportId}
            """)
    int countOtherReportsByInvoiceNo(@Param("invoiceCode") String invoiceCode,
                                     @Param("invoiceNo") String invoiceNo,
                                     @Param("reportId") Long reportId);
}
