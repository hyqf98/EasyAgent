package io.github.easyagent.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;

/**
 * 原始 JSON 字符串适配器。
 * <p>
 * 将任意 JSON 节点反序列化为字符串：
 * <ul>
 *     <li>字符串原样返回</li>
 *     <li>对象/数组保留 JSON 文本</li>
 *     <li>数字、布尔值转为字面量文本</li>
 * </ul>
 * </p>
 *
 * @author haijun
 * @date 2026/5/7
 * @since 1.0.0
 */
public class RawJsonStringAdapter extends TypeAdapter<String> {

    @Override
    public void write(JsonWriter out, String value) throws IOException {
        if (value == null) {
            out.nullValue();
            return;
        }
        out.value(value);
    }

    @Override
    public String read(JsonReader in) throws IOException {
        JsonToken token = in.peek();
        if (token == JsonToken.NULL) {
            in.nextNull();
            return null;
        }
        if (token == JsonToken.STRING) {
            return in.nextString();
        }
        JsonElement element = JsonParser.parseReader(in);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return element.getAsString();
        }
        return GsonUtils.toJson(element);
    }
}
