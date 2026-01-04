package com.bit.coin.database.rocksDb;


import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
public class RTable {
    // 核心：表枚举 -> 列族句柄映射（仅维护句柄，不重复定义表信息）
    private static final Map<TableEnum, ColumnFamilyHolder> tableToCfMap = new HashMap<>();

    // 内部holder：仅存储列族句柄（表信息从TableEnum获取）
    private static class ColumnFamilyHolder {
        @Setter @Getter
        private ColumnFamilyHandle handle;  // 列族句柄（动态绑定）
    }

    // 静态初始化：从TableEnum同步所有表，初始化映射（无需硬编码列族）
    static {
        for (TableEnum table : TableEnum.values()) {
            tableToCfMap.put(table, new ColumnFamilyHolder());
        }
        log.info("RTable静态初始化完成，加载列族数量：{}", tableToCfMap.size());
    }

    /**
     * 根据表枚举获取列族句柄
     */
    public static ColumnFamilyHandle getColumnFamilyHandle(TableEnum tableEnum) {
        if (tableEnum == null) {
            log.info("表枚举为空，无法获取列族句柄");
            return null;
        }
        ColumnFamilyHolder holder = tableToCfMap.get(tableEnum);
        return holder != null ? holder.getHandle() : null;
    }

    /**
     * 绑定列族句柄（数据库初始化时调用）
     * @param tableEnum 表枚举
     * @param handle 列族句柄
     */
    public static void setColumnFamilyHandle(TableEnum tableEnum, ColumnFamilyHandle handle) {
        if (tableEnum == null || handle == null) {
            log.warn("绑定列族句柄失败：表枚举或句柄为空");
            return;
        }
        ColumnFamilyHolder holder = tableToCfMap.get(tableEnum);
        if (holder != null) {
            holder.setHandle(handle);
        }
    }

    /**
     * 获取所有列族描述符（从TableEnum动态生成，无需硬编码）
     */
    public static Map<TableEnum, ColumnFamilyDescriptor> getColumnFamilyDescriptors() {
        // 使用 LinkedHashMap 保持 TableEnum 定义的顺序
        Map<TableEnum, ColumnFamilyDescriptor> descriptors = new LinkedHashMap<>();
        for (TableEnum table : TableEnum.values()) {
            descriptors.put(
                    table,
                    new ColumnFamilyDescriptor(
                            table.getColumnFamilyName().getBytes(StandardCharsets.UTF_8), // 显式指定编码
                            table.getColumnFamilyOptions()
                    )
            );
        }
        return descriptors;
    }
}