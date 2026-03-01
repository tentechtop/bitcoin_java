package com.bit.coin.p2p.rpc;


import com.bit.coin.aop.annotation.RpcServiceAlias;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RPC服务注册器：管理服务接口与实现类的映射关系
 * 支持线程安全的注册、查询、注销操作，并提供类型校验
 */
@Slf4j
@Component
@Scope("singleton") // 显式指定单例
public class RpcServiceRegistry {
    // 核心存储：服务接口全限定名 -> 实现类实例
    // 使用ConcurrentHashMap保证线程安全，支持高并发读写
    private final Map<String, Object> serviceMap = new ConcurrentHashMap<>();
    // 别名映射：别名 -> 全限定名
    private final Map<String, String> aliasMap = new ConcurrentHashMap<>();

    /**
     * 批量注册服务实现类
     * @param serviceMap 接口类 -> 实现类实例的映射
     */
    public void batchRegisterServices(Map<Class<?>, Object> serviceMap) {
        if (CollectionUtils.isEmpty(serviceMap)) {
            log.info("没有需要注册的RPC服务");
            return;
        }
        serviceMap.forEach((interfaceClass, implInstance) -> {
            try {
                this.registerService(interfaceClass, implInstance);
            } catch (IllegalArgumentException e) {
                log.error("批量注册服务失败 | 接口: {} | 原因: {}",
                        interfaceClass.getName(), e.getMessage());
            }
        });
    }


    /**
     * 注册服务（手动指定接口与实现类）
     * @param serviceInterface 服务接口类（如 TransactionService.class）
     * @param serviceImpl 接口的实现类实例（如 new TransactionServiceImpl()）
     * @param <T> 服务接口类型
     * @throws IllegalArgumentException 当实现类不匹配接口时抛出
     */
    public <T> void registerService(Class<T> serviceInterface, Object serviceImpl) {
        // 1. 校验参数合法性
        if (serviceInterface == null) {
            throw new IllegalArgumentException("服务接口不能为空");
        }
        if (serviceImpl == null) {
            throw new IllegalArgumentException("服务实现类不能为空");
        }
        // 2. 校验实现类是否真的实现了接口
        if (!serviceInterface.isInstance(serviceImpl)) {
            throw new IllegalArgumentException(
                    "实现类 " + serviceImpl.getClass().getName() +
                            " 未实现接口 " + serviceInterface.getName()
            );
        }

        // 3. 注册服务（接口全限定名为key）
        String serviceName = serviceInterface.getName();
        serviceMap.put(serviceName, serviceImpl);

        // 处理别名
        if (serviceInterface.isAnnotationPresent(RpcServiceAlias.class)) {
            String alias = serviceInterface.getAnnotation(RpcServiceAlias.class).value();
            if (aliasMap.containsKey(alias)) {
                throw new IllegalArgumentException("服务别名重复: " + alias);
            }
            aliasMap.put(alias, serviceName);
            log.info("RPC服务注册成功 | 别名: {} | 接口: {} | 实现类: {}", alias, serviceName,serviceImpl.getClass().getName());
        } else {
            log.info("RPC服务注册成功 | 接口: {} | 实现类: {}",
                    serviceName, serviceImpl.getClass().getName());
        }
    }



    /**
     * 支持通过别名或全限定名获取服务
     */
    public Object getService(String name) {
        // 先查别名映射
        String serviceName = aliasMap.getOrDefault(name, name);
        Object service = serviceMap.get(serviceName);

        if (service == null) {
            log.warn("RPC服务未找到 | 名称: {}", name);
        }
        return service;
    }


    public Map<String, Object> getService() {
        return serviceMap;
    }


    /**
     * 根据接口类获取服务实例（泛型重载，方便调用）
     * @param serviceInterface 服务接口类
     * @param <T> 服务接口类型
     * @return 强类型的服务实例，若未注册则返回null
     */
    @SuppressWarnings("unchecked")
    public <T> T getService(Class<T> serviceInterface) {
        Object service = getService(serviceInterface.getName());
        return (T) service; // 强转由调用者保证类型安全
    }

    /**
     * 注销服务
     * @param serviceInterface 服务接口类
     * @return 被注销的服务实例，若未注册则返回null
     */
    public Object unregisterService(Class<?> serviceInterface) {
        if (serviceInterface == null) {
            return null;
        }
        String serviceName = serviceInterface.getName();
        Object removed = serviceMap.remove(serviceName);
        if (removed != null) {
            log.info("RPC服务注销成功 | 接口: {}", serviceName);
        } else {
            log.warn("RPC服务注销失败（未注册） | 接口: {}", serviceName);
        }
        return removed;
    }

    /**
     * 获取所有已注册的服务接口名
     * @return 服务接口名集合（不可修改）
     */
    public Set<String> getRegisteredServices() {
        return serviceMap.keySet();
    }

    /**
     * 检查服务是否已注册
     * @param serviceInterface 服务接口类
     * @return 已注册返回true，否则false
     */
    public boolean isRegistered(Class<?> serviceInterface) {
        return serviceInterface != null &&
                serviceMap.containsKey(serviceInterface.getName());
    }
}
