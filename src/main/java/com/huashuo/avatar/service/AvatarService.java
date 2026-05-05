package com.huashuo.avatar.service;

import com.huashuo.avatar.dto.AvatarGenerateRequest;
import com.huashuo.avatar.dto.AvatarGenerateResponse;
import com.huashuo.avatar.dto.AvatarTaskDetailResponse;
import com.huashuo.avatar.dto.AvatarUpdateRequest;
import com.huashuo.avatar.vo.AvatarItem;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface AvatarService {

    AvatarItem upload(Long projectId, String avatarName, MultipartFile file);

    AvatarGenerateResponse generate(AvatarGenerateRequest request, String traceId);

    AvatarTaskDetailResponse getGenerateTask(Long taskId);

    List<AvatarItem> listProjectAvatars(Long projectId);

    AvatarItem getAvatar(Long avatarId);

    AvatarItem updateAvatar(Long avatarId, AvatarUpdateRequest request);
}
