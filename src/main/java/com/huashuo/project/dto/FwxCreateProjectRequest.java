package com.huashuo.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FwxCreateProjectRequest(
        @NotBlank(message = "项目名称不能为空")
        @Size(max = 80, message = "项目名称不能超过 80 个字符")
        String projectName,

        @Size(max = 500, message = "项目描述不能超过 500 个字符")
        String description
) {
}
