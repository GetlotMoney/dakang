package com.jbk.tool.utils;


import cn.hutool.core.util.ObjectUtil;
import com.jbk.tool.exception.JbkException;

import java.util.Optional;

/**
 * @ClassName OptionalUtils
 * @Author xs
 * @Date 2025/3/6 14:34
 * @Version 1.0
 */
public class OptionalUtils {
    public static void trueToElseThrow(boolean bool, String msg) {
        if (bool) {
            throw new JbkException(msg);
        }
    }

    public static void falseToElseThrow(boolean bool, String msg) {
        if (!bool) {
            throw new JbkException(msg);
        }
    }

    public static <T> T emptyToElseThrow(T t, String msg) {
        if (ObjectUtil.isEmpty(t)) {
            throw new JbkException(msg);
        }
        return t;
    }

    public static <T> T nullToElseThrow(T t, String msg) {
        return Optional.ofNullable(t).orElseThrow(() -> new JbkException(msg));
    }

    public static <T> T leZeroElseThrow(T count, String msg) {
        if (count instanceof Long) {
            if ((Long) count <= 0L) {
                throw new JbkException(msg);
            }
        } else if (count instanceof Integer) {
            if ((Integer) count <= 0) {
                throw new JbkException(msg);
            }
        } else if (Long.valueOf(String.valueOf(count)).longValue() <= 0L) {
            throw new JbkException(msg);
        }
        return count;
    }

    public static <T> T gtZeroElseThrow(T count, String msg) {
        if (count instanceof Long) {
            if ((Long) count > 0L) {
                throw new JbkException(msg);
            }
        } else if (count instanceof Integer) {
            if ((Integer) count > 0L) {
                throw new JbkException(msg);
            }
        } else if (Long.valueOf(String.valueOf(count)).longValue() > 0L) {
            throw new JbkException(msg);
        }
        return count;
    }
}


