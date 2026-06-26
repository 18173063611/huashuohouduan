package com.huashuo.user.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.huashuo.user.entity.ActivateCodeEntity;
import com.huashuo.user.mapper.ActivateCodeMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping({"/api/v1/admin/activation", "/api/v1/activation"})
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class ActivationController {

    private final ActivateCodeMapper activateCodeMapper;

    @PostMapping("/activate-codes")
    public boolean addActivateCode(@RequestBody ActivateCodeAddRequest request) {
        ActivateCodeEntity entity = new ActivateCodeEntity();
        entity.setKey(request.getKey());
        entity.setStatus(ActivateCodeEntity.STATUS_UNUSED);
        return activateCodeMapper.insert(entity) > 0;
    }

    @DeleteMapping("/activate-codes/{id}")
    public boolean deleteActivateCode(@PathVariable Integer id) {
        return activateCodeMapper.deleteById(id) > 0;
    }

    @PatchMapping("/activate-codes/{id}/disable")
    public boolean disableActivateCode(@PathVariable Integer id) {
        ActivateCodeEntity entity = new ActivateCodeEntity();
        entity.setId(id);
        entity.setStatus(ActivateCodeEntity.STATUS_USED);
        return activateCodeMapper.updateById(entity) > 0;
    }

    @GetMapping("/activate-codes")
    public List<ActivateCodeEntity> listActivateCodes() {
        return activateCodeMapper.selectList(
                new LambdaQueryWrapper<ActivateCodeEntity>()
                        .orderByDesc(ActivateCodeEntity::getId)
        );
    }

    @GetMapping("/activate-codes/{id}")
    public ActivateCodeEntity getActivateCode(@PathVariable Integer id) {
        return activateCodeMapper.selectById(id);
    }

    @Data
    public static class ActivateCodeAddRequest {
        private String key;
    }
}
