package com.huashuo.user.service;

import java.util.List;

public interface UserFeaturePermissionService {

    String PET_CREATION_ACCESS = "PET_CREATION_ACCESS";
    String VEHICLE_CREATION_ACCESS = "VEHICLE_CREATION_ACCESS";
    String PET_PUBLIC_ASSET_EDITOR = "PET_PUBLIC_ASSET_EDITOR";

    List<String> listPermissionCodes(Long userId);

    boolean hasPermission(Long userId, String permissionCode);

    void assertPetCreationAccess(Long userId);
}
