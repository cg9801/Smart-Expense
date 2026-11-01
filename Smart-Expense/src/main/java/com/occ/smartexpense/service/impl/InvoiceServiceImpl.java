package com.occ.smartexpense.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.occ.smartexpense.common.BizException;
import com.occ.smartexpense.common.ErrorCode;
import com.occ.smartexpense.common.PageResult;
import com.occ.smartexpense.dto.InvoiceReviewDTO;
import com.occ.smartexpense.entity.Invoice;
import com.occ.smartexpense.mapper.InvoiceMapper;
import com.occ.smartexpense.service.InvoiceRecognitionService;
import com.occ.smartexpense.service.InvoiceService;
import com.occ.smartexpense.vo.InvoiceFieldVO;
import com.occ.smartexpense.vo.InvoiceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 发票上传实现（docs/11 §3.4 情形 1：同人同 file_hash 复用）。
 * 方案 A：文件落本地 uploads 目录、ocr_status 直接 MANUAL，识别链路后置。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceServiceImpl implements InvoiceService {

    /** 上传后进入识别队列（docs/11 §3.1）：PENDING → PROCESSING → SUCCESS/MANUAL/FAILED */
    private static final String OCR_PENDING = "PENDING";
    private static final DateTimeFormatter MONTH_DIR = DateTimeFormatter.ofPattern("yyyyMM");
    private static final HexFormat HEX = HexFormat.of();
    /** 只允许常见图片与 PDF，避免把任意文件塞进存储目录 */
    private static final List<String> ALLOWED_EXT = List.of("png", "jpg", "jpeg", "gif", "webp", "bmp", "pdf");

    private final InvoiceMapper invoiceMapper;
    private final InvoiceRecognitionService recognitionService;
    private final ObjectMapper objectMapper;

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InvoiceVO upload(MultipartFile file, Long uploaderId) {        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "发票文件不能为空");
        }
        String originalName = file.getOriginalFilename() == null ? "invoice" : file.getOriginalFilename();
        String ext = extensionOf(originalName);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "只支持图片或 PDF 文件");
        }
        // 扩展名可以随便改，再验一次文件头魔数（docs/11 §3.4）
        verifyMagic(file, ext);

        String hash = sha256(file);
        // 情形 1：同一个人重复上传同一张图 → 直接返回已有记录，不重复落库（docs/11 §3.4）
        Invoice existing = invoiceMapper.selectOne(new LambdaQueryWrapper<Invoice>()
                .eq(Invoice::getFileHash, hash)
                .eq(Invoice::getUploaderId, uploaderId));
        if (existing != null) {
            return toVO(existing);
        }
        // 情形 2（docs/11 §3.4）：跨人同 hash 允许新建，索引已改为 (uploader_id, file_hash)。
        // 疑似一票多报不在这里拒绝，由 duplicateSuspect 标记后交审批人判断。

        String relativeKey = LocalDate.now().format(MONTH_DIR) + "/" + UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = storageRoot().resolve(relativeKey).normalize();
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target);
            }
        } catch (IOException e) {
            log.error("发票文件写入失败，key={}", relativeKey, e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "发票文件保存失败");
        }

        Invoice invoice = new Invoice();
        invoice.setFileUrl(relativeKey);
        invoice.setFileHash(hash);
        invoice.setOriginalName(originalName);
        invoice.setOcrStatus(OCR_PENDING);
        invoice.setRetryCount(0);
        invoice.setUploaderId(uploaderId);
        try {
            invoiceMapper.insert(invoice);
        } catch (DuplicateKeyException e) {
            // 并发同图：唯一键兜底后回查已有记录，保持幂等
            Invoice same = invoiceMapper.selectOne(new LambdaQueryWrapper<Invoice>()
                    .eq(Invoice::getFileHash, hash)
                    .eq(Invoice::getUploaderId, uploaderId));
            if (same != null) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException ignored) {
                    // 清理失败不影响结果，文件后续由巡检处理
                }
                return toVO(same);
            }
            throw new BizException(ErrorCode.STATE_ILLEGAL, "发票上传冲突，请重试");
        }
        // 与上传同事务写 Outbox：消息一定投出去，识别在消费者里做（docs/11 §3.1）
        recognitionService.enqueue(invoice.getId());
        return toVO(invoice);
    }

    /**
     * 校验文件头魔数（docs/11 §3.4）：扩展名与 Content-Type 都是客户端可伪造的，
     * 只有文件头能证明"这是不是一张图/PDF"，避免把 .html 或可执行文件塞进存储目录。
     */
    private void verifyMagic(MultipartFile file, String ext) {
        byte[] head;
        try (InputStream in = file.getInputStream()) {
            head = in.readNBytes(12);
        } catch (IOException e) {
            throw new BizException(ErrorCode.PARAM_ERROR, "发票文件读取失败，请重新上传");
        }
        boolean ok = switch (ext) {
            case "png" -> head.length >= 8 && (head[0] & 0xFF) == 0x89
                    && head[1] == 'P' && head[2] == 'N' && head[3] == 'G';
            case "jpg", "jpeg" -> head.length >= 3 && (head[0] & 0xFF) == 0xFF
                    && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF;
            case "gif" -> head.length >= 3 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F';
            case "bmp" -> head.length >= 2 && head[0] == 'B' && head[1] == 'M';
            case "webp" -> head.length >= 12
                    && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                    && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
            case "pdf" -> head.length >= 4
                    && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F';
            default -> false;
        };
        if (!ok) {
            throw new BizException(ErrorCode.PARAM_ERROR, "文件内容与扩展名不符，请上传真实的图片或 PDF");
        }
    }

    @Override
    public InvoiceVO get(Long id) {
        Invoice invoice = invoiceMapper.selectById(id);
        if (invoice == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "发票不存在");
        }
        return toVO(invoice);
    }

    @Override
    public InvoiceFile file(Long id) {
        Invoice invoice = invoiceMapper.selectById(id);
        if (invoice == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "发票不存在");
        }
        Path path = storageRoot().resolve(invoice.getFileUrl()).normalize();
        if (!path.startsWith(storageRoot())) {
            // 防路径穿越：file_url 是服务端生成的相对键，仍做一次兜底
            throw new BizException(ErrorCode.PARAM_ERROR, "非法文件路径");
        }
        if (!Files.exists(path)) {
            throw new BizException(ErrorCode.NOT_FOUND, "发票文件不存在");
        }
        return new InvoiceFile(path, contentTypeOf(invoice.getFileUrl()),
                invoice.getOriginalName() == null ? "invoice" : invoice.getOriginalName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindReport(Long invoiceId, Long reportId, Long applicantId) {
        Invoice invoice = invoiceMapper.selectById(invoiceId);
        if (invoice == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "发票不存在");
        }
        if (!invoice.getUploaderId().equals(applicantId)) {
            throw new BizException(ErrorCode.FORBIDDEN, "只能关联本人上传的发票");
        }
        if (invoice.getReportId() != null && !invoice.getReportId().equals(reportId)) {
            throw new BizException(ErrorCode.STATE_ILLEGAL, "该发票已关联其他报销单");
        }
        invoiceMapper.update(null, new LambdaUpdateWrapper<Invoice>()
                .eq(Invoice::getId, invoiceId)
                .set(Invoice::getReportId, reportId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unbindByReport(Long reportId) {
        invoiceMapper.update(null, new LambdaUpdateWrapper<Invoice>()
                .eq(Invoice::getReportId, reportId)
                .set(Invoice::getReportId, null));
    }

    @Override
    public PageResult<InvoiceVO> listByStatus(String ocrStatus, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        String status = ocrStatus == null || ocrStatus.isBlank()
                ? null : ocrStatus.trim().toUpperCase(Locale.ROOT);
        long total = invoiceMapper.countByStatus(status);
        if (total == 0) {
            return PageResult.empty(p, s);
        }
        List<InvoiceVO> records = invoiceMapper.selectPageByStatus(status, (p - 1) * s, s)
                .stream().map(this::toVO).toList();
        return PageResult.of(records, total, p, s);
    }

    /**
     * 人工复核（docs/03 §2.5）：修正字段后置 SUCCESS。
     * 只允许 MANUAL/SUCCESS —— FAILED 的正确动作是重试识别，不是手填（手填可用重试后再复核）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public InvoiceVO review(Long id, InvoiceReviewDTO dto, Long reviewerId) {
        Invoice invoice = invoiceMapper.selectById(id);
        if (invoice == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "发票不存在");
        }
        String status = invoice.getOcrStatus();
        if (!"MANUAL".equals(status) && !"SUCCESS".equals(status)) {
            throw new BizException(ErrorCode.STATE_ILLEGAL, "只有待复核/已识别的发票可以人工修正");
        }
        if (dto.getInvoiceDate() != null && dto.getInvoiceDate().isAfter(LocalDate.now().plusDays(7))) {
            throw new BizException(ErrorCode.PARAM_ERROR, "发票日期不能晚于当前日期 7 天");
        }
        int rows = invoiceMapper.update(null, new LambdaUpdateWrapper<Invoice>()
                .eq(Invoice::getId, id)
                .in(Invoice::getOcrStatus, "MANUAL", "SUCCESS")
                .set(Invoice::getInvoiceCode, dto.getInvoiceCode())
                .set(Invoice::getInvoiceNo, dto.getInvoiceNo())
                .set(Invoice::getInvoiceDate, dto.getInvoiceDate())
                .set(Invoice::getAmount, dto.getAmount())
                .set(Invoice::getTaxAmount, dto.getTaxAmount())
                .set(Invoice::getOcrStatus, "SUCCESS")
                .set(Invoice::getConfidence, new BigDecimal("1.000"))
                .set(Invoice::getErrorMsg, null)
                .set(Invoice::getReviewedBy, reviewerId)
                .set(Invoice::getReviewedAt, LocalDateTime.now()));
        if (rows == 0) {
            throw new BizException(ErrorCode.STATE_ILLEGAL, "发票状态已变化，请刷新后重试");
        }
        return get(id);
    }

    /** 重试识别（docs/03 §2.5）：MANUAL/FAILED → PENDING，重新走异步链路 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public InvoiceVO retry(Long id) {
        Invoice invoice = invoiceMapper.selectById(id);
        if (invoice == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "发票不存在");
        }
        int rows = invoiceMapper.update(null, new LambdaUpdateWrapper<Invoice>()
                .eq(Invoice::getId, id)
                .in(Invoice::getOcrStatus, "MANUAL", "FAILED")
                .set(Invoice::getOcrStatus, "PENDING")
                .set(Invoice::getErrorMsg, null)
                .set(Invoice::getRecognizer, null)
                .set(Invoice::getDegraded, 0)
                .setSql("retry_count = retry_count + 1"));
        if (rows == 0) {
            throw new BizException(ErrorCode.STATE_ILLEGAL, "只有待复核/识别失败的发票可以重试");
        }
        recognitionService.enqueue(id);
        return get(id);
    }

    @Override
    public List<InvoiceVO> listByReport(Long reportId) {
        return invoiceMapper.selectList(new LambdaQueryWrapper<Invoice>()
                        .eq(Invoice::getReportId, reportId)
                        .orderByAsc(Invoice::getId))
                .stream()
                .map(this::toVO)
                .toList();
    }

    private InvoiceVO toVO(Invoice invoice) {
        InvoiceVO vo = new InvoiceVO();
        vo.setId(invoice.getId());
        vo.setReportId(invoice.getReportId());
        vo.setOriginalName(invoice.getOriginalName());
        vo.setInvoiceCode(invoice.getInvoiceCode());
        vo.setInvoiceNo(invoice.getInvoiceNo());
        vo.setInvoiceDate(invoice.getInvoiceDate());
        vo.setAmount(invoice.getAmount());
        vo.setTaxAmount(invoice.getTaxAmount());
        vo.setOcrStatus(invoice.getOcrStatus());
        vo.setConfidence(invoice.getConfidence());
        vo.setErrorMsg(invoice.getErrorMsg());
        vo.setRecognizer(invoice.getRecognizer());
        vo.setDegraded(invoice.getDegraded());
        vo.setRetryCount(invoice.getRetryCount());
        vo.setCreatedAt(invoice.getCreatedAt());
        readFields(invoice, vo);
        // 跨人同图：标记疑似一票多报，交给审批人判断（docs/11 §3.4 情形 2）
        if (invoice.getFileHash() != null) {
            Long others = invoiceMapper.selectCount(new LambdaQueryWrapper<Invoice>()
                    .eq(Invoice::getFileHash, invoice.getFileHash())
                    .ne(Invoice::getUploaderId, invoice.getUploaderId()));
            vo.setDuplicateSuspect(others != null && others > 0);
        }
        return vo;
    }

    /** model_result 里存 {fields:[{name,value,confidence,source}], needsReview:[], raw} */
    private void readFields(Invoice invoice, InvoiceVO vo) {
        List<InvoiceFieldVO> fields = new ArrayList<>();
        List<String> needsReview = new ArrayList<>();
        if (invoice.getModelResult() != null && !invoice.getModelResult().isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(invoice.getModelResult());
                for (JsonNode f : node.path("fields")) {
                    InvoiceFieldVO field = new InvoiceFieldVO();
                    field.setName(f.path("name").asText(null));
                    field.setValue(f.path("value").asText(null));
                    field.setConfidence(f.path("confidence").isNull()
                            ? null : new BigDecimal(f.path("confidence").asText()));
                    field.setSource(f.path("source").asText(null));
                    fields.add(field);
                }
                for (JsonNode n : node.path("needsReview")) {
                    needsReview.add(n.asText());
                }
            } catch (Exception e) {
                log.warn("发票 {} 的 model_result 解析失败：{}", invoice.getId(), e.getMessage());
            }
        }
        vo.setFields(fields);
        vo.setNeedsReview(needsReview);
    }

    private Path storageRoot() {
        return Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    private String sha256(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HEX.formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "发票文件读取失败");
        }
    }

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String contentTypeOf(String key) {
        String ext = extensionOf(key);
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "pdf" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }
}
