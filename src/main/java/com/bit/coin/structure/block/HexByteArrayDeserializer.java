package com.bit.coin.structure.block;

import com.bit.coin.exception.JsonParseException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

/**
 * 字节数组的十六进制字符串反序列化器
 * 与HexByteArraySerializer配对使用，将JSON中的十六进制字符串还原为byte[]
 * 兼容：0x前缀、大小写、空格/空白分隔符、空字符串
 * 复用HexByteArraySerializer的hexToBytes方法，保证编解码逻辑一致
 */
public class HexByteArrayDeserializer extends StdDeserializer<byte[]> {

    /**
     * 无参构造方法（Jackson反射实例化反序列化器时的默认构造，必须公开）
     * 直接指定反序列化的目标类型为byte[]
     */
    public HexByteArrayDeserializer() {
        super(byte[].class);
    }

    /**
     * 带类型的构造方法（兼容Jackson的StdDeserializer父类设计，保留protected）
     * @param vc 反序列化目标类型
     */
    protected HexByteArrayDeserializer(Class<?> vc) {
        super(vc);
    }

    /**
     * 核心反序列化方法：将JSON中的十六进制字符串转为byte[]
     * @param p Json解析器，用于获取JSON中的字符串值
     * @param ctxt 反序列化上下文，用于抛出Jackson规范的异常
     * @return 解析后的字节数组，null/空字符串对应null/空字节数组
     * @throws JacksonException Jackson序列化/反序列化统一异常
     */
    @Override
    public byte[] deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
        // 1. 获取JSON中的原始字符串值（null/空字符串都会正确获取）
        String hexStr = p.getValueAsString();

        // 2. 空值处理：JSON中为null则返回null，与序列化器的null处理逻辑一致
        if (hexStr == null) {
            return null;
        }

        try {
            // 3. 复用序列化器的hexToBytes方法，保证编解码逻辑完全一致
            // 已处理：0x前缀移除、分隔符移除、大小写兼容、空字符串返回空数组
            return HexByteArraySerializer.hexToBytes(hexStr);
        } catch (IllegalArgumentException e) {
            // 4. 异常适配：将自定义的参数异常包装为Jackson的解析异常
            // 符合Jackson的异常规范，反序列化失败时抛出统一的JacksonException
            throw new JsonParseException("解析十六进制字符串为字节数组失败：",500);
        }
    }
}