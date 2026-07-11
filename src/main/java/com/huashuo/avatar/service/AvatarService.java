package com.huashuo.avatar.service;

import com.huashuo.avatar.dto.AvatarGenerateRequest;
import com.huashuo.avatar.dto.AvatarGenerateResponse;
import com.huashuo.avatar.dto.AvatarTaskDetailResponse;
import com.huashuo.avatar.dto.AvatarUpdateRequest;
import com.huashuo.avatar.vo.AvatarItem;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.OptionalLong;

public interface AvatarService {

    AvatarItem upload(Long projectId, String avatarName, MultipartFile file, Long ownerUserId,
                      String businessDomain);

    AvatarGenerateResponse generate(AvatarGenerateRequest request, String traceId, Long requestingUserId,
                                      String idempotencyKey);

    AvatarTaskDetailResponse getGenerateTask(Long taskId, OptionalLong viewerUserId);

    default List<AvatarItem> listProjectAvatars(Long projectId, OptionalLong viewerUserId) {
        return listProjectAvatars(projectId, viewerUserId, null);
    }

    List<AvatarItem> listProjectAvatars(Long projectId, OptionalLong viewerUserId, String businessDomain);

    AvatarItem getAvatar(Long avatarId, OptionalLong viewerUserId);

    AvatarItem updateAvatar(Long avatarId, AvatarUpdateRequest request, OptionalLong viewerUserId);

    void deleteAvatar(Long avatarId, OptionalLong viewerUserId);
}
