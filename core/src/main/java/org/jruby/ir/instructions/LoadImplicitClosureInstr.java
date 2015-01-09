package org.jruby.ir.instructions;

import org.jruby.ir.IRScope;
import org.jruby.ir.IRVisitor;
import org.jruby.ir.Operation;
import org.jruby.ir.operands.Operand;
import org.jruby.ir.operands.Variable;
import org.jruby.ir.operands.WrappedIRClosure;
import org.jruby.ir.transformations.inlining.CloneInfo;
import org.jruby.ir.transformations.inlining.InlineCloneInfo;
import org.jruby.ir.transformations.inlining.SimpleCloneInfo;
import org.jruby.parser.StaticScope;
import org.jruby.runtime.Block;
import org.jruby.runtime.DynamicScope;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;

/* Receive the closure argument (either implicit or explicit in Ruby source code) */
public class LoadImplicitClosureInstr extends Instr implements ResultInstr, FixedArityInstr {
    private Variable result;

    public LoadImplicitClosureInstr(Variable result) {
        super(Operation.LOAD_IMPLICT_CLOSURE);

        assert result != null: "LoadImplicitClosureInstr result is null";

        this.result = result;
    }

    @Override
    public Operand[] getOperands() {
        return EMPTY_OPERANDS;
    }

    @Override
    public Variable getResult() {
        return result;
    }

    @Override
    public void updateResult(Variable v) {
        this.result = v;
    }

    @Override
    public Instr clone(CloneInfo info) {
        if (info instanceof SimpleCloneInfo) return new LoadImplicitClosureInstr(info.getRenamedVariable(result));

        // SSS FIXME: This code below is for inlining and is untested.

        InlineCloneInfo ii = (InlineCloneInfo) info;

        // SSS FIXME: This is not strictly correct -- we have to wrap the block into an
        // operand type that converts the static code block to a proc which is a closure.
        if (ii.getCallClosure() instanceof WrappedIRClosure) return NopInstr.NOP;

        return new CopyInstr(ii.getRenamedVariable(result), ii.getCallClosure());
    }

    @Override
    public void visit(IRVisitor visitor) {
        visitor.LoadImplicitClosure(this);
    }
}
