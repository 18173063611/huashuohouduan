package com.huashuo.admin.service;

public interface AdminAccessService {

    boolean isAdmin(Long userId);

    String roleOf(Long userId, String username);

    void requireAdmin(Long userId);
}
