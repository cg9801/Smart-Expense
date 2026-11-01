package com.occ.smartexpense.controller;

import com.occ.smartexpense.common.PageResult;
import com.occ.smartexpense.common.Result;
import com.occ.smartexpense.dto.InvoiceReviewDTO;
import com.occ.smartexpense.security.RequireRole;
import com.occ.smartexpense.security.UserContext;
import com.occ.smartexpense.service.InvoiceService;
import com.occ.smartexpense.vo.InvoiceVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 发票接口（v2 方案 A：上传落库 + 文件读取；识别链路见 docs/11）。
 * 契约：docs/03-接口清单.md §2.5。
 */
@RestController
@RequestMapping("/api/v1/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;

    /** 上传发票：multipart，字段名 file；同人同 hash 直接返回已有记录（docs/11 §3.4 情形 1）。 */
    @PostMapping("/upload")
    public Result<InvoiceVO> upload(@RequestParam("file") MultipartFile file) {
        Long uploaderId = UserContext.requireUserId();
        return Result.ok(invoiceService.upload(file, uploaderId));
    }

    @GetMapping("/{id}")
    public Result<InvoiceVO> getDetail(@PathVariable Long id) {
        UserContext.requireUserId();
        return Result.ok(invoiceService.get(id));
    }

    /**
     * 复核队列（docs/03 §2.5）：默认拉 MANUAL，财务/管理员用。
     * 分页，按创建时间倒序，最新上传的排前面。
     */
    @GetMapping
    @RequireRole({"FINANCE", "ADMIN"})
    public Result<PageResult<InvoiceVO>> listByStatus(
            @RequestParam(defaultValue = "MANUAL") String ocrStatus,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(invoiceService.listByStatus(ocrStatus, page, size));
    }

    /** 人工复核：修正金额/日期/号码后置 SUCCESS（docs/03 §2.5） */
    @PutMapping("/{id}/review")
    @RequireRole({"FINANCE", "ADMIN"})
    public Result<InvoiceVO> review(@PathVariable Long id, @Valid @RequestBody InvoiceReviewDTO dto) {
        Long reviewerId = UserContext.requireUserId();
        return Result.ok(invoiceService.review(id, dto, reviewerId));
    }

    /** 重试识别：MANUAL/FAILED 时可用，重新投递识别事件（docs/11 §3.5） */
    @PostMapping("/{id}/retry")
    @RequireRole({"FINANCE", "ADMIN"})
    public Result<InvoiceVO> retry(@PathVariable Long id) {
        return Result.ok(invoiceService.retry(id));
    }

    /** 读取发票文件流；前端用 fetch + blob 渲染，避免 token 进 URL。 */
    @GetMapping("/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable Long id) {
        UserContext.requireUserId();
        InvoiceService.InvoiceFile file = invoiceService.file(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(file.fileName()))
                .body(new FileSystemResource(file.path()));
    }

    /**
     * HTTP 头只能是 ISO-8859-1，原始中文文件名塞进 Content-Disposition 会被 Tomcat 丢弃
     * （日志报 UnmappableCharacterException，下载文件名也会乱）。用 RFC 5987 的 filename*。
     */
    private String contentDisposition(String fileName) {
        String safe = fileName == null || fileName.isBlank() ? "invoice" : fileName;
        String encoded = URLEncoder.encode(safe, StandardCharsets.UTF_8).replace("+", "%20");
        return "inline; filename*=UTF-8''" + encoded;
    }
}
