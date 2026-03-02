package com.bit.coin.database.rocksDb;

import com.bit.coin.structure.block.HexByteArraySerializer;
import lombok.Getter;
import lombok.Setter;
import tools.jackson.databind.annotation.JsonSerialize;

import java.util.List;

@Getter
@Setter
public class PageResult<T> {
    private List<T> data; // 当前页数据

    @JsonSerialize(using = HexByteArraySerializer.class)
    private byte[] lastKey; // 当前页最后一个键（用于下一页查询）
    private boolean isLastPage; // 是否为最后一页

    public PageResult(List<T> data, byte[] lastKey, boolean isLastPage) {
        this.data = data;
        this.lastKey = lastKey;
        this.isLastPage = isLastPage;
    }
}
