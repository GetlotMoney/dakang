package com.jbk.tool.data.user.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.jbk.tool.data.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * 家庭资料（自愿信息，一人一份，uk_family_user 库层唯一）。
 * <p>PRIVACY_CONSENT_TIME 首次保存记服务端时间，后续更新不改写（同意审计位）。</p>
 *
 * @author dakang
 * @since 2026-08-02
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@TableName("ws_family_profile")
public class WsFamilyProfile extends BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "ID", type = IdType.AUTO)
    private Long id;

    /** 归属用户（ws_user.ID）；一人一份 */
    private Long userId;

    /** 隐私说明同意时间（首次保存记服务端时间，后续不改写） */
    private String privacyConsentTime;

    /** 家庭人数（自愿） */
    private Integer memberCount;

    /** 用水习惯备注(max200，自愿) */
    private String waterHabitNote;
}
