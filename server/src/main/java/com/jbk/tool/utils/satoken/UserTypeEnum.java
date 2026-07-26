package com.jbk.tool.utils.satoken;

import com.jbk.tool.exception.JbkException;

public enum UserTypeEnum {
    KH_USER(1, "kh_user"),
    MANAGE(2, "manage"),
    ;

    private final int value;

    private final String desc;


    UserTypeEnum(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    public int getValue() {
        return value;
    }

    public String getDesc() {
        return desc;
    }

    public static UserTypeEnum getType(String type) {
        for (UserTypeEnum infoType : UserTypeEnum.values()) {
            if (infoType.getDesc().equals(type)) {
                return infoType;
            }
        }
        throw new JbkException("类型不存在");
    }
}


