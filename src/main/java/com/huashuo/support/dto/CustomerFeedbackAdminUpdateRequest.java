package com.huashuo.support.dto;

import jakarta.validation.constraints.Size;

public class CustomerFeedbackAdminUpdateRequest {

    @Size(max = 30)
    private String status;

    @Size(max = 20)
    private String priority;

    @Size(max = 4000)
    private String adminReply;

    @Size(max = 4000)
    private String adminNote;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
        this.priority = priority;
    }

    public String getAdminReply() {
        return adminReply;
    }

    public void setAdminReply(String adminReply) {
        this.adminReply = adminReply;
    }

    public String getAdminNote() {
        return adminNote;
    }

    public void setAdminNote(String adminNote) {
        this.adminNote = adminNote;
    }
}
