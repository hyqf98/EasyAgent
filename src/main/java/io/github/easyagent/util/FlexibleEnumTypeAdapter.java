package io.github.easyagent.util;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import lombok.extern.slf4j.Slf4j;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 通用灵活枚举反序列化器。
 * <p>
 * 支持 JSON 中的字符串值和整数值自动转换为 Java 枚举：
 * <ul>
 * <li>字符串值：通过枚举名称匹配（忽略大小写、横杠转下划线）</li>
 * <li>整数值：通过枚举上 {@link EnumValue} 注解的序号匹配</li>
 * </ul>
 * </p>
 *
 * <h3>字符串匹配规则</h3>
 * <pre>
 * "step_start" → STEP_START
 * "step-start" → STEP_START
 * "StepStart"  → STEP_START
 * </pre>
 *
 * <h3>整数匹配规则</h3>
 * <p>在枚举字段上使用 {@link EnumValue} 注解指定数值：</p>
 * <pre>
 * &#64;EnumValue(1)
 * AGENT_MESSAGE
 * </pre>
 *
 * @author haijun
 * @param <E> e
 * @date 2026/4/30 09:48
 * @since 1.0.0
 */
@Slf4j
public class FlexibleEnumTypeAdapter<E extends Enum<E>> implements JsonDeserializer<E>, JsonSerializer<E> {

    /**
     * 枚举类。
     */
    private final Class<E> enumClass;

    /**
     * 名称到枚举实例的映射。
     */
    private final Map<String, E> nameToEnum;

    /**
     * 数值到枚举实例的映射。
     */
    private final Map<Integer, E> valueToEnum;

    /**
     * 枚举值注解，用于标记整数类型的 JSON 值映射。
     *
     * @author haijun
     * @date 2026/4/30 09:48
     * @since 1.0.0
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    public @interface EnumValue {
        /**
         * 枚举对应的整数值。
         *
         * @return 整数值
         * @since 1.0.0
         */
        int value();
    }

    /**
     * 构造指定枚举类型的适配器。
     *
     * @param enumClass 枚举类
     * @since 1.0.0
     */
    public FlexibleEnumTypeAdapter(Class<E> enumClass) {
        this.enumClass = enumClass;
        this.nameToEnum = new HashMap<>();
        this.valueToEnum = new HashMap<>();

        for (E constant : enumClass.getEnumConstants()) {
            this.nameToEnum.put(constant.name().toLowerCase(Locale.ROOT), constant);
            this.nameToEnum.put(constant.name().toLowerCase(Locale.ROOT).replace("_", "-"), constant);

            EnumValue enumValue = null;
            try {
                enumValue = enumClass.getField(constant.name()).getAnnotation(EnumValue.class);
            } catch (NoSuchFieldException ignored) {
            }
            if (enumValue != null) {
                this.valueToEnum.put(enumValue.value(), constant);
            }
        }
    }

    /**
     * 反序列化 JSON 元素为枚举实例。
     *
     * @param json    待解析的 JSON 元素
     * @param typeOfT 目标类型
     * @param context 反序列化上下文
     * @return 匹配的枚举实例，无法匹配时返回 {@code null}
     * @throws JsonParseException JSON 解析异常
     * @since 1.0.0
     */
    @Override
    public E deserialize(JsonElement json,
                         Type typeOfT,
                         JsonDeserializationContext context) throws JsonParseException {
        if (json == null || json.isJsonNull()) {
            return null;
        }

        if (json.isJsonPrimitive()) {
            JsonPrimitive prim = json.getAsJsonPrimitive();

            if (prim.isString()) {
                String value = prim.getAsString().toLowerCase(Locale.ROOT).replace("-", "_");
                E result = this.nameToEnum.get(value);
                if (result != null) {
                    return result;
                }
            }

            if (prim.isNumber()) {
                int num = prim.getAsInt();
                E result = this.valueToEnum.get(num);
                if (result != null) {
                    return result;
                }
            }
        }

        String jsonStr = json.toString();
        log.warn("Unknown enum value for {}: {}", this.enumClass.getSimpleName(), jsonStr);
        return null;
    }

    /**
     * 序列化枚举实例为 JSON 元素。
     *
     * @param src         枚举实例
     * @param typeOfSrc   源类型
     * @param context     序列化上下文
     * @return 对应的 JSON 元素
     * @since 1.0.0
     */
    @Override
    public JsonElement serialize(E src,
                                 Type typeOfSrc,
                                 JsonSerializationContext context) {
        if (src == null) {
            return JsonNull.INSTANCE;
        }
        EnumValue enumValue = null;
        try {
            enumValue = this.enumClass.getField(src.name()).getAnnotation(EnumValue.class);
        } catch (NoSuchFieldException ignored) {
        }
        if (enumValue != null) {
            return new JsonPrimitive(enumValue.value());
        }
        return new JsonPrimitive(src.name().toLowerCase(Locale.ROOT).replace("_", "-"));
    }

    /**
     * 用于注册多个枚举类型的 TypeAdapterFactory。
     *
     * @param <E> 枚举类型
     * @author haijun
     * @date 2026/4/30 09:48
     * @since 1.0.0
     */
    @FunctionalInterface
    public interface EnumRegistrar {
        /**
         * 注册枚举类型的适配器。
         *
         * @param enumClass 枚举类
         * @param <E>       枚举泛型
         * @since 1.0.0
         */
        <E extends Enum<E>> void register(Class<E> enumClass);
    }
}
