package com.huashuo.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("activate_code")
public class ActivateCodeEntity {

    public static final int STATUS_UNUSED = 1;
    public static final int STATUS_USED = 0;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    @TableField("`key`")
    private String key;


    private Integer status;  // 0: 已使用 1: 未使用

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
