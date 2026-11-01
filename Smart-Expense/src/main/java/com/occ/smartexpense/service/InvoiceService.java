package com.occ.smartexpense.service;

import com.occ.smartexpense.common.PageResult;
import com.occ.smartexpense.dto.InvoiceReviewDTO;
import com.occ.smartexpense.vo.InvoiceVO;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;

/**
 * 发票服务（v2 方案 A 最小可用）：上传落库、文件读取、绑定报销单。
 * 完整识别链路见 docs/11-AI识别与辅助审核.md；识别消费者接入前 ocr_status 固定 MANUAL。
 */
public interface InvoiceService {

    /** 上传发票：同人同 file_hash 直接复用已有记录（docs/11 §3.4 情形 1），不重复落库 */
    InvoiceVO upload(MultipartFile file, Long uploaderId);

    InvoiceVO get(Long id);

    /** 读取发票文件，返回物理路径与推断的 Content-Type */
    InvoiceFile file(Long id);

    /** 建单时把发票绑定到报销单；只允许本人上传的、尚未绑定的发票 */
    void bindReport(Long invoiceId, Long reportId, Long applicantId);

    List<InvoiceVO> listByReport(Long reportId);

    /**
     * 删除草稿时解绑（DELETE /reports/{id}）：发票回到"未关联"状态，可再次用于新单。
     * 发票记录与文件本身不删——文件是上传凭证，删了没法追溯。
     */
    void unbindByReport(Long reportId);

    /** 按识别状态分页（GET /invoices?ocrStatus=MANUAL，docs/03 §2.5）：财务复核队列 */
    PageResult<InvoiceVO> listByStatus(String ocrStatus, int page, int size);

    /** 人工复核：修正字段并置 SUCCESS（FINANCE/ADMIN） */
    InvoiceVO review(Long id, InvoiceReviewDTO dto, Long reviewerId);

    /** 重试识别：MANUAL/FAILED → PENDING 并重新投递（FAILED 才有重试语义） */
    InvoiceVO retry(Long id);

    record InvoiceFile(Path path, String contentType, String fileName) {
    }
}
