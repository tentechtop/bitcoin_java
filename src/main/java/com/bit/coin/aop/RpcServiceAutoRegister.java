package com.bit.coin.aop;

import com.bit.coin.aop.annotation.RpcService;
import com.bit.coin.aop.annotation.RpcServiceAlias;
import com.bit.coin.p2p.rpc.RpcServiceRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class RpcServiceAutoRegister implements ApplicationContextAware {

    private ApplicationContext applicationContext;

    @Autowired
    private RpcServiceRegistry rpcServiceRegistry;

    // 构造注入注册器
    public RpcServiceAutoRegister() {}

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * 当Spring容器完全初始化后执行注册
     */
    @EventListener(ContextRefreshedEvent.class)
    public void registerServicesAfterSpringInit() {
        log.info("开始自动扫描并注册RPC服务...");

        // 1. 扫描所有被@RpcService标记的接口
        Map<String, Object> serviceImplBeans = new HashMap<>();

        // 2. 遍历所有Spring管理的Bean，找到实现了@RpcService接口的Bean
        Map<String, Object> allBeans = applicationContext.getBeansOfType(Object.class);
        for (Object bean : allBeans.values()) {
            Class<?>[] interfaces = bean.getClass().getInterfaces();
            for (Class<?> iface : interfaces) {
                if (iface.isAnnotationPresent(RpcService.class)) {
                    serviceImplBeans.put(iface.getName(), bean);
                    log.info("发现RPC服务实现 | 接口: {} | 实现类: {}",
                            iface.getName(), bean.getClass().getName());
                }
                if (iface.isAnnotationPresent(RpcServiceAlias.class)) {
                    serviceImplBeans.put(iface.getName(), bean);
                    log.info("发现RPC服务实现 | 接口: {} | 实现类: {}",
                            iface.getName(), bean.getClass().getName());
                }
            }
        }

        // 3. 转换为接口类->实例的映射
        Map<Class<?>, Object> serviceMap = new HashMap<>();
        for (Map.Entry<String, Object> entry : serviceImplBeans.entrySet()) {
            try {
                Class<?> interfaceClass = Class.forName(entry.getKey());
                serviceMap.put(interfaceClass, entry.getValue());
            } catch (ClassNotFoundException e) {
                log.error("找不到服务接口类 | 接口名: {}", entry.getKey(), e);
            }
        }

        // 4. 批量注册
        rpcServiceRegistry.batchRegisterServices(serviceMap);

        // 5. 注册结果校验
        log.info("RPC服务自动注册完成 | 总数量: {} | 已注册列表: {}",
                serviceMap.size(), rpcServiceRegistry.getRegisteredServices());
    }
}