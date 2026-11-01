package com.occ.smartexpense.invoice;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 识别结果校验层（docs/11 §3.3）：只判"能不能自动用"，不修数据。
 * 任何一条失败 → MANUAL，原始结果保留在 model_result，人工接手。
 */
@Component
public class InvoiceValidator {

    private static final int MAX_FIELD_LENGTH = 32;
    private static final int FUTURE_DAYS = 7;

    /** 返回问题码列表；空表示通过。问题码直接进 error_msg，便于统计失败原因 */
    public List<String> validate(InvoiceRecognizer.RecognitionResult result) {
        List<String> issues = new ArrayList<>();
        if (result == null) {
            issues.add("RESULT_EMPTY");
            return issues;
        }
        if (result.amount() == null || result.amount().compareTo(BigDecimal.ZERO) <= 0) {
            issues.add("AMOUNT_INVALID");
        }
        if (result.invoiceDate() == null) {
            issues.add("DATE_MISSING");
        } else if (result.invoiceDate().isAfter(LocalDate.now().plusDays(FUTURE_DAYS))) {
            issues.add("DATE_IN_FUTURE");
        }
        if (result.invoiceNo() == null || result.invoiceNo().isBlank()) {
            issues.add("NO_MISSING");
        }
        if (result.invoiceCode() != null && result.invoiceCode().length() > MAX_FIELD_LENGTH) {
            issues.add("CODE_TOO_LONG");
        }
        if (result.invoiceNo() != null && result.invoiceNo().length() > MAX_FIELD_LENGTH) {
            issues.add("NO_TOO_LONG");
        }
        return issues;
    }
}
