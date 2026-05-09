package io.github.easyagent.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.containers.ContainerUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * JSON 工具类。
 * <p>
 * 基于 IntelliJ 内置的 {@link Gson} 封装常用的 JSON 解析、转换和安全取值操作，
 * 所有 CLI 提供者统一使用此工具类处理 JSON。
 * 内置 {@link FlexibleEnumTypeAdapter} 支持字符串和整数自动转换为枚举。
 * </p>
 *
 * @author haijun
 * @date 2026/4/30
 * @since 1.0.0
 */
@Slf4j
public class GsonUtils {

    /** 已注册的枚举类型列表。 */
    private static final List<Class<? extends Enum<?>>> REGISTERED_ENUMS = new ArrayList<>();

    /**
     * 全局 Gson 实例。
     * -- GETTER --
     * 获取全局 Gson 实例。
     */
    @Getter
    private static Gson gson = createBaseGson();

    private GsonUtils() {
    }

    /**
     * 注册枚举类型，使其支持字符串/整数自动转换。
     * <p>
     * 必须在首次使用 {@link #fromJson} 之前调用。
     * 重复注册同一类型会被忽略。
     * </p>
     *
     * @param enumClass 枚举类
     * @param <E>       枚举泛型
     * @since 1.0.0
     */
    public static synchronized <E extends Enum<E>> void registerEnum(Class<E> enumClass) {
        if (REGISTERED_ENUMS.contains(enumClass)) {
            return;
        }
        REGISTERED_ENUMS.add(enumClass);
        gson = buildGsonWithEnums();
    }

    /**
     * 批量注册枚举类型。
     *
     * @param enumClasses 枚举类数组
     * @since 1.0.0
     */
    @SafeVarargs
    public static synchronized void registerEnums(Class<? extends Enum<?>>... enumClasses) {
        for (Class<? extends Enum<?>> enumClass : enumClasses) {
            if (!REGISTERED_ENUMS.contains(enumClass)) {
                REGISTERED_ENUMS.add(enumClass);
            }
        }
        gson = buildGsonWithEnums();
    }

    /**
     * 使用已注册的枚举类型构建 Gson 实例。
     *
     * @return Gson 实例
     */
    private static Gson buildGsonWithEnums() {
        GsonBuilder builder = new GsonBuilder();
        for (Class<? extends Enum<?>> enumClass : REGISTERED_ENUMS) {
            registerAdapter(builder, enumClass);
        }
        return builder.create();
    }

    /**
     * 为指定枚举类型注册灵活适配器。
     *
     * @param <E>       枚举泛型
     * @param builder   Gson 构建器
     * @param enumClass 枚举类
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <E extends Enum<E>> void registerAdapter(GsonBuilder builder, Class<? extends Enum<?>> enumClass) {
        builder.registerTypeAdapter(enumClass, new FlexibleEnumTypeAdapter<>((Class<E>) enumClass));
    }

    /**
     * 创建基础 Gson 实例。
     *
     * @return Gson 实例
     */
    private static Gson createBaseGson() {
        return new GsonBuilder().create();
    }

    /**
     * 将 JSON 字符串解析为 JsonObject。
     *
     * @param json JSON 字符串
     * @return JsonObject
     * @since 1.0.0
     */
    public static JsonObject parseObject(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /**
     * 将 JSON 字符串反序列化为 Java 对象。
     *
     * @param json  JSON 字符串
     * @param clazz 目标类型
     * @param <T>   泛型
     * @return 反序列化后的对象
     * @since 1.0.0
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        return gson.fromJson(json, clazz);
    }

    /**
     * 将 JSON 字符串反序列化为泛型 Java 对象。
     * <p>
     * 用于解决泛型擦除问题，通过 {@link TypeToken} 保留完整泛型类型信息。
     * </p>
     *
     * @param json JSON 字符串
     * @param type 目标类型（通过 {@code new TypeToken<T>(){}.getType()} 获取）
     * @param <T>  泛型
     * @return 反序列化后的对象
     * @since 1.0.0
     */
    public static <T> T fromJson(String json, Type type) {
        return gson.fromJson(json, type);
    }

    /**
     * 从 JsonObject 反序列化为 Java 对象。
     *
     * @param obj   JsonObject
     * @param clazz 目标类型
     * @param <T>   泛型
     * @return 反序列化后的对象
     * @since 1.0.0
     */
    public static <T> T fromJson(JsonObject obj, Class<T> clazz) {
        return gson.fromJson(obj.toString(), clazz);
    }

    /**
     * 将 Java 对象序列化为 JsonElement。
     *
     * @param obj Java 对象
     * @return JsonElement
     * @since 1.0.0
     */
    public static JsonElement toJsonTree(Object obj) {
        return gson.toJsonTree(obj);
    }

    /**
     * 将 Java 对象序列化为 JSON 字符串。
     *
     * @param obj Java 对象
     * @return JSON 字符串
     * @since 1.0.0
     */
    public static String toJson(Object obj) {
        return gson.toJson(obj);
    }

    /**
     * 安全获取 JsonObject 中的字符串值。
     *
     * @param obj   JSON 对象
     * @param field 字段名
     * @return 字符串值，字段不存在或为 null 时返回 null
     * @since 1.0.0
     */
    public static String getString(JsonObject obj, String field) {
        if (obj == null || !obj.has(field) || obj.get(field).isJsonNull()) {
            return null;
        }
        return obj.get(field).getAsString();
    }

    /**
     * 安全获取 JsonObject 中的长整型值。
     *
     * @param obj          JSON 对象
     * @param field        字段名
     * @param defaultValue 默认值
     * @return 长整型值
     * @since 1.0.0
     */
    public static long getLong(JsonObject obj, String field, long defaultValue) {
        if (obj == null || !obj.has(field) || obj.get(field).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(field).getAsLong();
    }

    /**
     * 安全获取 JsonObject 中的整型值。
     *
     * @param obj          JSON 对象
     * @param field        字段名
     * @param defaultValue 默认值
     * @return 整型值
     * @since 1.0.0
     */
    public static int getInt(JsonObject obj, String field, int defaultValue) {
        if (obj == null || !obj.has(field) || obj.get(field).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(field).getAsInt();
    }

    /**
     * 安全获取 JsonObject 中的布尔值。
     *
     * @param obj          JSON 对象
     * @param field        字段名
     * @param defaultValue 默认值
     * @return 布尔值
     * @since 1.0.0
     */
    public static boolean getBoolean(JsonObject obj, String field, boolean defaultValue) {
        if (obj == null || !obj.has(field) || obj.get(field).isJsonNull()) {
            return defaultValue;
        }
        return obj.get(field).getAsBoolean();
    }

    /**
     * 安全获取嵌套的 JsonObject。
     *
     * @param obj   父 JSON 对象
     * @param field 字段名
     * @return 子 JsonObject，不存在时返回 null
     * @since 1.0.0
     */
    public static JsonObject getJsonObject(JsonObject obj, String field) {
        if (obj == null || !obj.has(field) || obj.get(field).isJsonNull()) {
            return null;
        }
        JsonElement el = obj.get(field);
        return el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    /**
     * 判断字符串是否为有效的 JSON 对象（以 '{' 开头）。
     *
     * @param line 原始行
     * @return 是否为 JSON
     * @since 1.0.0
     */
    public static boolean isJsonObject(String line) {
        return line != null && line.trim().startsWith("{");
    }

    /**
     * 判断字符串是否为空或空白。
     *
     * @param str 字符串
     * @return 是否为空
     * @since 1.0.0
     */
    public static boolean isEmpty(String str) {
        return StringUtil.isEmpty(str);
    }

    /**
     * 判断字符串是否不为空。
     *
     * @param str 字符串
     * @return 是否不为空
     * @since 1.0.0
     */
    public static boolean isNotEmpty(String str) {
        return !isEmpty(str);
    }

    /**
     * 判断列表是否为空或 null。
     *
     * @param list 列表
     * @return 是否为空
     * @since 1.0.0
     */
    public static boolean isEmpty(List<?> list) {
        return ContainerUtil.isEmpty(list);
    }
}
