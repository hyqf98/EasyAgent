package io.github.easyagent.enums;

/**
 * 基于值的枚举通用接口。
 * <p>
 * 所有具有 {@code value} 字段的枚举类型均应实现此接口。
 * 反查枚举实例统一通过 {@link #fromValue(Class, Object)} 静态泛型方法完成，
 * 各枚举不再自行定义 {@code fromValue()} 方法。
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
     *
     * @param <E>       枚举类型
     * @param <V>       值类型
     * @param enumClass 枚举类
     * @param value     要查找的值
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
        if (value instanceof String strValue) {
            for (E constant : enumClass.getEnumConstants()) {
                if (constant.name().equalsIgnoreCase(strValue)) {
                    return constant;
                }
            }
        }
        return null;
    }
}
