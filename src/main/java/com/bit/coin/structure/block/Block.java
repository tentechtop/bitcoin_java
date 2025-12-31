package com.bit.coin.structure.block;


import lombok.Data;
import org.bitcoinj.core.Transaction;

import java.util.List;

@Data
public class Block {
    private BlockHeader blockHeader;
    private List<Transaction> transactions;
}
