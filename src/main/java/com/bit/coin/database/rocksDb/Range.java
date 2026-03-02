package com.bit.coin.database.rocksDb;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Range {
    public  byte[] start; // 范围起始键（包含）
    public  byte[] end;   // 范围结束键（不包含）
}
