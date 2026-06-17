package com.huashuo.support.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

public class CustomerFeedbackCreateRequest {

    @Size(max = 40)
    private String category;

    @Size(max = 20)
    private String priority;

    @Size(max = 120)
    private String title;

    @Size(max = 4000)
    private String content;

    @Size(max = 120)
    private String contact;

    private Long relatedTaskId;

    private Long projectId;

    @Size(max = 1000)
    private String pageUrl;

    @Size(max = 255)
    private String sourcePath;

    @Size(max = 500)
    private String userAgent;

    @Size(max = 8)
    private List<Long> attachmentFileIds;

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getContact() {
        return contact;
    }

    public void setContact(String contact) {
        this.contact = contact;
    }

    public Long getRelatedTaskId() {
        return relatedTaskId;
    }

    public void setRelatedTaskId(Long relatedTaskId) {
        this.relatedTaskId = relatedTaskId;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getPageUrl() {
        return pageUrl;
    }

    public void setPageUrl(String pageUrl) {
        this.pageUrl = pageUrl;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public List<Long> getAttachmentFileIds() {
        return attachmentFileIds;
    }

    public void setAttachmentFileIds(List<Long> attachmentFileIds) {
        this.attachmentFileIds = attachmentFileIds;
    }
}
