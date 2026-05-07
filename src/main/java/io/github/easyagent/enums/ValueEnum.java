package io.github.easyagent.enums;

/**
 * 基于值的枚举通用接口。
 * <p>
 * 提供枚举类型的通用值获取和根据值反查枚举实例的默认方法。
 * 所有具有 {@code value} 字段的枚举类型均应实现此接口，
 * 以消除各枚举中重复的 {@code fromValue()} 静态方法。
 * </p>
 *
 * @param <V> 值的类型
 * @author haijun
 * @date 2026/5/6
 * @since 1.0.0
 */
public interface ValueEnum<V> {

    /**
     * 获取枚举实例的关联值。
     *
     * @return 关联值
     */
    V getValue();

    /**
     * 根据值在指定枚举类型中查找匹配的枚举实例。
     * <p>
     * 默认实现遍历所有枚举实例，比较 {@link #getValue()} 与给定值。
     * 由于 Java 接口静态方法无法做到真正的泛型推导，此方法由各枚举
     * 自行提供静态 {@code fromValue} 方法并委托此默认方法实现。
     * </p>
     *
     * @param <E>   枚举类型
     * @param <V>   值类型
     * @param enumClass 枚举类
     * @param value 要查找的值
     * @return 匹配的枚举实例，未找到时返回 {@code null}
     */
    static <E extends Enum<E> & ValueEnum<V>, V> E fromValue(Class<E> enumClass, V value) {
        if (value == null) {
            return null;
        }
        for (E constant : enumClass.getEnumConstants()) {
            if (constant.getValue().equals(value)) {
                return constant;
            }
        }
        return null;
    }
}
