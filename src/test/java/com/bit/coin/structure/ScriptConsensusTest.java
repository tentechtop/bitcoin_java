package com.bit.coin.structure;

import com.bit.coin.blockchain.ConsensusRules;
import com.bit.coin.structure.script.*;
import com.bit.coin.structure.tx.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ScriptConsensusTest {
    private Transaction tx(long sequence, long lockTime) {
        Transaction tx = new Transaction(); tx.setVersion(101); tx.setLockTime(lockTime);
        TxInput input = new TxInput(); input.setTxId(new byte[32]); input.getTxId()[0] = 1; input.setSequence(sequence);
        tx.getInputs().add(input); return tx;
    }
    private Script lock(int number, int opcode) {
        Script script = new Script(); script.addData(new byte[]{(byte) number}); script.addOpCode(opcode);
        script.addOpCode(Script.OP_DROP); script.addOpCode(Script.OP_1); return script;
    }
    @Test void absoluteTimelockCannotBeBypassedWithFinalSequenceOrFollowingCode() {
        Script script = lock(10, Script.OP_CHECKLOCKTIMEVERIFY);
        assertFalse(new ScriptExecutor().execute(script, tx(0xFFFFFFFFL, 0), 0));
        assertFalse(new ScriptExecutor().execute(script, tx(0, 9), 0));
        assertTrue(new ScriptExecutor().execute(script, tx(0, 10), 0));
        assertFalse(new ScriptExecutor().execute(script, tx(0, 500_000_000), 0));
    }
    @Test void relativeTimelockUsesDisableTypeAndVersionRules() {
        Script script = lock(2, Script.OP_CHECKSEQUENCEVERIFY);
        assertFalse(new ScriptExecutor().execute(script, tx(0xFFFFFFFFL, 0), 0));
        assertFalse(new ScriptExecutor().execute(script, tx(1, 0), 0));
        assertTrue(new ScriptExecutor().execute(script, tx(2, 0), 0));
        assertFalse(new ScriptExecutor().execute(script, tx(0x400002, 0), 0));
        Transaction old = tx(2, 0); old.setVersion(1);
        assertFalse(new ScriptExecutor().execute(script, old, 0));
    }
    @Test void relativeLocksAreActuallyEnforcedAgainstSourceAge() {
        Transaction tx = tx(2, 0);
        UTXO source = new UTXO(); source.setTxId(tx.getInputs().getFirst().getTxId()); source.setHeight(10);
        tx.setUtxoMap(Map.of(UTXO.getKeyStr(source.getTxId(), 0), source));
        assertFalse(ConsensusRules.relativeLocksSatisfied(tx, 11, 1000, height -> 0));
        assertTrue(ConsensusRules.relativeLocksSatisfied(tx, 12, 1000, height -> 0));
        tx.getInputs().getFirst().setSequence(0x400002);
        assertFalse(ConsensusRules.relativeLocksSatisfied(tx, 12, 2023, height -> 1000));
        assertTrue(ConsensusRules.relativeLocksSatisfied(tx, 12, 2024, height -> 1000));
    }
    @Test void negativeShiftsAndUnbalancedConditionalsFailClosed() {
        Script negative = new Script(); negative.addOpCode(Script.OP_1); negative.addData(new byte[]{(byte) 0x81}); negative.addOpCode(Script.OP_LSHIFT);
        assertFalse(new ScriptExecutor().execute(negative, tx(0, 0), 0));
        Script conditional = new Script(); conditional.addOpCode(Script.OP_1); conditional.addOpCode(Script.OP_IF); conditional.addOpCode(Script.OP_1);
        assertFalse(new ScriptExecutor().execute(conditional, tx(0, 0), 0));
    }
}
