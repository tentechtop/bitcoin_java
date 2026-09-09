package com.bit.coin.api;

import com.bit.coin.blockchain.BlockChainServiceImpl;
import com.bit.coin.structure.tx.Transaction;
import com.bit.coin.structure.tx.TransactionValidator;
import com.bit.coin.txpool.TxPool;
import com.bit.coin.utils.SerializeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.HexFormat;
import java.util.Map;

@RestController @RequestMapping("/tx")
public class SignedTransactionApi {
    private final TxPool pool;
    private final BlockChainServiceImpl chain;
    public SignedTransactionApi(TxPool pool, BlockChainServiceImpl chain) { this.pool = pool; this.chain = chain; }
    public record SignedTransaction(String rawTransaction) {}
    @PostMapping("/broadcast")
    public Map<String, String> broadcast(@RequestBody SignedTransaction body) {
        if (!chain.isStateHealthy()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Node is not ready");
        if (body == null || body.rawTransaction() == null || body.rawTransaction().length() > TransactionValidator.MAX_TRANSACTION_BYTES * 2)
            throw new IllegalArgumentException("Invalid transaction size");
        Transaction tx = Transaction.deserialize(HexFormat.of().parseHex(body.rawTransaction()));
        if (!java.util.Arrays.equals(tx.getTxId(), tx.calculateTxId())) throw new IllegalArgumentException("Transaction identity mismatch");
        String txId = SerializeUtils.bytesToHex(tx.getTxId());
        if (pool.getTx(txId) == null && !pool.addTx(tx)) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Transaction rejected");
        return Map.of("txId", txId, "status", "accepted");
    }
}
