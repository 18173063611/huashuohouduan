package com.huashuo.support.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huashuo.admin.service.AdminOperationAuditService;
import com.huashuo.admin.service.AdminOperationContext;
import com.huashuo.common.exception.BusinessException;
import com.huashuo.common.response.PageResult;
import com.huashuo.storage.resolve.StoredUrlResolver;
import com.huashuo.support.dto.CustomerFeedbackAdminUpdateRequest;
import com.huashuo.support.dto.CustomerFeedbackCreateRequest;
import com.huashuo.support.entity.CustomerFeedbackEntity;
import com.huashuo.support.mapper.CustomerFeedbackMapper;
import com.huashuo.support.service.CustomerFeedbackService;
import com.huashuo.support.vo.CustomerFeedbackAttachmentItem;
import com.huashuo.support.vo.CustomerFeedbackItem;
import com.huashuo.upload.entity.UploadedFileEntity;
import com.huashuo.upload.mapper.UploadedFileMapper;
import com.huashuo.user.entity.UserAccountEntity;
import com.huashuo.user.mapper.UserAccountMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class CustomerFeedbackServiceImpl implements CustomerFeedbackService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_ATTACHMENT_COUNT = 8;
    private static final Set<String> CATEGORIES = Set.of(
            "BUG", "TASK_EXCEPTION", "FEATURE_REQUEST", "CONSULT", "CONTENT_COMPLAINT", "REFUND", "OTHER"
    );
    private static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");
    private static final Set<String> STATUSES = Set.of("OPEN", "IN_PROGRESS", "WAITING_USER", "RESOLVED", "CLOSED");
    private static final Set<String> TERMINAL_STATUSES = Set.of("RESOLVED", "CLOSED");

    private final CustomerFeedbackMapper customerFeedbackMapper;
    private final UploadedFileMapper uploadedFileMapper;
    private final UserAccountMapper userAccountMapper;
    private final StoredUrlResolver storedUrlResolver;
    private final AdminOperationAuditService auditService;

    public CustomerFeedbackServiceImpl(
            CustomerFeedbackMapper customerFeedbackMapper,
            UploadedFileMapper uploadedFileMapper,
            UserAccountMapper userAccountMapper,
            StoredUrlResolver storedUrlResolver,
            AdminOperationAuditService auditService
    ) {
        this.customerFeedbackMapper = customerFeedbackMapper;
        this.uploadedFileMapper = uploadedFileMapper;
        this.userAccountMapper = userAccountMapper;
        this.storedUrlResolver = storedUrlResolver;
        this.auditService = auditService;
    }

    @Override
    @Transactional
    public CustomerFeedbackItem create(long ownerUserId, CustomerFeedbackCreateRequest request) {
        if (request == null) {
            throw new BusinessException(40000, "反馈内容不能为空");
        }
        String content = trimToNull(request.getContent());
        List<Long> attachmentIds = normalizeAttachmentIds(request.getAttachmentFileIds());
        if (!StringUtils.hasText(content) && attachmentIds.isEmpty()) {
            throw new BusinessException(40000, "请填写反馈内容或上传附件");
        }
        validateAttachmentOwnership(ownerUserId, attachmentIds);

        CustomerFeedbackEntity entity = new CustomerFeedbackEntity();
        entity.setOwnerUserId(ownerUserId);
        entity.setCategory(normalizeEnum(request.getCategory(), CATEGORIES, "OTHER", "反馈类型不支持"));
        entity.setPriority(normalizeEnum(request.getPriority(), PRIORITIES, "NORMAL", "优先级不支持"));
        entity.setStatus("OPEN");
        entity.setTitle(defaultTitle(request.getTitle(), entity.getCategory(), content, attachmentIds));
        entity.setContent(StringUtils.hasText(content) ? content : "用户上传了附件反馈。");
        entity.setContact(trimToNull(request.getContact()));
        entity.setRelatedTaskId(positiveOrNull(request.getRelatedTaskId()));
        entity.setProjectId(positiveOrNull(request.getProjectId()));
        entity.setPageUrl(trimToNull(request.getPageUrl()));
        entity.setSourcePath(trimToNull(request.getSourcePath()));
        entity.setUserAgent(trimToNull(request.getUserAgent()));
        entity.setAttachmentFileIds(joinIds(attachmentIds));
        customerFeedbackMapper.insert(entity);
        return toItem(requireFeedback(entity.getFeedbackId()));
    }

    @Override
    public PageResult<CustomerFeedbackItem> listMine(long ownerUserId, Integer pageNo, Integer pageSize) {
        int page = safePageNo(pageNo);
        int size = safePageSize(pageSize);
        LambdaQueryWrapper<CustomerFeedbackEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CustomerFeedbackEntity::getOwnerUserId, ownerUserId);
        long total = customerFeedbackMapper.selectCount(wrapper);
        wrapper.orderByDesc(CustomerFeedbackEntity::getCreatedAt)
                .last("LIMIT " + ((long) (page - 1) * size) + "," + size);
        List<CustomerFeedbackItem> records = customerFeedbackMapper.selectList(wrapper).stream()
                .map(this::toItem)
                .toList();
        return new PageResult<>(records, page, size, total);
    }

    @Override
    public PageResult<CustomerFeedbackItem> listAdmin(Long ownerUserId, String category, String status, String priority,
                                                      String keyword, Integer pageNo, Integer pageSize) {
        int page = safePageNo(pageNo);
        int size = safePageSize(pageSize);
        LambdaQueryWrapper<CustomerFeedbackEntity> wrapper = new LambdaQueryWrapper<>();
        if (ownerUserId != null) {
            wrapper.eq(CustomerFeedbackEntity::getOwnerUserId, ownerUserId);
        }
        applyEnumFilter(wrapper, CustomerFeedbackEntity::getCategory, category, CATEGORIES);
        applyEnumFilter(wrapper, CustomerFeedbackEntity::getStatus, status, STATUSES);
        applyEnumFilter(wrapper, CustomerFeedbackEntity::getPriority, priority, PRIORITIES);
        if (StringUtils.hasText(keyword)) {
            String k = keyword.trim();
            wrapper.and(q -> q.like(CustomerFeedbackEntity::getTitle, k)
                    .or()
                    .like(CustomerFeedbackEntity::getContent, k)
                    .or()
                    .like(CustomerFeedbackEntity::getContact, k));
        }
        long total = customerFeedbackMapper.selectCount(wrapper);
        wrapper.last("""
                ORDER BY
                  CASE status
                    WHEN 'OPEN' THEN 1
                    WHEN 'IN_PROGRESS' THEN 2
                    WHEN 'WAITING_USER' THEN 3
                    WHEN 'RESOLVED' THEN 4
                    WHEN 'CLOSED' THEN 5
                    ELSE 6
                  END,
                  CASE priority
                    WHEN 'URGENT' THEN 1
                    WHEN 'HIGH' THEN 2
                    WHEN 'NORMAL' THEN 3
                    WHEN 'LOW' THEN 4
                    ELSE 5
                  END,
                  created_at DESC
                LIMIT """ + ((long) (page - 1) * size) + "," + size);
        List<CustomerFeedbackItem> records = customerFeedbackMapper.selectList(wrapper).stream()
                .map(this::toItem)
                .toList();
        return new PageResult<>(records, page, size, total);
    }

    @Override
    public CustomerFeedbackItem getAdmin(Long feedbackId) {
        return toItem(requireFeedback(feedbackId));
    }

    @Override
    @Transactional
    public CustomerFeedbackItem updateAdmin(Long feedbackId, CustomerFeedbackAdminUpdateRequest request,
                                            AdminOperationContext context) {
        if (request == null) {
            throw new BusinessException(40000, "更新内容不能为空");
        }
        CustomerFeedbackEntity before = requireFeedback(feedbackId);
        CustomerFeedbackEntity update = new CustomerFeedbackEntity();
        update.setFeedbackId(before.getFeedbackId());

        String status = normalizeOptionalEnum(request.getStatus(), STATUSES, "处理状态不支持");
        String priority = normalizeOptionalEnum(request.getPriority(), PRIORITIES, "优先级不支持");
        String adminReply = trimToNull(request.getAdminReply());
        String adminNote = trimToNull(request.getAdminNote());
        LocalDateTime now = LocalDateTime.now();

        if (status != null) {
            update.setStatus(status);
            if (TERMINAL_STATUSES.contains(status) && before.getResolvedAt() == null) {
                update.setResolvedAt(now);
            }
        }
        if (priority != null) {
            update.setPriority(priority);
        }
        update.setAdminReply(adminReply);
        update.setAdminNote(adminNote);
        update.setAssigneeAdminId(context == null ? null : context.adminUserId());
        if ((StringUtils.hasText(adminReply) || status != null && !"OPEN".equals(status))
                && before.getFirstResponseAt() == null) {
            update.setFirstResponseAt(now);
        }
        update.setUpdatedAt(now);
        customerFeedbackMapper.updateById(update);

        CustomerFeedbackEntity after = requireFeedback(feedbackId);
        auditService.record(context, "UPDATE_FEEDBACK", "CUSTOMER_FEEDBACK", feedbackId, toItem(before), toItem(after));
        return toItem(after);
    }

    private CustomerFeedbackEntity requireFeedback(Long feedbackId) {
        if (feedbackId == null || feedbackId <= 0) {
            throw new BusinessException(40000, "反馈ID不合法");
        }
        CustomerFeedbackEntity entity = customerFeedbackMapper.selectById(feedbackId);
        if (entity == null) {
            throw new BusinessException(40400, "反馈不存在");
        }
        return entity;
    }

    private void validateAttachmentOwnership(long ownerUserId, List<Long> attachmentIds) {
        for (Long fileId : attachmentIds) {
            UploadedFileEntity file = uploadedFileMapper.selectById(fileId);
            if (file == null) {
                throw new BusinessException(40000, "反馈附件不存在：" + fileId);
            }
            Long fileOwner = file.getOwnerUserId();
            if (fileOwner != null && fileOwner != ownerUserId) {
                throw new BusinessException(40300, "不能使用其他用户的附件：" + fileId);
            }
        }
    }

    private CustomerFeedbackItem toItem(CustomerFeedbackEntity entity) {
        UserAccountEntity user = entity.getOwnerUserId() == null ? null : userAccountMapper.selectById(entity.getOwnerUserId());
        List<Long> attachmentIds = parseIds(entity.getAttachmentFileIds());
        List<CustomerFeedbackAttachmentItem> attachments = attachmentIds.stream()
                .map(this::toAttachmentItem)
                .filter(item -> item != null)
                .toList();
        return new CustomerFeedbackItem(
                entity.getFeedbackId(),
                entity.getOwnerUserId(),
                user == null ? null : user.getUsername(),
                user == null ? null : user.getDisplayName(),
                entity.getCategory(),
                entity.getPriority(),
                entity.getStatus(),
                entity.getTitle(),
                entity.getContent(),
                entity.getContact(),
                entity.getRelatedTaskId(),
                entity.getProjectId(),
                entity.getPageUrl(),
                entity.getSourcePath(),
                entity.getUserAgent(),
                attachmentIds,
                attachments,
                entity.getAdminReply(),
                entity.getAdminNote(),
                entity.getAssigneeAdminId(),
                entity.getFirstResponseAt(),
                entity.getResolvedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private CustomerFeedbackAttachmentItem toAttachmentItem(Long fileId) {
        UploadedFileEntity file = uploadedFileMapper.selectById(fileId);
        if (file == null) {
            return null;
        }
        String previewUrl = file.getPreviewUrl();
        boolean local = file.getFilePath() != null && file.getFilePath().startsWith("local:");
        return new CustomerFeedbackAttachmentItem(
                file.getFileId(),
                file.getOriginalFileName(),
                local ? previewUrl : storedUrlResolver.resolveToPublicUrl(previewUrl),
                file.getMimeType(),
                file.getFileSize(),
                file.getCreatedAt()
        );
    }

    private List<Long> normalizeAttachmentIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        for (Long id : ids) {
            if (id != null && id > 0) {
                normalized.add(id);
            }
        }
        if (normalized.size() > MAX_ATTACHMENT_COUNT) {
            throw new BusinessException(40000, "最多上传 " + MAX_ATTACHMENT_COUNT + " 个反馈附件");
        }
        return new ArrayList<>(normalized);
    }

    private String defaultTitle(String rawTitle, String category, String content, List<Long> attachmentIds) {
        String title = trimToNull(rawTitle);
        if (StringUtils.hasText(title)) {
            return title;
        }
        if (StringUtils.hasText(content)) {
            String oneLine = content.replace('\n', ' ').replace('\r', ' ').trim();
            return oneLine.length() > 32 ? oneLine.substring(0, 32) + "..." : oneLine;
        }
        return "附件反馈 - " + category + "（" + attachmentIds.size() + " 个附件）";
    }

    private String normalizeEnum(String value, Set<String> allowed, String fallback, String errorMessage) {
        String normalized = normalizeOptionalEnum(value, allowed, errorMessage);
        return normalized == null ? fallback : normalized;
    }

    private String normalizeOptionalEnum(String value, Set<String> allowed, String errorMessage) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new BusinessException(40000, errorMessage + "：" + value);
        }
        return normalized;
    }

    private <T> void applyEnumFilter(LambdaQueryWrapper<CustomerFeedbackEntity> wrapper,
                                     com.baomidou.mybatisplus.core.toolkit.support.SFunction<CustomerFeedbackEntity, T> column,
                                     String value, Set<String> allowed) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (allowed.contains(normalized)) {
            wrapper.eq(column, normalized);
        }
    }

    private int safePageNo(Integer pageNo) {
        return pageNo == null || pageNo < 1 ? 1 : pageNo;
    }

    private int safePageSize(Integer pageSize) {
        return pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private Long positiveOrNull(Long value) {
        return value != null && value > 0 ? value : null;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String joinIds(List<Long> ids) {
        return ids == null || ids.isEmpty() ? null : String.join(",", ids.stream().map(String::valueOf).toList());
    }

    private List<Long> parseIds(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        return Arrays.stream(text.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(Long::parseLong)
                .toList();
    }
}
