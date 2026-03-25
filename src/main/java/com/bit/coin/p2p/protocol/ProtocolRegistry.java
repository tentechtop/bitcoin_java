package com.bit.coin.p2p.protocol;


import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 协议注册表：核心管理枚举与处理器的绑定关系
 */
@Slf4j
@Component
public class ProtocolRegistry {


    // 单例模式（P2P节点全局唯一）
    private static final ProtocolRegistry INSTANCE = new ProtocolRegistry();

    // 存储协议枚举 → 处理器的映射（线程安全）
    @Getter
    private final Map<ProtocolEnum, ProtocolHandler> handlerMap = new ConcurrentHashMap<>();

    // 私有构造器（单例）
    private ProtocolRegistry() {}

    // ========== 注册方法（核心：按返回值类型区分注册） ==========
    /**
     * 注册有返回值的协议处理器（强制非null返回）
     * @param protocolEnum 有返回值的协议枚举
     * @param handler 有返回值的处理器
     */
    public void registerResultHandler(ProtocolEnum protocolEnum, ProtocolHandler.ResultProtocolHandler handler) {
        validateProtocolHasResponse(protocolEnum, true);
        handlerMap.put(protocolEnum, handler);
        log.info("注册有返回值协议处理器：{}", protocolEnum.getProtocol());
    }

    /**
     * 注册无返回值的协议处理器（强制返回null）
     * @param protocolEnum 无返回值的协议枚举
     * @param handler 无返回值的处理器
     */
    public void registerVoidHandler(ProtocolEnum protocolEnum, ProtocolHandler.VoidProtocolHandler handler) {
        validateProtocolHasResponse(protocolEnum, false);
        handlerMap.put(protocolEnum, handler);
        log.info("注册无返回值协议处理器：{}", protocolEnum.getProtocol());
    }

    /**
     * 校验协议的返回值属性与预期一致
     */
    private void validateProtocolHasResponse(ProtocolEnum protocolEnum, boolean expectHasResponse) {
        if (protocolEnum == null) {
            throw new IllegalArgumentException("协议枚举不能为空");
        }
        if (protocolEnum.isHasResponse() != expectHasResponse) {
            throw new IllegalArgumentException(
                    "协议[" + protocolEnum.getProtocol() + "]的返回值属性不匹配：预期" +
                            (expectHasResponse ? "有返回值" : "无返回值") +
                            "，实际" + (protocolEnum.isHasResponse() ? "有返回值" : "无返回值")
            );
        }
    }



    // ========== 处理方法 ==========
    /**
     * 执行协议处理（核心调用入口）
     * @param protocolEnum 协议枚举
     * @param requestParams 请求参数（字节数组）
     * @return 处理结果（有返回值则返回字节数组，无返回值返回null）
     */
    public byte[] handle(ProtocolEnum protocolEnum, P2PMessage requestParams) throws Exception {
        ProtocolHandler handler = handlerMap.get(protocolEnum);
        if (handler == null) {
            throw new IllegalStateException("未找到协议处理器：" + protocolEnum.getProtocol());
        }
        // 执行处理器逻辑（有返回值则返回，无返回值返回null）
        return handler.handle(requestParams);
    }

    // ========== 静态工具方法 ==========
    /** 根据code执行处理（适配P2PMessage的type字段） */
    public byte[] handleByCode(int code, P2PMessage requestParams) throws Exception {
        ProtocolEnum protocolEnum = ProtocolEnum.fromCode(code);
        return handle(protocolEnum, requestParams);
    }

    /** 根据协议字符串执行处理 */
    public byte[] handleByProtocol(String protocol, P2PMessage requestParams) throws Exception {
        ProtocolEnum protocolEnum = ProtocolEnum.fromProtocol(protocol);
        return handle(protocolEnum, requestParams);
    }

    // 获取单例实例
    public static ProtocolRegistry getInstance() {
        return INSTANCE;
    }



}