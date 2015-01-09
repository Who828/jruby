package org.jruby.ir;

import org.jcodings.specific.ASCIIEncoding;
import org.jruby.EvalType;
import org.jruby.Ruby;
import org.jruby.RubyInstanceConfig;
import org.jruby.ast.*;
import org.jruby.ast.types.INameNode;
import org.jruby.compiler.NotCompilableException;
import org.jruby.internal.runtime.methods.IRMethodArgs;
import org.jruby.ir.instructions.*;
import org.jruby.ir.instructions.defined.GetErrorInfoInstr;
import org.jruby.ir.instructions.defined.RestoreErrorInfoInstr;
import org.jruby.ir.listeners.IRScopeListener;
import org.jruby.ir.operands.*;
import org.jruby.ir.operands.Boolean;
import org.jruby.ir.operands.Float;
import org.jruby.ir.transformations.inlining.SimpleCloneInfo;
import org.jruby.parser.StaticScope;
import org.jruby.runtime.Arity;
import org.jruby.runtime.CallType;
import org.jruby.runtime.Helpers;
import org.jruby.runtime.RubyEvent;
import org.jruby.util.ByteList;
import org.jruby.util.KeyValuePair;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

import static org.jruby.ir.instructions.RuntimeHelperCall.Methods.*;

import static org.jruby.ir.operands.CurrentScope.*;
import static org.jruby.ir.operands.ScopeModule.*;

// This class converts an AST into a bunch of IR instructions

// IR Building Notes
// -----------------
//
// 1. More copy instructions added than necessary
// ----------------------------------------------
// Note that in general, there will be lots of a = b kind of copies
// introduced in the IR because the translation is entirely single-node focused.
// An example will make this clear
//
// RUBY:
//     v = @f
// will translate to
//
// AST:
//     LocalAsgnNode v
//       InstrVarNode f
// will translate to
//
// IR:
//     tmp = self.f [ GET_FIELD(tmp,self,f) ]
//     v = tmp      [ COPY(v, tmp) ]
//
// instead of
//     v = self.f   [ GET_FIELD(v, self, f) ]
//
// We could get smarter and pass in the variable into which this expression is going to get evaluated
// and use that to store the value of the expression (or not build the expression if the variable is null).
//
// But, that makes the code more complicated, and in any case, all this will get fixed in a single pass of
// copy propagation and dead-code elimination.
//
// Something to pay attention to and if this extra pass becomes a concern (not convinced that it is yet),
// this smart can be built in here.  Right now, the goal is to do something simple and straightforward that is going to be correct.
//
// 2. Returning null vs manager.getNil()
// ----------------------------
// - We should be returning null from the build methods where it is a normal "error" condition
// - We should be returning manager.getNil() where the actual return value of a build is the ruby nil operand
//   Look in buildIf for an example of this
//
// 3. Temporary variable reuse
// ---------------------------
// I am reusing variables a lot in places in this code.  Should I instead always get a new variable when I need it
// This introduces artificial data dependencies, but fewer variables.  But, if we are going to implement SSA pass
// this is not a big deal.  Think this through!

public class IRBuilder {
    static final Operand[] NO_ARGS = new Operand[]{};
    static final UnexecutableNil U_NIL = UnexecutableNil.U_NIL;

    public static IRBuilder createIRBuilder(Ruby runtime, IRManager manager) {
        return new IRBuilder(manager);
    }

    public static Node buildAST(boolean isCommandLineScript, String arg) {
        Ruby ruby = Ruby.getGlobalRuntime();

        // inline script
        if (isCommandLineScript) return ruby.parse(ByteList.create(arg), "-e", null, 0, false);

        // from file
        FileInputStream fis = null;
        try {
            File file = new File(arg);
            fis = new FileInputStream(file);
            long size = file.length();
            byte[] bytes = new byte[(int)size];
            fis.read(bytes);
            System.out.println("-- processing " + arg + " --");
            return ruby.parse(new ByteList(bytes), arg, null, 0, false);
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        } finally {
            try { if (fis != null) fis.close(); } catch(Exception ignored) { }
        }
    }

    private static class IRLoop {
        public final IRScope  container;
        public final IRLoop   parentLoop;
        public final Label    loopStartLabel;
        public final Label    loopEndLabel;
        public final Label    iterStartLabel;
        public final Label    iterEndLabel;
        public final Variable loopResult;

        public IRLoop(IRScope s, IRLoop outerLoop) {
            container = s;
            parentLoop = outerLoop;
            loopStartLabel = s.getNewLabel("_LOOP_BEGIN");
            loopEndLabel   = s.getNewLabel("_LOOP_END");
            iterStartLabel = s.getNewLabel("_ITER_BEGIN");
            iterEndLabel   = s.getNewLabel("_ITER_END");
            loopResult     = s.createTemporaryVariable();
            s.setHasLoopsFlag();
        }
    }

    private static class RescueBlockInfo {
        RescueNode rescueNode;             // Rescue node for which we are tracking info
        Label      entryLabel;             // Entry of the rescue block
        Variable   savedExceptionVariable; // Variable that contains the saved $! variable
        IRLoop     innermostLoop;          // Innermost loop within which this rescue block is nested, if any

        public RescueBlockInfo(RescueNode n, Label l, Variable v, IRLoop loop) {
            rescueNode = n;
            entryLabel = l;
            savedExceptionVariable = v;
            innermostLoop = loop;
        }

        public void restoreException(IRBuilder b, IRScope s, IRLoop currLoop) {
            if (currLoop == innermostLoop) b.addInstr(s, new PutGlobalVarInstr("$!", savedExceptionVariable));
        }
    }

    /* -----------------------------------------------------------------------------------
     * Every ensure block has a start label and end label
     *
     * This ruby code will translate to the IR shown below
     * -----------------
     *   begin
     *       ... protected body ...
     *   ensure
     *       ... ensure block to run
     *   end
     * -----------------
     *  L_region_start
     *     IR instructions for the protected body
     *     .. copy of ensure block IR ..
     *  L_dummy_rescue:
     *     e = recv_exc
     *  L_start:
     *     .. ensure block IR ..
     *     throw e
     *  L_end:
     * -----------------
     *
     * If N is a node in the protected body that might exit this scope (exception rethrows
     * and returns), N has to first run the ensure block before exiting.
     *
     * Since we can have a nesting of ensure blocks, we are maintaining a stack of these
     * well-nested ensure blocks.  Every node N that will exit this scope will have to
     * run the stack of ensure blocks in the right order.
     * ----------------------------------------------------------------------------------- */
    private static class EnsureBlockInfo {
        Label    regionStart;
        Label    start;
        Label    end;
        Label    dummyRescueBlockLabel;
        Variable savedGlobalException;

        // Label of block that will rescue exceptions raised by ensure code
        Label    bodyRescuer;

        // Innermost loop within which this ensure block is nested, if any
        IRLoop   innermostLoop;

        // AST node for any associated rescue node in the case of begin-rescue-ensure-end block
        // Will be null in the case of begin-ensure-end block
        RescueNode matchingRescueNode;

        // This ensure block's instructions
        List<Instr> instrs;

        public EnsureBlockInfo(IRScope s, RescueNode n, IRLoop l, Label bodyRescuer) {
            regionStart = s.getNewLabel();
            start       = s.getNewLabel();
            end         = s.getNewLabel();
            dummyRescueBlockLabel = s.getNewLabel();
            instrs = new ArrayList<>();
            savedGlobalException = null;
            innermostLoop = l;
            matchingRescueNode = n;
            this.bodyRescuer = bodyRescuer;
        }

        public void addInstr(Instr i) {
            instrs.add(i);
        }

        public void addInstrAtBeginning(Instr i) {
            instrs.add(0, i);
        }

        public void emitBody(IRBuilder b, IRScope s) {
            b.addInstr(s, new LabelInstr(start));
            for (Instr i: instrs) {
                b.addInstr(s, i);
            }
        }

        public void cloneIntoHostScope(IRBuilder b, IRScope s) {
            SimpleCloneInfo ii = new SimpleCloneInfo(s, true);

            // Clone required labels.
            // During normal cloning below, labels not found in the rename map
            // are not cloned.
            ii.renameLabel(start);
            for (Instr i: instrs) {
                if (i instanceof LabelInstr) {
                    ii.renameLabel(((LabelInstr)i).label);
                }
            }

            // Clone instructions now
            b.addInstr(s, new LabelInstr(ii.getRenamedLabel(start)));
            b.addInstr(s, new ExceptionRegionStartMarkerInstr(bodyRescuer));
            for (Instr i: instrs) {
                Instr clonedInstr = i.clone(ii);
                if (clonedInstr instanceof CallBase) {
                    CallBase call = (CallBase)clonedInstr;
                    Operand block = call.getClosureArg(null);
                    if (block instanceof WrappedIRClosure) s.addClosure(((WrappedIRClosure)block).getClosure());
                }
                b.addInstr(s, clonedInstr);
            }
            b.addInstr(s, new ExceptionRegionEndMarkerInstr());
        }
    }

    // Stack of nested rescue blocks -- this just tracks the start label of the blocks
    private Stack<RescueBlockInfo> activeRescueBlockStack = new Stack<>();

    // Stack of ensure blocks that are currently active
    private Stack<EnsureBlockInfo> activeEnsureBlockStack = new Stack<>();

    // Stack of ensure blocks whose bodies are being constructed
    private Stack<EnsureBlockInfo> ensureBodyBuildStack   = new Stack<>();

    // Combined stack of active rescue/ensure nestings -- required to properly set up
    // rescuers for ensure block bodies cloned into other regions -- those bodies are
    // rescued by the active rescuers at the point of definition rather than the point
    // of cloning.
    private Stack<Label> activeRescuers = new Stack<>();

    private int _lastProcessedLineNum = -1;

    // Since we are processing ASTs, loop bodies are processed in depth-first manner
    // with outer loops encountered before inner loops, and inner loops finished before outer ones.
    //
    // So, we can keep track of loops in a loop stack which  keeps track of loops as they are encountered.
    // This lets us implement next/redo/break/retry easily for the non-closure cases
    private Stack<IRLoop> loopStack = new Stack<>();

    public IRLoop getCurrentLoop() {
        return loopStack.isEmpty() ? null : loopStack.peek();
    }

    protected IRManager manager;

    public IRBuilder(IRManager manager) {
        this.manager = manager;
        this.activeRescuers.push(Label.UNRESCUED_REGION_LABEL);
    }

    public void addInstr(IRScope s, Instr i) {
        // If we are building an ensure body, stash the instruction
        // in the ensure body's list. If not, add it to the scope directly.
        if (ensureBodyBuildStack.empty()) {
            s.addInstr(i);
        } else {
            ensureBodyBuildStack.peek().addInstr(i);
        }
    }

    public void addInstrAtBeginning(IRScope s, Instr i) {
        // If we are building an ensure body, stash the instruction
        // in the ensure body's list. If not, add it to the scope directly.
        if (ensureBodyBuildStack.empty()) {
            s.addInstrAtBeginning(i);
        } else {
            ensureBodyBuildStack.peek().addInstrAtBeginning(i);
        }
    }

    // Emit cloned ensure bodies by walking up the ensure block stack.
    // If we have been passed a loop value, only emit bodies that are nested within that loop.
    private void emitEnsureBlocks(IRScope s, IRLoop loop) {
        int n = activeEnsureBlockStack.size();
        EnsureBlockInfo[] ebArray = activeEnsureBlockStack.toArray(new EnsureBlockInfo[n]);
        for (int i = n-1; i >= 0; i--) {
            EnsureBlockInfo ebi = ebArray[i];

            // For "break" and "next" instructions, we only want to run
            // ensure blocks from the loops they are present in.
            if (loop != null && ebi.innermostLoop != loop) break;

            // SSS FIXME: Should $! be restored before or after the ensure block is run?
            if (ebi.savedGlobalException != null) {
                addInstr(s, new PutGlobalVarInstr("$!", ebi.savedGlobalException));
            }

            // Clone into host scope
            ebi.cloneIntoHostScope(this, s);
        }
    }

    private Operand buildOperand(Node node, IRScope s) throws NotCompilableException {
        switch (node.getNodeType()) {
            case ALIASNODE: return buildAlias((AliasNode) node, s);
            case ANDNODE: return buildAnd((AndNode) node, s);
            case ARGSCATNODE: return buildArgsCat((ArgsCatNode) node, s);
            case ARGSPUSHNODE: return buildArgsPush((ArgsPushNode) node, s);
            case ARRAYNODE: return buildArray(node, s);
            case ATTRASSIGNNODE: return buildAttrAssign((AttrAssignNode) node, s);
            case BACKREFNODE: return buildBackref((BackRefNode) node, s);
            case BEGINNODE: return buildBegin((BeginNode) node, s);
            case BIGNUMNODE: return buildBignum((BignumNode) node);
            case BLOCKNODE: return buildBlock((BlockNode) node, s);
            case BREAKNODE: return buildBreak((BreakNode) node, s);
            case CALLNODE: return buildCall((CallNode) node, s);
            case CASENODE: return buildCase((CaseNode) node, s);
            case CLASSNODE: return buildClass((ClassNode) node, s);
            case CLASSVARNODE: return buildClassVar((ClassVarNode) node, s);
            case CLASSVARASGNNODE: return buildClassVarAsgn((ClassVarAsgnNode) node, s);
            case CLASSVARDECLNODE: return buildClassVarDecl((ClassVarDeclNode) node, s);
            case COLON2NODE: return buildColon2((Colon2Node) node, s);
            case COLON3NODE: return buildColon3((Colon3Node) node, s);
            case COMPLEXNODE: return buildComplex((ComplexNode) node, s);
            case CONSTDECLNODE: return buildConstDecl((ConstDeclNode) node, s);
            case CONSTNODE: return searchConst(s, ((ConstNode) node).getName());
            case DASGNNODE: return buildDAsgn((DAsgnNode) node, s);
            case DEFINEDNODE: return buildGetDefinition(((DefinedNode) node).getExpressionNode(), s);
            case DEFNNODE: return buildDefn((MethodDefNode) node, s);
            case DEFSNODE: return buildDefs((DefsNode) node, s);
            case DOTNODE: return buildDot((DotNode) node, s);
            case DREGEXPNODE: return buildDRegexp((DRegexpNode) node, s);
            case DSTRNODE: return buildDStr((DStrNode) node, s);
            case DSYMBOLNODE: return buildDSymbol((DSymbolNode) node, s);
            case DVARNODE: return buildDVar((DVarNode) node, s);
            case DXSTRNODE: return buildDXStr((DXStrNode) node, s);
            case ENCODINGNODE: return buildEncoding((EncodingNode)node, s);
            case ENSURENODE: return buildEnsureNode((EnsureNode) node, s);
            case EVSTRNODE: return buildEvStr((EvStrNode) node, s);
            case FALSENODE: return buildFalse();
            case FCALLNODE: return buildFCall((FCallNode) node, s);
            case FIXNUMNODE: return buildFixnum((FixnumNode) node);
            case FLIPNODE: return buildFlip((FlipNode) node, s);
            case FLOATNODE: return buildFloat((FloatNode) node);
            case FORNODE: return buildFor((ForNode) node, s);
            case GLOBALASGNNODE: return buildGlobalAsgn((GlobalAsgnNode) node, s);
            case GLOBALVARNODE: return buildGlobalVar((GlobalVarNode) node, s);
            case HASHNODE: return buildHash((HashNode) node, s);
            case IFNODE: return buildIf((IfNode) node, s);
            case INSTASGNNODE: return buildInstAsgn((InstAsgnNode) node, s);
            case INSTVARNODE: return buildInstVar((InstVarNode) node, s);
            case ITERNODE: return buildIter((IterNode) node, s);
            case LAMBDANODE: return buildLambda((LambdaNode)node, s);
            case LITERALNODE: return buildLiteral((LiteralNode) node, s);
            case LOCALASGNNODE: return buildLocalAsgn((LocalAsgnNode) node, s);
            case LOCALVARNODE: return buildLocalVar((LocalVarNode) node, s);
            case MATCH2NODE: return buildMatch2((Match2Node) node, s);
            case MATCH3NODE: return buildMatch3((Match3Node) node, s);
            case MATCHNODE: return buildMatch((MatchNode) node, s);
            case MODULENODE: return buildModule((ModuleNode) node, s);
            case MULTIPLEASGNNODE: return buildMultipleAsgn((MultipleAsgnNode) node, s); // Only for 1.8
            case MULTIPLEASGN19NODE: return buildMultipleAsgn19((MultipleAsgn19Node) node, s);
            case NEWLINENODE: return buildNewline((NewlineNode) node, s);
            case NEXTNODE: return buildNext((NextNode) node, s);
            case NTHREFNODE: return buildNthRef((NthRefNode) node, s);
            case NILNODE: return buildNil();
            case OPASGNANDNODE: return buildOpAsgnAnd((OpAsgnAndNode) node, s);
            case OPASGNNODE: return buildOpAsgn((OpAsgnNode) node, s);
            case OPASGNORNODE: return buildOpAsgnOr((OpAsgnOrNode) node, s);
            case OPELEMENTASGNNODE: return buildOpElementAsgn((OpElementAsgnNode) node, s);
            case ORNODE: return buildOr((OrNode) node, s);
            case PREEXENODE: return buildPreExe((PreExeNode) node, s);
            case POSTEXENODE: return buildPostExe((PostExeNode) node, s);
            case RATIONALNODE: return buildRational((RationalNode) node);
            case REDONODE: return buildRedo(s);
            case REGEXPNODE: return buildRegexp((RegexpNode) node, s);
            case RESCUEBODYNODE:
                throw new NotCompilableException("rescue body is handled by rescue compilation at: " + node.getPosition());
            case RESCUENODE: return buildRescue((RescueNode) node, s);
            case RETRYNODE: return buildRetry(s);
            case RETURNNODE: return buildReturn((ReturnNode) node, s);
            case ROOTNODE:
                throw new NotCompilableException("Use buildRoot(); Root node at: " + node.getPosition());
            case SCLASSNODE: return buildSClass((SClassNode) node, s);
            case SELFNODE: return buildSelf(s);
            case SPLATNODE: return buildSplat((SplatNode) node, s);
            case STRNODE: return buildStr((StrNode) node, s);
            case SUPERNODE: return buildSuper((SuperNode) node, s);
            case SVALUENODE: return buildSValue((SValueNode) node, s);
            case SYMBOLNODE: return buildSymbol((SymbolNode) node);
            case TRUENODE: return buildTrue();
            case UNDEFNODE: return buildUndef(node, s);
            case UNTILNODE: return buildUntil((UntilNode) node, s);
            case VALIASNODE: return buildVAlias((VAliasNode) node, s);
            case VCALLNODE: return buildVCall((VCallNode) node, s);
            case WHILENODE: return buildWhile((WhileNode) node, s);
            case WHENNODE: assert false : "When nodes are handled by case node compilation."; return null;
            case XSTRNODE: return buildXStr((XStrNode) node, s);
            case YIELDNODE: return buildYield((YieldNode) node, s);
            case ZARRAYNODE: return buildZArray(s);
            case ZSUPERNODE: return buildZSuper((ZSuperNode) node, s);
            default: throw new NotCompilableException("Unknown node encountered in builder: " + node.getClass());
        }
    }

    private boolean hasListener() {
        return manager.getIRScopeListener() != null;
    }

    public static IRBuilder newIRBuilder(IRManager manager) {
        return new IRBuilder(manager);
    }

    public Node skipOverNewlines(IRScope s, Node n) {
        if (n.getNodeType() == NodeType.NEWLINENODE) {
            // Do not emit multiple line number instrs for the same line
            int currLineNum = n.getPosition().getLine();
            if (currLineNum != _lastProcessedLineNum) {
                if (RubyInstanceConfig.FULL_TRACE_ENABLED) {
                    addInstr(s, new TraceInstr(RubyEvent.LINE, methodNameFor(s), s.getFileName(), currLineNum));
                }
               addInstr(s, new LineNumberInstr(currLineNum));
               _lastProcessedLineNum = currLineNum;
            }
        }

        while (n.getNodeType() == NodeType.NEWLINENODE) {
            n = ((NewlineNode) n).getNextNode();
        }

        return n;
    }

    public Operand build(Node node, IRScope s) {
        if (node == null) return null;

        if (s == null) {
            System.out.println("Got a null scope!");
            throw new NotCompilableException("Unknown node encountered in builder: " + node);
        }
        if (hasListener()) {
            IRScopeListener listener = manager.getIRScopeListener();
            listener.startBuildOperand(node, s);
        }
        Operand operand = buildOperand(node, s);
        if (hasListener()) {
            IRScopeListener listener = manager.getIRScopeListener();
            listener.endBuildOperand(node, s, operand);
        }
        return operand;
    }

    public Operand buildLambda(LambdaNode node, IRScope s) {
        IRClosure closure = new IRClosure(manager, s, node.getPosition().getLine(), node.getScope(), Arity.procArityOf(node.getArgs()), node.getArgumentType());

        // Create a new nested builder to ensure this gets its own IR builder state
        // like the ensure block stack
        IRBuilder closureBuilder = newIRBuilder(manager);

        // Prepare all implicit state (self, frame block, etc)
        closureBuilder.prepareImplicitState(closure);

        // Set %current_scope = <current-scope>
        // Set %current_module = <current-module>
        closureBuilder.addInstr(closure, new CopyInstr(closure.getCurrentScopeVariable(), CURRENT_SCOPE[0]));
        closureBuilder.addInstr(closure, new CopyInstr(closure.getCurrentModuleVariable(), SCOPE_MODULE[0]));

        // args
        closureBuilder.receiveBlockArgs(node, closure);

        Operand closureRetVal = node.getBody() == null ? manager.getNil() : closureBuilder.build(node.getBody(), closure);

        // can be U_NIL if the node is an if node with returns in both branches.
        if (closureRetVal != U_NIL) closureBuilder.addInstr(closure, new ReturnInstr(closureRetVal));

        closureBuilder.handleBreakAndReturnsInLambdas(closure);

        Variable lambda = s.createTemporaryVariable();
        WrappedIRClosure lambdaBody = new WrappedIRClosure(closure.getSelf(), closure);
        addInstr(s, new BuildLambdaInstr(lambda, lambdaBody, node.getPosition()));
        return lambda;
    }

    public Operand buildEncoding(EncodingNode node, IRScope s) {
        Variable ret = s.createTemporaryVariable();
        addInstr(s, new GetEncodingInstr(ret, node.getEncoding()));
        return ret;
    }

    // Non-arg masgn
    public Operand buildMultipleAsgn19(MultipleAsgn19Node multipleAsgnNode, IRScope s) {
        Operand  values = build(multipleAsgnNode.getValueNode(), s);
        Variable ret = getValueInTemporaryVariable(s, values);
        Variable tmp = s.createTemporaryVariable();
        addInstr(s, new ToAryInstr(tmp, ret));
        buildMultipleAsgn19Assignment(multipleAsgnNode, s, null, tmp);
        return ret;
    }

    protected Variable copyAndReturnValue(IRScope s, Operand val) {
        return addResultInstr(s, new CopyInstr(s.createTemporaryVariable(), val));
    }

    protected Variable getValueInTemporaryVariable(IRScope s, Operand val) {
        if (val != null && val instanceof TemporaryVariable) return (Variable) val;

        return copyAndReturnValue(s, val);
    }

    // Return the last argument in the list as this represents rhs of the overall attrassign expression
    // e.g. 'a[1] = 2 #=> 2' or 'a[1] = 1,2,3 #=> [1,2,3]'
    protected Operand buildAttrAssignCallArgs(List<Operand> argsList, Node args, IRScope s) {
        switch (args.getNodeType()) {
            case ARRAYNODE: {     // a[1] = 2; a[1,2,3] = 4,5,6
                Operand last = manager.getNil();
                for (Node n: args.childNodes()) {
                    last = build(n, s);
                    argsList.add(last);
                }
                return last;
            }
            case ARGSPUSHNODE:  { // a[1, *b] = 2
                ArgsPushNode argsPushNode = (ArgsPushNode)args;
                Operand lhs = build(argsPushNode.getFirstNode(), s);
                Operand rhs = build(argsPushNode.getSecondNode(), s);
                Variable res = s.createTemporaryVariable();
                addInstr(s, new BuildCompoundArrayInstr(res, lhs, rhs, true));
                argsList.add(new Splat(res));
                return rhs;
            }
            case SPLATNODE: {     // a[1] = *b
                Splat rhs = new Splat(buildSplat((SplatNode)args, s));
                argsList.add(rhs);
                return rhs;
            }
        }

        throw new NotCompilableException("Invalid node for attrassign call args: " + args.getClass().getSimpleName() + ":" + args.getPosition());
    }

    protected Operand[] buildCallArgs(Node args, IRScope s) {
        switch (args.getNodeType()) {
            case ARGSCATNODE:
            case ARGSPUSHNODE:
                return new Operand[] { new Splat(build(args, s)) };
            case ARRAYNODE: {
                List<Node> children = args.childNodes();
                int numberOfArgs = children.size();
                Operand[] builtArgs = new Operand[numberOfArgs];

                for (int i = 0; i < numberOfArgs; i++) {
                    builtArgs[i] = build(children.get(i), s);
                }
                return builtArgs;
            }
            case SPLATNODE:
                return new Operand[] { new Splat(buildSplat((SplatNode)args, s)) };
        }

        throw new NotCompilableException("Invalid node for call args: " + args.getClass().getSimpleName() + ":" + args.getPosition());
    }

    public Operand[] setupCallArgs(Node args, IRScope s) {
        return args == null ? Operand.EMPTY_ARRAY : buildCallArgs(args, s);
    }

    public static Operand[] addArg(Operand[] args, Operand extraArg) {
        Operand[] newArgs = new Operand[args.length + 1];
        System.arraycopy(args, 0, newArgs, 0, args.length);
        newArgs[args.length] = extraArg;
        return newArgs;
    }

    // Non-arg masgn (actually a nested masgn)
    public void buildVersionSpecificAssignment(Node node, IRScope s, Variable v) {
        switch (node.getNodeType()) {
        case MULTIPLEASGN19NODE: {
            Variable tmp = s.createTemporaryVariable();
            addInstr(s, new ToAryInstr(tmp, v));
            buildMultipleAsgn19Assignment((MultipleAsgn19Node)node, s, null, tmp);
            break;
        }
        default:
            throw new NotCompilableException("Can't build assignment node: " + node);
        }
    }

    // This method is called to build assignments for a multiple-assignment instruction
    public void buildAssignment(Node node, IRScope s, Variable rhsVal) {
        switch (node.getNodeType()) {
            case ATTRASSIGNNODE:
                buildAttrAssignAssignment(node, s, rhsVal);
                break;
            case CLASSVARASGNNODE:
                addInstr(s, new PutClassVariableInstr(classVarDefinitionContainer(s), ((ClassVarAsgnNode)node).getName(), rhsVal));
                break;
            case CLASSVARDECLNODE:
                addInstr(s, new PutClassVariableInstr(classVarDeclarationContainer(s), ((ClassVarDeclNode)node).getName(), rhsVal));
                break;
            case CONSTDECLNODE:
                buildConstDeclAssignment((ConstDeclNode) node, s, rhsVal);
                break;
            case DASGNNODE: {
                DAsgnNode variable = (DAsgnNode) node;
                int depth = variable.getDepth();
                addInstr(s, new CopyInstr(s.getLocalVariable(variable.getName(), depth), rhsVal));
                break;
            }
            case GLOBALASGNNODE:
                addInstr(s, new PutGlobalVarInstr(((GlobalAsgnNode)node).getName(), rhsVal));
                break;
            case INSTASGNNODE:
                // NOTE: if 's' happens to the a class, this is effectively an assignment of a class instance variable
                addInstr(s, new PutFieldInstr(s.getSelf(), ((InstAsgnNode)node).getName(), rhsVal));
                break;
            case LOCALASGNNODE: {
                LocalAsgnNode localVariable = (LocalAsgnNode) node;
                int depth = localVariable.getDepth();
                addInstr(s, new CopyInstr(s.getLocalVariable(localVariable.getName(), depth), rhsVal));
                break;
            }
            case ZEROARGNODE:
                throw new NotCompilableException("Shouldn't get here; zeroarg does not do assignment: " + node);
            default:
                buildVersionSpecificAssignment(node, s, rhsVal);
        }
    }

    protected LocalVariable getBlockArgVariable(IRScope s, String name, int depth) {
        if (!(s instanceof IRFor)) throw new NotCompilableException("Cannot ask for block-arg variable in 1.9 mode");

        return s.getLocalVariable(name, depth);
    }

    protected void receiveBlockArg(IRScope s, Variable v, Operand argsArray, int argIndex, boolean isSplat) {
        if (argsArray != null) {
            // We are in a nested receive situation -- when we are not at the root of a masgn tree
            // Ex: We are trying to receive (b,c) in this example: "|a, (b,c), d| = ..."
            if (isSplat) addInstr(s, new RestArgMultipleAsgnInstr(v, argsArray, argIndex));
            else addInstr(s, new ReqdArgMultipleAsgnInstr(v, argsArray, argIndex));
        } else {
            // argsArray can be null when the first node in the args-node-ast is a multiple-assignment
            // For example, for-nodes
            addInstr(s, isSplat ? new ReceiveRestArgInstr(v, argIndex, argIndex) : new ReceivePreReqdArgInstr(v, argIndex));
        }
    }

    public void buildVersionSpecificBlockArgsAssignment(Node node, IRScope s) {
        if (!(s instanceof IRFor)) throw new NotCompilableException("Should not have come here for block args assignment in 1.9 mode: " + node);

        // Argh!  For-loop bodies and regular iterators are different in terms of block-args!
        switch (node.getNodeType()) {
            case MULTIPLEASGN19NODE: {
                ListNode sourceArray = ((MultipleAsgn19Node) node).getPre();
                int i = 0;
                for (Node an: sourceArray.childNodes()) {
                    // Use 1.8 mode version for this
                    buildBlockArgsAssignment(an, s, null, i, false);
                    i++;
                }
                break;
            }
            default:
                throw new NotCompilableException("Can't build assignment node: " + node);
        }
    }

    // This method is called to build arguments for a block!
    public void buildBlockArgsAssignment(Node node, IRScope s, Operand argsArray, int argIndex, boolean isSplat) {
        Variable v;
        switch (node.getNodeType()) {
            case ATTRASSIGNNODE:
                v = s.createTemporaryVariable();
                receiveBlockArg(s, v, argsArray, argIndex, isSplat);
                buildAttrAssignAssignment(node, s, v);
                break;
            case DASGNNODE: {
                DAsgnNode dynamicAsgn = (DAsgnNode) node;
                v = getBlockArgVariable(s, dynamicAsgn.getName(), dynamicAsgn.getDepth());
                receiveBlockArg(s, v, argsArray, argIndex, isSplat);
                break;
            }
            case CLASSVARASGNNODE:
                v = s.createTemporaryVariable();
                receiveBlockArg(s, v, argsArray, argIndex, isSplat);
                addInstr(s, new PutClassVariableInstr(classVarDefinitionContainer(s), ((ClassVarAsgnNode)node).getName(), v));
                break;
            case CLASSVARDECLNODE:
                v = s.createTemporaryVariable();
                receiveBlockArg(s, v, argsArray, argIndex, isSplat);
                addInstr(s, new PutClassVariableInstr(classVarDeclarationContainer(s), ((ClassVarDeclNode)node).getName(), v));
                break;
            case CONSTDECLNODE:
                v = s.createTemporaryVariable();
                receiveBlockArg(s, v, argsArray, argIndex, isSplat);
                buildConstDeclAssignment((ConstDeclNode) node, s, v);
                break;
            case GLOBALASGNNODE:
                v = s.createTemporaryVariable();
                receiveBlockArg(s, v, argsArray, argIndex, isSplat);
                addInstr(s, new PutGlobalVarInstr(((GlobalAsgnNode)node).getName(), v));
                break;
            case INSTASGNNODE:
                v = s.createTemporaryVariable();
                receiveBlockArg(s, v, argsArray, argIndex, isSplat);
                // NOTE: if 's' happens to the a class, this is effectively an assignment of a class instance variable
                addInstr(s, new PutFieldInstr(s.getSelf(), ((InstAsgnNode)node).getName(), v));
                break;
            case LOCALASGNNODE: {
                LocalAsgnNode localVariable = (LocalAsgnNode) node;
                int depth = localVariable.getDepth();
                v = getBlockArgVariable(s, localVariable.getName(), depth);
                receiveBlockArg(s, v, argsArray, argIndex, isSplat);
                break;
            }
            case ZEROARGNODE:
                throw new NotCompilableException("Shouldn't get here; zeroarg does not do assignment: " + node);
            default:
                buildVersionSpecificBlockArgsAssignment(node, s);
        }
    }

    public Operand buildAlias(final AliasNode alias, IRScope s) {
        Operand newName = build(alias.getNewName(), s);
        Operand oldName = build(alias.getOldName(), s);
        addInstr(s, new AliasInstr(newName, oldName));

        return manager.getNil();
    }

    // Translate "ret = (a && b)" --> "ret = (a ? b : false)" -->
    //
    //    v1 = -- build(a) --
    //       OPT: ret can be set to v1, but effectively v1 is false if we take the branch to L.
    //            while this info can be inferred by using attributes, why bother if we can do this?
    //    ret = v1
    //    beq(v1, false, L)
    //    v2 = -- build(b) --
    //    ret = v2
    // L:
    //
    public Operand buildAnd(final AndNode andNode, IRScope s) {
        if (andNode.getFirstNode().getNodeType().alwaysTrue()) {
            // build first node (and ignore its result) and then second node
            build(andNode.getFirstNode(), s);
            return build(andNode.getSecondNode(), s);
        } else if (andNode.getFirstNode().getNodeType().alwaysFalse()) {
            // build first node only and return its value
            return build(andNode.getFirstNode(), s);
        } else {
            Label    l   = s.getNewLabel();
            Operand  v1  = build(andNode.getFirstNode(), s);
            Variable ret = getValueInTemporaryVariable(s, v1);
            addInstr(s, BEQInstr.create(v1, manager.getFalse(), l));
            Operand  v2  = build(andNode.getSecondNode(), s);
            addInstr(s, new CopyInstr(ret, v2));
            addInstr(s, new LabelInstr(l));
            return ret;
        }
    }

    public Operand buildArray(Node node, IRScope s) {
        List<Operand> elts = new ArrayList<>();
        for (Node e: node.childNodes())
            elts.add(build(e, s));

        return copyAndReturnValue(s, new Array(elts));
    }

    public Operand buildArgsCat(final ArgsCatNode argsCatNode, IRScope s) {
        Operand v1 = build(argsCatNode.getFirstNode(), s);
        Operand v2 = build(argsCatNode.getSecondNode(), s);
        Variable res = s.createTemporaryVariable();
        addInstr(s, new BuildCompoundArrayInstr(res, v1, v2, false));
        return res;
    }

    public Operand buildArgsPush(final ArgsPushNode node, IRScope s) {
        Operand lhs = build(node.getFirstNode(), s);
        Operand rhs = build(node.getSecondNode(), s);

        return addResultInstr(s, new BuildCompoundArrayInstr(s.createTemporaryVariable(), lhs, rhs, true));
    }

    private Operand buildAttrAssign(final AttrAssignNode attrAssignNode, IRScope s) {
        Operand obj = build(attrAssignNode.getReceiverNode(), s);
        List<Operand> args = new ArrayList<>();
        Node argsNode = attrAssignNode.getArgsNode();
        Operand lastArg = (argsNode == null) ? manager.getNil() : buildAttrAssignCallArgs(args, argsNode, s);
        addInstr(s, AttrAssignInstr.create(obj, attrAssignNode.getName(), args.toArray(new Operand[args.size()])));
        return lastArg;
    }

    public Operand buildAttrAssignAssignment(Node node, IRScope s, Operand value) {
        final AttrAssignNode attrAssignNode = (AttrAssignNode) node;
        Operand obj = build(attrAssignNode.getReceiverNode(), s);
        Operand[] args = setupCallArgs(attrAssignNode.getArgsNode(), s);
        args = addArg(args, value);
        addInstr(s, AttrAssignInstr.create(obj, attrAssignNode.getName(), args));
        return value;
    }

    public Operand buildBackref(BackRefNode node, IRScope s) {
        // SSS FIXME: Required? Verify with Tom/Charlie
        return copyAndReturnValue(s, new Backref(node.getType()));
    }

    public Operand buildBegin(BeginNode beginNode, IRScope s) {
        return build(beginNode.getBodyNode(), s);
    }

    public Operand buildBignum(BignumNode node) {
        // SSS: Since bignum literals are effectively interned objects, no need to copyAndReturnValue(...)
        // Or is this a premature optimization?
        return new Bignum(node.getValue());
    }

    public Operand buildBlock(BlockNode node, IRScope s) {
        Operand retVal = null;
        for (Node child : node.childNodes()) {
            retVal = build(child, s);
        }

        // Value of the last expression in the block
        return retVal;
    }

    public Operand buildBreak(BreakNode breakNode, IRScope s) {
        IRLoop currLoop = getCurrentLoop();

        Operand rv = build(breakNode.getValueNode(), s);
        // If we have ensure blocks, have to run those first!
        if (!activeEnsureBlockStack.empty()) emitEnsureBlocks(s, currLoop);
        else if (!activeRescueBlockStack.empty()) activeRescueBlockStack.peek().restoreException(this, s, currLoop);

        if (currLoop != null) {
            addInstr(s, new CopyInstr(currLoop.loopResult, rv));
            addInstr(s, new JumpInstr(currLoop.loopEndLabel));
        } else {
            if (s instanceof IRClosure) {
                // This lexical scope value is only used (and valid) in regular block contexts.
                // If this instruction is executed in a Proc or Lambda context, the lexical scope value is useless.
                IRScope returnScope = s.getLexicalParent();
                // In 1.9 and later modes, no breaks from evals
                if (s instanceof IREvalScript || returnScope == null) addInstr(s, new ThrowExceptionInstr(IRException.BREAK_LocalJumpError));
                else addInstr(s, new BreakInstr(rv, returnScope.getName()));
            } else {
                // We are not in a closure or a loop => bad break instr!
                addInstr(s, new ThrowExceptionInstr(IRException.BREAK_LocalJumpError));
            }
        }

        // Once the break instruction executes, control exits this scope
        return U_NIL;
    }

    private void handleNonlocalReturnInMethod(IRScope s) {
        Label rBeginLabel = s.getNewLabel();
        Label rEndLabel   = s.getNewLabel();
        Label gebLabel    = s.getNewLabel();

        // Protect the entire body as it exists now with the global ensure block
        //
        // Add label and marker instruction in reverse order to the beginning
        // so that the label ends up being the first instr.
        addInstrAtBeginning(s, new ExceptionRegionStartMarkerInstr(gebLabel));
        addInstrAtBeginning(s, new LabelInstr(rBeginLabel));
        addInstr(s, new ExceptionRegionEndMarkerInstr());

        // Receive exceptions (could be anything, but the handler only processes IRReturnJumps)
        addInstr(s, new LabelInstr(gebLabel));
        Variable exc = s.createTemporaryVariable();
        addInstr(s, new ReceiveJRubyExceptionInstr(exc));

        if (RubyInstanceConfig.FULL_TRACE_ENABLED) {
            addInstr(s, new TraceInstr(RubyEvent.RETURN, s.getName(), s.getFileName(), -1));
        }

        // Handle break using runtime helper
        // --> IRRuntimeHelpers.handleNonlocalReturn(scope, bj, blockType)
        Variable ret = s.createTemporaryVariable();
        addInstr(s, new RuntimeHelperCall(ret, HANDLE_NONLOCAL_RETURN, new Operand[]{exc} ));
        addInstr(s, new ReturnInstr(ret));

        // End
        addInstr(s, new LabelInstr(rEndLabel));
    }

    private Operand receiveBreakException(IRScope s, Operand block, CodeBlock codeBlock) {
        // Check if we have to handle a break
        if (block == null ||
            !(block instanceof WrappedIRClosure) ||
            !(((WrappedIRClosure)block).getClosure()).flags.contains(IRFlags.HAS_BREAK_INSTRS)) {
            // No protection needed -- add the call and return
            return codeBlock.run();
        }

        Label rBeginLabel = s.getNewLabel();
        Label rEndLabel   = s.getNewLabel();
        Label rescueLabel = s.getNewLabel();

        // Protected region
        addInstr(s, new LabelInstr(rBeginLabel));
        addInstr(s, new ExceptionRegionStartMarkerInstr(rescueLabel));
        Variable callResult = (Variable)codeBlock.run();
        addInstr(s, new JumpInstr(rEndLabel));
        addInstr(s, new ExceptionRegionEndMarkerInstr());

        // Receive exceptions (could be anything, but the handler only processes IRBreakJumps)
        addInstr(s, new LabelInstr(rescueLabel));
        Variable exc = s.createTemporaryVariable();
        addInstr(s, new ReceiveJRubyExceptionInstr(exc));

        // Handle break using runtime helper
        // --> IRRuntimeHelpers.handlePropagatedBreak(context, scope, bj, blockType)
        addInstr(s, new RuntimeHelperCall(callResult, HANDLE_PROPAGATE_BREAK, new Operand[]{exc} ));

        // End
        addInstr(s, new LabelInstr(rEndLabel));

        return callResult;
    }

    // Wrap call in a rescue handler that catches the IRBreakJump
    private void receiveBreakException(final IRScope s, Operand block, final CallInstr callInstr) {
        receiveBreakException(s, block, new CodeBlock() { public Operand run() { addInstr(s, callInstr); return callInstr.getResult(); } });
    }

    public Operand buildCall(CallNode callNode, IRScope s) {
        Node          callArgsNode = callNode.getArgsNode();
        Node          receiverNode = callNode.getReceiverNode();

        // check for "string".freeze
        if (receiverNode instanceof StrNode && callNode.getName().equals("freeze")) {
            // frozen string optimization
            return new FrozenString(((StrNode)receiverNode).getValue());
        }

        // Though you might be tempted to move this build into the CallInstr as:
        //    new Callinstr( ... , build(receiverNode, s), ...)
        // that is incorrect IR because the receiver has to be built *before* call arguments are built
        // to preserve expected code execution order
        Operand       receiver     = build(receiverNode, s);
        Operand[] args         = setupCallArgs(callArgsNode, s);
        Operand       block        = setupCallClosure(callNode.getIterNode(), s);
        Variable      callResult   = s.createTemporaryVariable();
        CallInstr     callInstr    = CallInstr.create(callResult, callNode.getName(), receiver, args, block);

        // This is to support the ugly Proc.new with no block, which must see caller's frame
        if (
                callNode.getName().equals("new") &&
                receiverNode instanceof ConstNode &&
                ((ConstNode)receiverNode).getName().equals("Proc")) {
            callInstr.setProcNew(true);
        }

        receiveBreakException(s, block, callInstr);
        return callResult;
    }

    public Operand buildCase(CaseNode caseNode, IRScope s) {
        // get the incoming case value
        Operand value = build(caseNode.getCaseNode(), s);

        // This is for handling case statements without a value (see example below)
        //   case
        //     when true <blah>
        //     when false <blah>
        //   end
        if (value == null) value = UndefinedValue.UNDEFINED;

        Label     endLabel  = s.getNewLabel();
        boolean   hasElse   = (caseNode.getElseNode() != null);
        Label     elseLabel = s.getNewLabel();
        Variable  result    = s.createTemporaryVariable();

        List<Label> labels = new ArrayList<>();
        Map<Label, Node> bodies = new HashMap<>();

        // build each "when"
        for (Node aCase : caseNode.getCases().childNodes()) {
            WhenNode whenNode = (WhenNode)aCase;
            Label bodyLabel = s.getNewLabel();

            Variable eqqResult = s.createTemporaryVariable();
            labels.add(bodyLabel);
            Operand v1, v2;
            if (whenNode.getExpressionNodes() instanceof ListNode
                    // DNode produces a proper result, so we don't want the special ListNode handling below
                    // FIXME: This is obviously gross, and we need a better way to filter out non-expression ListNode here
                    // See GH #2423
                    && !(whenNode.getExpressionNodes() instanceof DNode)) {
                // Note about refactoring:
                // - BEQInstr has a quick implementation when the second operand is a boolean literal
                //   If it can be fixed to do this even on the first operand, we can switch around
                //   v1 and v2 in the UndefinedValue scenario and DRY out this code.
                // - Even with this asymmetric implementation of BEQInstr, you might be tempted to
                //   switch around v1 and v2 in the else case.  But, that is equivalent to this Ruby code change:
                //      (v1 == value) instead of (value == v1)
                //   It seems that they should be identical, but the first one is v1.==(value) and the second one is
                //   value.==(v1).  This is just fine *if* the Ruby programmer has implemented an algebraically
                //   symmetric "==" method on those objects.  If not, then, the results might be unexpected where the
                //   code (intentionally or otherwise) relies on this asymmetry of "==".  While it could be argued
                //   that this a Ruby code bug, we will just try to preserve the order of the == check as it appears
                //   in the Ruby code.
                if (value == UndefinedValue.UNDEFINED)  {
                    v1 = build(whenNode.getExpressionNodes(), s);
                    v2 = manager.getTrue();
                } else {
                    v1 = value;
                    v2 = build(whenNode.getExpressionNodes(), s);
                }
            } else {
                addInstr(s, new EQQInstr(eqqResult, build(whenNode.getExpressionNodes(), s), value));
                v1 = eqqResult;
                v2 = manager.getTrue();
            }
            addInstr(s, BEQInstr.create(v1, v2, bodyLabel));

            // SSS FIXME: This doesn't preserve original order of when clauses.  We could consider
            // preserving the order (or maybe not, since we would have to sort the constants first
            // in any case) for outputting jump tables in certain situations.
            //
            // add body to map for emitting later
            bodies.put(bodyLabel, whenNode.getBodyNode());
        }

        // Jump to else in case nothing matches!
        addInstr(s, new JumpInstr(elseLabel));

        // Build "else" if it exists
        if (hasElse) {
            labels.add(elseLabel);
            bodies.put(elseLabel, caseNode.getElseNode());
        }

        // Now, emit bodies while preserving when clauses order
        for (Label whenLabel: labels) {
            addInstr(s, new LabelInstr(whenLabel));
            Operand bodyValue = build(bodies.get(whenLabel), s);
            // bodyValue can be null if the body ends with a return!
            if (bodyValue != null) {
                // SSS FIXME: Do local optimization of break results (followed by a copy & jump) to short-circuit the jump right away
                // rather than wait to do it during an optimization pass when a dead jump needs to be removed.  For this, you have
                // to look at what the last generated instruction was.
                addInstr(s, new CopyInstr(result, bodyValue));
                addInstr(s, new JumpInstr(endLabel));
            }
        }

        if (!hasElse) {
            addInstr(s, new LabelInstr(elseLabel));
            addInstr(s, new CopyInstr(result, manager.getNil()));
            addInstr(s, new JumpInstr(endLabel));
        }

        // Close it out
        addInstr(s, new LabelInstr(endLabel));

        return result;
    }

    /**
     * Build a new class and add it to the current scope (s).
     */
    public Operand buildClass(ClassNode classNode, IRScope s) {
        Node superNode = classNode.getSuperNode();
        Colon3Node cpath = classNode.getCPath();
        Operand superClass = (superNode == null) ? null : build(superNode, s);
        String className = cpath.getName();
        Operand container = getContainerFromCPath(cpath, s);
        IRClassBody body = new IRClassBody(manager, s, className, classNode.getPosition().getLine(), classNode.getScope());
        Variable classVar = addResultInstr(s, new DefineClassInstr(s.createTemporaryVariable(), body, container, superClass));

        return buildModuleOrClassBody(s, classVar, body, classNode.getBodyNode(), classNode.getPosition().getLine(), NullBlock.INSTANCE);
    }

    // class Foo; class << self; end; end
    // Here, the class << self declaration is in Foo's body.
    // Foo is the class in whose context this is being defined.
    public Operand buildSClass(SClassNode sclassNode, IRScope s) {
        Operand receiver = build(sclassNode.getReceiverNode(), s);
        IRModuleBody body = new IRMetaClassBody(manager, s, manager.getMetaClassName(), sclassNode.getPosition().getLine(), sclassNode.getScope());
        Variable sClassVar = addResultInstr(s, new DefineMetaClassInstr(s.createTemporaryVariable(), receiver, body));

        // sclass bodies inherit the block of their containing method
        return buildModuleOrClassBody(s, sClassVar, body, sclassNode.getBodyNode(), sclassNode.getPosition().getLine(), s.getYieldClosureVariable());
    }

    // @@c
    public Operand buildClassVar(ClassVarNode node, IRScope s) {
        Variable ret = s.createTemporaryVariable();
        addInstr(s, new GetClassVariableInstr(ret, classVarDefinitionContainer(s), node.getName()));
        return ret;
    }

    // Add the specified result instruction to the scope and return its result variable.
    private Variable addResultInstr(IRScope s, ResultInstr instr) {
        addInstr(s, (Instr) instr);

        return instr.getResult();
    }

    // ClassVarAsgn node is assignment within a method/closure scope
    //
    // def foo
    //   @@c = 1
    // end
    public Operand buildClassVarAsgn(final ClassVarAsgnNode classVarAsgnNode, IRScope s) {
        Operand val = build(classVarAsgnNode.getValueNode(), s);
        addInstr(s, new PutClassVariableInstr(classVarDefinitionContainer(s), classVarAsgnNode.getName(), val));
        return val;
    }

    // ClassVarDecl node is assignment outside method/closure scope (top-level, class, module)
    //
    // class C
    //   @@c = 1
    // end
    public Operand buildClassVarDecl(final ClassVarDeclNode classVarDeclNode, IRScope s) {
        Operand val = build(classVarDeclNode.getValueNode(), s);
        addInstr(s, new PutClassVariableInstr(classVarDeclarationContainer(s), classVarDeclNode.getName(), val));
        return val;
    }

    public Operand classVarDeclarationContainer(IRScope s) {
        return classVarContainer(s, true);
    }

    public Operand classVarDefinitionContainer(IRScope s) {
        return classVarContainer(s, false);
    }

    // SSS FIXME: This feels a little ugly.  Is there a better way of representing this?
    public Operand classVarContainer(IRScope s, boolean declContext) {
        /* -------------------------------------------------------------------------------
         * We are looking for the nearest enclosing scope that is a non-singleton class body
         * without running into an eval-scope in between.
         *
         * Stop lexical scope walking at an eval script boundary.  Evals are essentially
         * a way for a programmer to splice an entire tree of lexical scopes at the point
         * where the eval happens.  So, when we hit an eval-script boundary at compile-time,
         * defer scope traversal to when we know where this scope has been spliced in.
         * ------------------------------------------------------------------------------- */
        int n = 0;
        IRScope cvarScope = s;
        while (cvarScope != null && !(cvarScope instanceof IREvalScript) && !cvarScope.isNonSingletonClassBody()) {
            cvarScope = cvarScope.getLexicalParent();
            n++;
        }

        if ((cvarScope != null) && cvarScope.isNonSingletonClassBody()) {
            return ScopeModule.ModuleFor(n);
        } else {
            return addResultInstr(s, new GetClassVarContainerModuleInstr(s.createTemporaryVariable(),
                    s.getCurrentScopeVariable(), declContext ? null : s.getSelf()));
        }
    }

    public Operand buildConstDecl(ConstDeclNode node, IRScope s) {
        return buildConstDeclAssignment(node, s, build(node.getValueNode(), s));
    }

    private Operand findContainerModule(IRScope s) {
        int nearestModuleBodyDepth = s.getNearestModuleReferencingScopeDepth();
        return (nearestModuleBodyDepth == -1) ? s.getCurrentModuleVariable() : ScopeModule.ModuleFor(nearestModuleBodyDepth);
    }

    private Operand startingSearchScope(IRScope s) {
        int nearestModuleBodyDepth = s.getNearestModuleReferencingScopeDepth();
        return nearestModuleBodyDepth == -1 ? s.getCurrentScopeVariable() : CurrentScope.ScopeFor(nearestModuleBodyDepth);
    }

    public Operand buildConstDeclAssignment(ConstDeclNode constDeclNode, IRScope s, Operand val) {
        Node constNode = constDeclNode.getConstNode();

        if (constNode == null) {
            addInstr(s, new PutConstInstr(findContainerModule(s), constDeclNode.getName(), val));
        } else if (constNode.getNodeType() == NodeType.COLON2NODE) {
            Operand module = build(((Colon2Node) constNode).getLeftNode(), s);
            addInstr(s, new PutConstInstr(module, constDeclNode.getName(), val));
        } else { // colon3, assign in Object
            addInstr(s, new PutConstInstr(new ObjectClass(), constDeclNode.getName(), val));
        }

        return val;
    }

    private void genInheritanceSearchInstrs(IRScope s, Operand startingModule, Variable constVal, Label foundLabel, boolean noPrivateConstants, String name) {
        addInstr(s, new InheritanceSearchConstInstr(constVal, startingModule, name, noPrivateConstants));
        addInstr(s, BNEInstr.create(constVal, UndefinedValue.UNDEFINED, foundLabel));
        addInstr(s, new ConstMissingInstr(constVal, startingModule, name));
        addInstr(s, new LabelInstr(foundLabel));
    }

    private Operand searchConstInInheritanceHierarchy(IRScope s, Operand startingModule, String name) {
        Variable constVal = s.createTemporaryVariable();
        genInheritanceSearchInstrs(s, startingModule, constVal, s.getNewLabel(), true, name);
        return constVal;
    }

    private Operand searchConst(IRScope s, String name) {
        final boolean noPrivateConstants = false;
        Variable v = s.createTemporaryVariable();
/**
 * SSS FIXME: Went back to a single instruction for now.
 *
 * Do not split search into lexical-search, inheritance-search, and const-missing instrs.
 *
        Label foundLabel = s.getNewLabel();
        addInstr(s, new LexicalSearchConstInstr(v, startingSearchScope(s), name));
        addInstr(s, BNEInstr.create(v, UndefinedValue.UNDEFINED, foundLabel));
        genInheritanceSearchInstrs(s, findContainerModule(startingScope), v, foundLabel, noPrivateConstants, name);
**/
        addInstr(s, new SearchConstInstr(v, name, startingSearchScope(s), noPrivateConstants));
        return v;
    }

    public Operand buildColon2(final Colon2Node iVisited, IRScope s) {
        Node leftNode = iVisited.getLeftNode();
        final String name = iVisited.getName();

        // Colon2ImplicitNode
        if (leftNode == null) return searchConst(s, name);

        // Colon2ConstNode
        // 1. Load the module first (lhs of node)
        // 2. Then load the constant from the module
        Operand module = build(leftNode, s);
        return searchConstInInheritanceHierarchy(s, module, name);
    }

    public Operand buildColon3(Colon3Node node, IRScope s) {
        return searchConstInInheritanceHierarchy(s, new ObjectClass(), node.getName());
    }

    public Operand buildComplex(ComplexNode node, IRScope s) {
        return new Complex((ImmutableLiteral) build(node.getNumber(), s));
    }

    interface CodeBlock {
        public Operand run();
    }

    private Operand protectCodeWithRescue(IRScope s, CodeBlock protectedCode, CodeBlock rescueBlock) {
        // This effectively mimics a begin-rescue-end code block
        // Except this catches all exceptions raised by the protected code

        Variable rv = s.createTemporaryVariable();
        Label rBeginLabel = s.getNewLabel();
        Label rEndLabel   = s.getNewLabel();
        Label rescueLabel = s.getNewLabel();

        // Protected region code
        addInstr(s, new LabelInstr(rBeginLabel));
        addInstr(s, new ExceptionRegionStartMarkerInstr(rescueLabel));
        Object v1 = protectedCode.run(); // YIELD: Run the protected code block
        addInstr(s, new CopyInstr(rv, (Operand)v1));
        addInstr(s, new JumpInstr(rEndLabel));
        addInstr(s, new ExceptionRegionEndMarkerInstr());

        // SSS FIXME: Create an 'Exception' operand type to eliminate the constant lookup below
        // We could preload a set of constant objects that are preloaded at boot time and use them
        // directly in IR when we know there is no lookup involved.
        //
        // new Operand type: CachedClass(String name)?
        //
        // Some candidates: Exception, StandardError, Fixnum, Object, Boolean, etc.
        // So, when they are referenced, they are fetched directly from the runtime object
        // which probably already has cached references to these constants.
        //
        // But, unsure if this caching is safe ... so, just an idea here for now.

        // Rescue code
        Label caughtLabel = s.getNewLabel();
        Variable exc = s.createTemporaryVariable();
        Variable excType = s.createTemporaryVariable();

        // Receive 'exc' and verify that 'exc' is of ruby-type 'Exception'
        addInstr(s, new LabelInstr(rescueLabel));
        addInstr(s, new ReceiveRubyExceptionInstr(exc));
        addInstr(s, new InheritanceSearchConstInstr(excType, new ObjectClass(), "Exception", false));
        outputExceptionCheck(s, excType, exc, caughtLabel);

        // Fall-through when the exc !== Exception; rethrow 'exc'
        addInstr(s, new ThrowExceptionInstr(exc));

        // exc === Exception; Run the rescue block
        addInstr(s, new LabelInstr(caughtLabel));
        Object v2 = rescueBlock.run(); // YIELD: Run the protected code block
        if (v2 != null) addInstr(s, new CopyInstr(rv, manager.getNil()));

        // End
        addInstr(s, new LabelInstr(rEndLabel));

        return rv;
    }

    public Operand buildGetDefinition(Node node, final IRScope scope) {
        node = skipOverNewlines(scope, node);

        // FIXME: Do we still have MASGN and MASGN19?
        switch (node.getNodeType()) {
        case CLASSVARASGNNODE: case CLASSVARDECLNODE: case CONSTDECLNODE:
        case DASGNNODE: case GLOBALASGNNODE: case LOCALASGNNODE: case MULTIPLEASGNNODE:
        case MULTIPLEASGN19NODE: case OPASGNNODE: case OPASGNANDNODE: case OPASGNORNODE:
        case OPELEMENTASGNNODE: case INSTASGNNODE:
            return new ConstantStringLiteral("assignment");
        case ORNODE: case ANDNODE:
            return new ConstantStringLiteral("expression");
        case FALSENODE:
            return new ConstantStringLiteral("false");
        case LOCALVARNODE: case DVARNODE:
            return new ConstantStringLiteral("local-variable");
        case MATCH2NODE: case MATCH3NODE:
            return new ConstantStringLiteral("method");
        case NILNODE:
            return new ConstantStringLiteral("nil");
        case SELFNODE:
            return new ConstantStringLiteral("self");
        case TRUENODE:
            return new ConstantStringLiteral("true");
        case DREGEXPNODE: case DSTRNODE: {
            final Node dNode = node;

            // protected code
            CodeBlock protectedCode = new CodeBlock() {
                public Operand run() {
                    build(dNode, scope);
                    // always an expression as long as we get through here without an exception!
                    return new ConstantStringLiteral("expression");
                }
            };
            // rescue block
            CodeBlock rescueBlock = new CodeBlock() {
                public Operand run() { return manager.getNil(); } // Nothing to do if we got an exception
            };

            // Try verifying definition, and if we get an JumpException exception, process it with the rescue block above
            Operand v = protectCodeWithRescue(scope, protectedCode, rescueBlock);
            Label doneLabel = scope.getNewLabel();
            Variable tmpVar = getValueInTemporaryVariable(scope, v);
            addInstr(scope, BNEInstr.create(tmpVar, manager.getNil(), doneLabel));
            addInstr(scope, new CopyInstr(tmpVar, new ConstantStringLiteral("expression")));
            addInstr(scope, new LabelInstr(doneLabel));

            return tmpVar;
        }
        case ARRAYNODE: { // If all elts of array are defined the array is as well
            ArrayNode array = (ArrayNode) node;
            Label undefLabel = scope.getNewLabel();
            Label doneLabel = scope.getNewLabel();

            Variable tmpVar = scope.createTemporaryVariable();
            for (Node elt: array.childNodes()) {
                Operand result = buildGetDefinition(elt, scope);

                addInstr(scope, BEQInstr.create(result, manager.getNil(), undefLabel));
            }

            addInstr(scope, new CopyInstr(tmpVar, new ConstantStringLiteral("expression")));
            addInstr(scope, new JumpInstr(doneLabel));
            addInstr(scope, new LabelInstr(undefLabel));
            addInstr(scope, new CopyInstr(tmpVar, manager.getNil()));
            addInstr(scope, new LabelInstr(doneLabel));

            return tmpVar;
        }
        case BACKREFNODE:
            return addResultInstr(scope, new RuntimeHelperCall(scope.createTemporaryVariable(), IS_DEFINED_BACKREF,
                    Operand.EMPTY_ARRAY));
        case GLOBALVARNODE:
            return addResultInstr(scope, new RuntimeHelperCall(scope.createTemporaryVariable(), IS_DEFINED_GLOBAL,
                    new Operand[] { new StringLiteral(((GlobalVarNode) node).getName()) }));
        case NTHREFNODE: {
            return addResultInstr(scope, new RuntimeHelperCall(scope.createTemporaryVariable(), IS_DEFINED_NTH_REF,
                    new Operand[] { new Fixnum(((NthRefNode) node).getMatchNumber()) }));
        }
        case INSTVARNODE:
            return addResultInstr(scope, new RuntimeHelperCall(scope.createTemporaryVariable(), IS_DEFINED_INSTANCE_VAR,
                    new Operand[] { scope.getSelf(), new StringLiteral(((InstVarNode) node).getName()) }));
        case CLASSVARNODE:
            return addResultInstr(scope, new RuntimeHelperCall(scope.createTemporaryVariable(), IS_DEFINED_CLASS_VAR,
                    new Operand[]{classVarDefinitionContainer(scope), new StringLiteral(((ClassVarNode) node).getName())}));
        case SUPERNODE: {
            Label undefLabel = scope.getNewLabel();
            Variable tmpVar  = addResultInstr(scope, new RuntimeHelperCall(scope.createTemporaryVariable(), IS_DEFINED_SUPER,
                    new Operand[] { scope.getSelf() }));
            addInstr(scope, BEQInstr.create(tmpVar, manager.getNil(), undefLabel));
            Operand superDefnVal = buildGetArgumentDefinition(((SuperNode) node).getArgsNode(), scope, "super");
            return buildDefnCheckIfThenPaths(scope, undefLabel, superDefnVal);
        }
        case VCALLNODE:
            return addResultInstr(scope, new RuntimeHelperCall(scope.createTemporaryVariable(), IS_DEFINED_METHOD,
                    new Operand[] { scope.getSelf(), new StringLiteral(((VCallNode) node).getName()), Boolean.FALSE}));
        case YIELDNODE:
            return buildDefinitionCheck(scope, new BlockGivenInstr(scope.createTemporaryVariable(), scope.getYieldClosureVariable()), "yield");
        case ZSUPERNODE:
            return addResultInstr(scope, new RuntimeHelperCall(scope.createTemporaryVariable(), IS_DEFINED_SUPER,
                    new Operand[] { scope.getSelf() } ));
        case CONSTNODE: {
            Label defLabel = scope.getNewLabel();
            Label doneLabel = scope.getNewLabel();
            Variable tmpVar  = scope.createTemporaryVariable();
            String constName = ((ConstNode) node).getName();
            addInstr(scope, new LexicalSearchConstInstr(tmpVar, startingSearchScope(scope), constName));
            addInstr(scope, BNEInstr.create(tmpVar, UndefinedValue.UNDEFINED, defLabel));
            addInstr(scope, new InheritanceSearchConstInstr(tmpVar, findContainerModule(scope), constName, false)); // SSS FIXME: should this be the current-module var or something else?
            addInstr(scope, BNEInstr.create(tmpVar, UndefinedValue.UNDEFINED, defLabel));
            addInstr(scope, new CopyInstr(tmpVar, manager.getNil()));
            addInstr(scope, new JumpInstr(doneLabel));
            addInstr(scope, new LabelInstr(defLabel));
            addInstr(scope, new CopyInstr(tmpVar, new ConstantStringLiteral("constant")));
            addInstr(scope, new LabelInstr(doneLabel));
            return tmpVar;
        }
        case COLON3NODE: case COLON2NODE: {
            // SSS FIXME: Is there a reason to do this all with low-level IR?
            // Can't this all be folded into a Java method that would be part
            // of the runtime library, which then can be used by buildDefinitionCheck method above?
            // This runtime library would be used both by the interpreter & the compiled code!

            final Colon3Node colon = (Colon3Node) node;
            final String name = colon.getName();
            final Variable errInfo = scope.createTemporaryVariable();

            // store previous exception for restoration if we rescue something
            addInstr(scope, new GetErrorInfoInstr(errInfo));

            CodeBlock protectedCode = new CodeBlock() {
                public Operand run() {
                    Operand v = colon instanceof Colon2Node ?
                            build(((Colon2Node)colon).getLeftNode(), scope) : new ObjectClass();

                    Variable tmpVar = scope.createTemporaryVariable();
                    addInstr(scope, new RuntimeHelperCall(tmpVar, IS_DEFINED_CONSTANT_OR_METHOD, new Operand[] {v, new ConstantStringLiteral(name)}));
                    return tmpVar;
                }
            };

            // rescue block
            CodeBlock rescueBlock = new CodeBlock() {
                 public Operand run() {
                 // Nothing to do -- ignore the exception, and restore stashed error info!
                 addInstr(scope, new RestoreErrorInfoInstr(errInfo));
                 return manager.getNil();
                 }
            };

                // Try verifying definition, and if we get an JumpException exception, process it with the rescue block above
            return protectCodeWithRescue(scope, protectedCode, rescueBlock);
        }
        case FCALLNODE: {
            /* ------------------------------------------------------------------
             * Generate IR for:
             *    r = self/receiver
             *    mc = r.metaclass
             *    return mc.methodBound(meth) ? buildGetArgumentDefn(..) : false
             * ----------------------------------------------------------------- */
            Label undefLabel = scope.getNewLabel();
            Variable tmpVar = addResultInstr(scope, new RuntimeHelperCall(scope.createTemporaryVariable(), IS_DEFINED_METHOD,
                    new Operand[]{scope.getSelf(), new StringLiteral(((FCallNode) node).getName()), Boolean.FALSE}));
            addInstr(scope, BEQInstr.create(tmpVar, manager.getNil(), undefLabel));
            Operand argsCheckDefn = buildGetArgumentDefinition(((FCallNode) node).getArgsNode(), scope, "method");
            return buildDefnCheckIfThenPaths(scope, undefLabel, argsCheckDefn);
        }
        case CALLNODE: {
            final Label undefLabel = scope.getNewLabel();
            final CallNode callNode = (CallNode) node;
            Operand  receiverDefn = buildGetDefinition(callNode.getReceiverNode(), scope);
            addInstr(scope, BEQInstr.create(receiverDefn, manager.getNil(), undefLabel));

            // protected main block
            CodeBlock protectedCode = new CodeBlock() {
                public Operand run() {
                    Variable tmpVar = scope.createTemporaryVariable();
                    addInstr(scope, new RuntimeHelperCall(tmpVar, IS_DEFINED_CALL,
                            new Operand[]{build(callNode.getReceiverNode(), scope), new StringLiteral(callNode.getName())}));
                    return buildDefnCheckIfThenPaths(scope, undefLabel, tmpVar);
                }
            };

            // rescue block
            CodeBlock rescueBlock = new CodeBlock() {
                public Operand run() { return manager.getNil(); } // Nothing to do if we got an exception
            };

            // Try verifying definition, and if we get an exception, throw it out, and return nil
            return protectCodeWithRescue(scope, protectedCode, rescueBlock);
        }
        case ATTRASSIGNNODE: {
            final Label  undefLabel = scope.getNewLabel();
            final AttrAssignNode attrAssign = (AttrAssignNode) node;
            Operand receiverDefn = buildGetDefinition(attrAssign.getReceiverNode(), scope);
            addInstr(scope, BEQInstr.create(receiverDefn, manager.getNil(), undefLabel));

            // protected main block
            CodeBlock protectedCode = new CodeBlock() {
                public Operand run() {
                    /* --------------------------------------------------------------------------
                     * This basically combines checks from CALLNODE and FCALLNODE
                     *
                     * Generate IR for this sequence
                     *
                     *    1. r  = receiver
                     *    2. mc = r.metaClass
                     *    3. v  = mc.getVisibility(methodName)
                     *    4. f  = !v || v.isPrivate? || (v.isProtected? && receiver/self?.kindof(mc.getRealClass))
                     *    5. return !f && mc.methodBound(attrmethod) ? buildGetArgumentDefn(..) : false
                     *
                     * Hide the complexity of instrs 2-4 into a verifyMethodIsPublicAccessible call
                     * which can executely entirely in Java-land.  No reason to expose the guts in IR.
                     * ------------------------------------------------------------------------------ */
                    Variable tmpVar     = scope.createTemporaryVariable();
                    Operand  receiver   = build(attrAssign.getReceiverNode(), scope);
                    addInstr(scope, new RuntimeHelperCall(tmpVar, IS_DEFINED_METHOD,
                            new Operand[] { receiver, new StringLiteral(attrAssign.getName()), Boolean.TRUE }));
                    addInstr(scope, BEQInstr.create(tmpVar, manager.getNil(), undefLabel));
                    Operand argsCheckDefn = buildGetArgumentDefinition(attrAssign.getArgsNode(), scope, "assignment");
                    return buildDefnCheckIfThenPaths(scope, undefLabel, argsCheckDefn);
                }
            };

            // rescue block
            CodeBlock rescueBlock = new CodeBlock() {
                public Operand run() { return manager.getNil(); } // Nothing to do if we got an exception
            };

            // Try verifying definition, and if we get an JumpException exception, process it with the rescue block above
            return protectCodeWithRescue(scope, protectedCode, rescueBlock);
        }
        default:
            return new ConstantStringLiteral("expression");
        }
    }

    protected Variable buildDefnCheckIfThenPaths(IRScope s, Label undefLabel, Operand defVal) {
        Label defLabel = s.getNewLabel();
        Variable tmpVar = getValueInTemporaryVariable(s, defVal);
        addInstr(s, new JumpInstr(defLabel));
        addInstr(s, new LabelInstr(undefLabel));
        addInstr(s, new CopyInstr(tmpVar, manager.getNil()));
        addInstr(s, new LabelInstr(defLabel));
        return tmpVar;
    }

    protected Variable buildDefinitionCheck(IRScope s, ResultInstr definedInstr, String definedReturnValue) {
        Label undefLabel = s.getNewLabel();
        addInstr(s, (Instr) definedInstr);
        addInstr(s, BEQInstr.create(definedInstr.getResult(), manager.getFalse(), undefLabel));
        return buildDefnCheckIfThenPaths(s, undefLabel, new ConstantStringLiteral(definedReturnValue));
    }

    public Operand buildGetArgumentDefinition(final Node node, IRScope s, String type) {
        if (node == null) return new StringLiteral(type);

        Operand rv = new ConstantStringLiteral(type);
        boolean failPathReqd = false;
        Label failLabel = s.getNewLabel();
        if (node instanceof ArrayNode) {
            for (int i = 0; i < ((ArrayNode) node).size(); i++) {
                Node iterNode = ((ArrayNode) node).get(i);
                Operand def = buildGetDefinition(iterNode, s);
                if (def == manager.getNil()) { // Optimization!
                    rv = manager.getNil();
                    break;
                } else if (!def.hasKnownValue()) { // Optimization!
                    failPathReqd = true;
                    addInstr(s, BEQInstr.create(def, manager.getNil(), failLabel));
                }
            }
        } else {
            Operand def = buildGetDefinition(node, s);
            if (def == manager.getNil()) { // Optimization!
                rv = manager.getNil();
            } else if (!def.hasKnownValue()) { // Optimization!
                failPathReqd = true;
                addInstr(s, BEQInstr.create(def, manager.getNil(), failLabel));
            }
        }

        // Optimization!
        return failPathReqd ? buildDefnCheckIfThenPaths(s, failLabel, rv) : rv;

    }

    public Operand buildDAsgn(final DAsgnNode dasgnNode, IRScope s) {
        // SSS: Looks like we receive the arg in buildBlockArgsAssignment via the IterNode
        // We won't get here for argument receives!  So, buildDasgn is called for
        // assignments to block variables within a block.  As far as the IR is concerned,
        // this is just a simple copy
        int depth = dasgnNode.getDepth();
        Variable arg = s.getLocalVariable(dasgnNode.getName(), depth);
        Operand  value = build(dasgnNode.getValueNode(), s);
        addInstr(s, new CopyInstr(arg, value));
        return value;

        // IMPORTANT: The return value of this method is value, not arg!
        //
        // Consider this Ruby code: foo((a = 1), (a = 2))
        //
        // If we return 'value' this will get translated to:
        //    a = 1
        //    a = 2
        //    call("foo", [1,2]) <---- CORRECT
        //
        // If we return 'arg' this will get translated to:
        //    a = 1
        //    a = 2
        //    call("foo", [a,a]) <---- BUGGY
        //
        // This technique only works if 'value' is an immutable value (ex: fixnum) or a variable
        // So, for Ruby code like this:
        //     def foo(x); x << 5; end;
        //     foo(a=[1,2]);
        //     p a
        // we are guaranteed that the value passed into foo and 'a' point to the same object
        // because of the use of copyAndReturnValue method for literal objects.
    }

    // Called by defineMethod but called on a new builder so things like ensure block info recording
    // do not get confused.
    protected IRMethod defineMethodInner(MethodDefNode defNode, IRMethod method, IRScope parent) {
        if (RubyInstanceConfig.FULL_TRACE_ENABLED) {
            addInstr(method, new TraceInstr(RubyEvent.CALL, method.getName(), method.getFileName(), method.getLineNumber()));
        }

        addInstr(method, new ReceiveSelfInstr(method.getSelf()));

        // Prepare all implicit state (self, frame block, etc)
        prepareImplicitState(method);

        // These instructions need to be toward the top of the method because they may both be needed for
        // processing optional arguments as in def foo(a = Object).
        // Set %current_scope = <current-scope>
        // Set %current_module = isInstanceMethod ? %self.metaclass : %self
        int nearestScopeDepth = parent.getNearestModuleReferencingScopeDepth();
        addInstr(method, new CopyInstr(method.getCurrentScopeVariable(), CurrentScope.ScopeFor(nearestScopeDepth == -1 ? 1 : nearestScopeDepth)));
        addInstr(method, new CopyInstr(method.getCurrentModuleVariable(), ScopeModule.ModuleFor(nearestScopeDepth == -1 ? 1 : nearestScopeDepth)));

        // Build IR for arguments (including the block arg)
        receiveMethodArgs(defNode.getArgsNode(), method);

        // Thread poll on entry to method
        addInstr(method, new ThreadPollInstr());

        // Build IR for body
        Operand rv = newIRBuilder(manager).build(defNode.getBodyNode(), method);

        if (RubyInstanceConfig.FULL_TRACE_ENABLED) {
            addInstr(method, new TraceInstr(RubyEvent.RETURN, method.getName(), method.getFileName(), -1));
        }

        if (rv != null) addInstr(method, new ReturnInstr(rv));

        // If the method can receive non-local returns
        if (method.canReceiveNonlocalReturns()) handleNonlocalReturnInMethod(method);

        return method;
    }

    private IRMethod defineNewMethod(MethodDefNode defNode, IRScope parent, boolean isInstanceMethod) {
        return new IRMethod(manager, parent, defNode, defNode.getName(), isInstanceMethod, defNode.getPosition().getLine(), defNode.getScope());

        //return newIRBuilder(manager).defineMethodInner(defNode, method, parent);
    }

    public Operand buildDefn(MethodDefNode node, IRScope s) { // Instance method
        IRMethod method = defineNewMethod(node, s, true);
        addInstr(s, new DefineInstanceMethodInstr(method));
        // FIXME: Method name should save encoding
        return new Symbol(method.getName(), ASCIIEncoding.INSTANCE);
    }

    public Operand buildDefs(DefsNode node, IRScope s) { // Class method
        Operand container =  build(node.getReceiverNode(), s);
        IRMethod method = defineNewMethod(node, s, false);
        addInstr(s, new DefineClassMethodInstr(container, method));
        // FIXME: Method name should save encoding
        return new Symbol(method.getName(), ASCIIEncoding.INSTANCE);
    }

    protected LocalVariable getArgVariable(IRScope s, String name, int depth) {
        // For non-loops, this name will override any name that exists in outer scopes
        return s instanceof IRFor ? s.getLocalVariable(name, depth) : s.getNewLocalVariable(name, 0);
    }

    private void addArgReceiveInstr(IRScope s, Variable v, int argIndex, boolean post, int numPreReqd, int numPostRead) {
        if (post) addInstr(s, new ReceivePostReqdArgInstr(v, argIndex, numPreReqd, numPostRead));
        else addInstr(s, new ReceivePreReqdArgInstr(v, argIndex));
    }

    public void receiveRequiredArg(Node node, IRScope s, int argIndex, boolean post, int numPreReqd, int numPostRead) {
        switch (node.getNodeType()) {
            case ARGUMENTNODE: {
                ArgumentNode a = (ArgumentNode)node;
                String argName = a.getName();
                if (s instanceof IRMethod) ((IRMethod)s).addArgDesc(IRMethodArgs.ArgType.req, argName);
                // Ignore duplicate "_" args in blocks
                // (duplicate _ args are named "_$0")
                if (!argName.equals("_$0")) {
                    addArgReceiveInstr(s, s.getNewLocalVariable(argName, 0), argIndex, post, numPreReqd, numPostRead);
                }
                break;
            }
            case MULTIPLEASGN19NODE: {
                MultipleAsgn19Node childNode = (MultipleAsgn19Node) node;
                Variable v = s.createTemporaryVariable();
                addArgReceiveInstr(s, v, argIndex, post, numPreReqd, numPostRead);
                if (s instanceof IRMethod) ((IRMethod)s).addArgDesc(IRMethodArgs.ArgType.req, "");
                Variable tmp = s.createTemporaryVariable();
                addInstr(s, new ToAryInstr(tmp, v));
                buildMultipleAsgn19Assignment(childNode, s, tmp, null);
                break;
            }
            default: throw new NotCompilableException("Can't build assignment node: " + node);
        }
    }

    protected void receiveNonBlockArgs(final ArgsNode argsNode, IRScope s) {
        final int numPreReqd = argsNode.getPreCount();
        final int numPostReqd = argsNode.getPostCount();
        final int required = argsNode.getRequiredArgsCount(); // numPreReqd + numPostReqd
        int opt = argsNode.getOptionalArgsCount();
        int rest = argsNode.getRestArg();

        s.getStaticScope().setArities(required, opt, rest);
        KeywordRestArgNode keyRest = argsNode.getKeyRest();

        // For closures, we don't need the check arity call
        if (s instanceof IRMethod) {
            // Expensive to do this explicitly?  But, two advantages:
            // (a) on inlining, we'll be able to get rid of these checks in almost every case.
            // (b) compiler to bytecode will anyway generate this and this is explicit.
            // For now, we are going explicit instruction route.
            // But later, perhaps can make this implicit in the method setup preamble?

            addInstr(s, new CheckArityInstr(required, opt, rest, argsNode.hasKwargs(),
                    keyRest == null ? -1 : keyRest.getIndex()));
        } else if (s instanceof IRClosure && argsNode.hasKwargs()) {
            // FIXME: This is added to check for kwargs correctness but bypass regular correctness.
            // Any other arity checking currently happens within Java code somewhere (RubyProc.call?)
            addInstr(s, new CheckArityInstr(required, opt, rest, argsNode.hasKwargs(),
                    keyRest == null ? -1 : keyRest.getIndex()));
        }

        // Other args begin at index 0
        int argIndex = 0;

        // Pre(-opt and rest) required args
        ListNode preArgs = argsNode.getPre();
        for (int i = 0; i < numPreReqd; i++, argIndex++) {
            receiveRequiredArg(preArgs.get(i), s, argIndex, false, -1, -1);
        }

        // Fixup opt/rest
        opt = opt > 0 ? opt : 0;
        rest = rest > -1 ? 1 : 0;

        // Now for opt args
        if (opt > 0) {
            ListNode optArgs = argsNode.getOptArgs();
            for (int j = 0; j < opt; j++, argIndex++) {
                // Jump to 'l' if this arg is not null.  If null, fall through and build the default value!
                Label l = s.getNewLabel();
                OptArgNode n = (OptArgNode)optArgs.get(j);
                String argName = n.getName();
                Variable av = s.getNewLocalVariable(argName, 0);
                if (s instanceof IRMethod) ((IRMethod)s).addArgDesc(IRMethodArgs.ArgType.opt, argName);
                // You need at least required+j+1 incoming args for this opt arg to get an arg at all
                addInstr(s, new ReceiveOptArgInstr(av, required, numPreReqd, j));
                addInstr(s, BNEInstr.create(av, UndefinedValue.UNDEFINED, l)); // if 'av' is not undefined, go to default
                build(n.getValue(), s);
                addInstr(s, new LabelInstr(l));
            }
        }

        // Rest arg
        if (rest > 0) {
            // Consider: def foo(*); .. ; end
            // For this code, there is no argument name available from the ruby code.
            // So, we generate an implicit arg name
            String argName = argsNode.getRestArgNode().getName();
            if (s instanceof IRMethod) ((IRMethod)s).addArgDesc(IRMethodArgs.ArgType.rest, argName == null ? "" : argName);
            argName = (argName == null || argName.equals("")) ? "*" : argName;

            // You need at least required+opt+1 incoming args for the rest arg to get any args at all
            // If it is going to get something, then it should ignore required+opt args from the beginning
            // because they have been accounted for already.
            addInstr(s, new ReceiveRestArgInstr(s.getNewLocalVariable(argName, 0), required + opt, argIndex));
        }

        // Post(-opt and rest) required args
        ListNode postArgs = argsNode.getPost();
        for (int i = 0; i < numPostReqd; i++) {
            receiveRequiredArg(postArgs.get(i), s, i, true, numPreReqd, numPostReqd);
        }
    }

    protected void receiveBlockArg(final ArgsNode argsNode, IRScope s) {
        // reify to Proc if we have a block arg
        BlockArgNode blockArg = argsNode.getBlock();
        if (blockArg != null) {
            String blockArgName = blockArg.getName();
            Variable blockVar = s.getNewLocalVariable(blockArgName, 0);
            if (s instanceof IRMethod) ((IRMethod) s).addArgDesc(IRMethodArgs.ArgType.block, blockArgName);
            addInstr(s, new ReifyClosureInstr(s.getImplicitClosureVariable(), blockVar));
        }
    }

    private void prepareImplicitState(IRScope s) {
        // Receive self
        addInstr(s, new ReceiveSelfInstr(s.getSelf()));

        // used for constructing block arg; if none, this will go away
        addInstr(s, new LoadImplicitClosureInstr(s.getImplicitClosureVariable()));

        // used for yields; metaclass body (sclass) inherits yield var from surrounding, and accesses it as implicit
        if (s instanceof IRMethod || s instanceof IRMetaClassBody) {
            addInstr(s, new LoadImplicitClosureInstr(s.getYieldClosureVariable()));
        } else {
            addInstr(s, new LoadFrameClosureInstr(s.getYieldClosureVariable()));
        }
    }

    public void receiveArgs(final ArgsNode argsNode, IRScope s) {
        // 1.9 pre, opt, rest, post args
        receiveNonBlockArgs(argsNode, s);

        // 2.0 keyword args
        ListNode keywords = argsNode.getKeywords();
        int required = argsNode.getRequiredArgsCount();
        if (keywords != null) {
            for (Node knode : keywords.childNodes()) {
                KeywordArgNode kwarg = (KeywordArgNode)knode;
                AssignableNode kasgn = kwarg.getAssignable();
                String argName = ((INameNode) kasgn).getName();
                Variable av = s.getNewLocalVariable(argName, 0);
                Label l = s.getNewLabel();
                if (s instanceof IRMethod) ((IRMethod)s).addArgDesc(IRMethodArgs.ArgType.key, argName);
                addInstr(s, new ReceiveKeywordArgInstr(av, argName, required));
                addInstr(s, BNEInstr.create(av, UndefinedValue.UNDEFINED, l)); // if 'av' is not undefined, we are done

                // Required kwargs have no value and check_arity will throw if they are not provided.
                if (kasgn.getValueNode().getNodeType() != NodeType.REQUIRED_KEYWORD_ARGUMENT_VALUE) {
                    build(kasgn, s);
                } else {
                    addInstr(s, new RaiseRequiredKeywordArgumentError(argName));
                }
                addInstr(s, new LabelInstr(l));
            }
        }

        // 2.0 keyword rest arg
        KeywordRestArgNode keyRest = argsNode.getKeyRest();
        if (keyRest != null) {
            String argName = keyRest.getName();
            Variable av = s.getNewLocalVariable(argName, 0);
            if (s instanceof IRMethod) ((IRMethod)s).addArgDesc(IRMethodArgs.ArgType.keyrest, argName);
            addInstr(s, new ReceiveKeywordRestArgInstr(av, required));
        }

        // Block arg
        receiveBlockArg(argsNode, s);
    }

    // This method is called to build arguments
    public void buildArgsMasgn(Node node, IRScope s, Operand argsArray, boolean isMasgnRoot, int preArgsCount, int postArgsCount, int index, boolean isSplat) {
        Variable v;
        switch (node.getNodeType()) {
            case DASGNNODE: {
                DAsgnNode dynamicAsgn = (DAsgnNode) node;
                v = getArgVariable(s, dynamicAsgn.getName(), dynamicAsgn.getDepth());
                if (isSplat) addInstr(s, new RestArgMultipleAsgnInstr(v, argsArray, preArgsCount, postArgsCount, index));
                else addInstr(s, new ReqdArgMultipleAsgnInstr(v, argsArray, preArgsCount, postArgsCount, index));
                break;
            }
            case LOCALASGNNODE: {
                LocalAsgnNode localVariable = (LocalAsgnNode) node;
                v = getArgVariable(s, localVariable.getName(), localVariable.getDepth());
                if (isSplat) addInstr(s, new RestArgMultipleAsgnInstr(v, argsArray, preArgsCount, postArgsCount, index));
                else addInstr(s, new ReqdArgMultipleAsgnInstr(v, argsArray, preArgsCount, postArgsCount, index));
                break;
            }
            case MULTIPLEASGN19NODE: {
                MultipleAsgn19Node childNode = (MultipleAsgn19Node) node;
                if (!isMasgnRoot) {
                    v = s.createTemporaryVariable();
                    if (isSplat) addInstr(s, new RestArgMultipleAsgnInstr(v, argsArray, preArgsCount, postArgsCount, index));
                    else addInstr(s, new ReqdArgMultipleAsgnInstr(v, argsArray, preArgsCount, postArgsCount, index));
                    Variable tmp = s.createTemporaryVariable();
                    addInstr(s, new ToAryInstr(tmp, v));
                    argsArray = tmp;
                }
                // Build
                buildMultipleAsgn19Assignment(childNode, s, argsArray, null);
                break;
            }
            default:
                throw new NotCompilableException("Shouldn't get here: " + node);
        }
    }

    // SSS: This method is called both for regular multiple assignment as well as argument passing
    //
    // Ex: a,b,*c=v  is a regular assignment and in this case, the "values" operand will be non-null
    // Ex: { |a,b,*c| ..} is the argument passing case
    public void buildMultipleAsgn19Assignment(final MultipleAsgn19Node multipleAsgnNode, IRScope s, Operand argsArray, Operand values) {
        final ListNode masgnPre = multipleAsgnNode.getPre();

        // Build assignments for specific named arguments
        int i = 0;
        if (masgnPre != null) {
            for (Node an: masgnPre.childNodes()) {
                if (values == null) {
                    buildArgsMasgn(an, s, argsArray, false, -1, -1, i, false);
                } else {
                    Variable rhsVal = s.createTemporaryVariable();
                    addInstr(s, new ReqdArgMultipleAsgnInstr(rhsVal, values, i));
                    buildAssignment(an, s, rhsVal);
                }
                i++;
            }
        }

        // Build an assignment for a splat, if any, with the rest of the operands!
        Node restNode = multipleAsgnNode.getRest();
        int postArgsCount = multipleAsgnNode.getPostCount();
        if (restNode != null && !(restNode instanceof StarNode)) {
            if (values == null) {
                buildArgsMasgn(restNode, s, argsArray, false, i, postArgsCount, 0, true); // rest of the argument array!
            } else {
                Variable rhsVal = s.createTemporaryVariable();
                addInstr(s, new RestArgMultipleAsgnInstr(rhsVal, values, i, postArgsCount, 0));
                buildAssignment(restNode, s, rhsVal); // rest of the argument array!
            }
        }

        // Build assignments for rest of the operands
        final ListNode masgnPost = multipleAsgnNode.getPost();
        if (masgnPost != null) {
            int j = 0;
            for (Node an: masgnPost.childNodes()) {
                if (values == null) {
                    buildArgsMasgn(an, s, argsArray, false, i, postArgsCount, j, false);
                } else {
                    Variable rhsVal = s.createTemporaryVariable();
                    addInstr(s, new ReqdArgMultipleAsgnInstr(rhsVal, values, i, postArgsCount, j));  // Fetch from the end
                    buildAssignment(an, s, rhsVal);
                }
                j++;
            }
        }
    }

    private void handleBreakAndReturnsInLambdas(IRClosure s) {
        Label rEndLabel   = s.getNewLabel();
        Label rescueLabel = Label.getGlobalEnsureBlockLabel();

        // protect the entire body as it exists now with the global ensure block
        addInstrAtBeginning(s, new ExceptionRegionStartMarkerInstr(rescueLabel));
        addInstr(s, new ExceptionRegionEndMarkerInstr());

        // Receive exceptions (could be anything, but the handler only processes IRBreakJumps)
        addInstr(s, new LabelInstr(rescueLabel));
        Variable exc = s.createTemporaryVariable();
        addInstr(s, new ReceiveJRubyExceptionInstr(exc));

        // Handle break using runtime helper
        // --> IRRuntimeHelpers.handleBreakAndReturnsInLambdas(context, scope, bj, blockType)
        Variable ret = s.createTemporaryVariable();
        addInstr(s, new RuntimeHelperCall(ret, RuntimeHelperCall.Methods.HANDLE_BREAK_AND_RETURNS_IN_LAMBDA, new Operand[]{exc} ));
        addInstr(s, new ReturnInstr(ret));

        // End
        addInstr(s, new LabelInstr(rEndLabel));
    }

    public void receiveMethodArgs(final ArgsNode argsNode, IRMethod s) {
        receiveArgs(argsNode, s);
    }

    public void receiveBlockArgs(final IterNode node, IRScope s) {
        Node args = node.getVarNode();
        if (args instanceof ArgsNode) { // regular blocks
            ((IRClosure)s).setParameterList(Helpers.encodeParameterList((ArgsNode) args).split(";"));
            receiveArgs((ArgsNode)args, s);
        } else  {
            // for loops -- reuse code in IRBuilder:buildBlockArgsAssignment
            buildBlockArgsAssignment(args, s, null, 0, false);
        }
    }

    public Operand buildDot(final DotNode dotNode, IRScope s) {
        Variable res = s.createTemporaryVariable();
        addInstr(s, new BuildRangeInstr(res, build(dotNode.getBeginNode(), s), build(dotNode.getEndNode(), s), dotNode.isExclusive()));
        return res;
    }

    private Operand dynamicPiece(Node pieceNode, IRScope s) {
        Operand piece = build(pieceNode, s);

        return piece == null ? manager.getNil() : piece;
    }

    public Operand buildDRegexp(DRegexpNode dregexpNode, IRScope s) {
        List<Operand> strPieces = new ArrayList<>();
        for (Node n : dregexpNode.childNodes()) {
            strPieces.add(dynamicPiece(n, s));
        }

        Variable res = s.createTemporaryVariable();
        addInstr(s, new BuildDynRegExpInstr(res, strPieces, dregexpNode.getOptions()));
        return res;
    }

    public Operand buildDStr(DStrNode dstrNode, IRScope s) {
        List<Operand> strPieces = new ArrayList<>();
        for (Node n : dstrNode.childNodes()) {
            strPieces.add(dynamicPiece(n, s));
        }

        Variable res = s.createTemporaryVariable();
        addInstr(s, new BuildCompoundStringInstr(res, strPieces, dstrNode.getEncoding()));
        return copyAndReturnValue(s, res);
    }

    public Operand buildDSymbol(DSymbolNode node, IRScope s) {
        List<Operand> strPieces = new ArrayList<>();
        for (Node n : node.childNodes()) {
            strPieces.add(dynamicPiece(n, s));
        }

        Variable res = s.createTemporaryVariable();
        addInstr(s, new BuildCompoundStringInstr(res, strPieces, node.getEncoding()));
        return copyAndReturnValue(s, new DynamicSymbol(res));
    }

    public Operand buildDVar(DVarNode node, IRScope s) {
        return s.getLocalVariable(node.getName(), node.getDepth());
    }

    public Operand buildDXStr(final DXStrNode dstrNode, IRScope s) {
        List<Operand> strPieces = new ArrayList<>();
        for (Node nextNode : dstrNode.childNodes()) {
            strPieces.add(dynamicPiece(nextNode, s));
        }

        Variable res = s.createTemporaryVariable();
        addInstr(s, new BacktickInstr(res, strPieces));
        return res;
    }

    /* ****************************************************************
     * Consider the ensure-protected ruby code below:

           begin
             .. protected body ..
           ensure
             .. eb code
           end

       This ruby code is effectively rewritten into the following ruby code

          begin
            .. protected body ..
            .. copy of ensure body code ..
          rescue <any-exception-or-error> => e
            .. ensure body code ..
            raise e
          end

      which in IR looks like this:

          L1:
            Exception region start marker_1 (protected by L10)
            ... IR for protected body ...
            Exception region end marker_1
          L2:
            Exception region start marker_2 (protected by whichever block handles exceptions for ensure body)
            .. copy of IR for ensure block ..
            Exception region end marker_2
            jump L3
          L10:          <----- dummy rescue block
            e = recv_exception
            .. IR for ensure block ..
            throw e
          L3:

     * ****************************************************************/
    public Operand buildEnsureNode(EnsureNode ensureNode, IRScope s) {
        Node bodyNode = ensureNode.getBodyNode();

        // ------------ Build the body of the ensure block ------------
        //
        // The ensure code is built first so that when the protected body is being built,
        // the ensure code can be cloned at break/next/return sites in the protected body.

        // Push a new ensure block node onto the stack of ensure bodies being built
        // The body's instructions are stashed and emitted later.
        EnsureBlockInfo ebi = new EnsureBlockInfo(s,
            (bodyNode instanceof RescueNode) ? (RescueNode)bodyNode : null,
            getCurrentLoop(),
            activeRescuers.peek());

        ensureBodyBuildStack.push(ebi);
        Operand ensureRetVal = (ensureNode.getEnsureNode() == null) ? manager.getNil() : build(ensureNode.getEnsureNode(), s);
        ensureBodyBuildStack.pop();

        // ------------ Build the protected region ------------
        activeEnsureBlockStack.push(ebi);

        // Start of protected region
        addInstr(s, new LabelInstr(ebi.regionStart));
        addInstr(s, new ExceptionRegionStartMarkerInstr(ebi.dummyRescueBlockLabel));
        activeRescuers.push(ebi.dummyRescueBlockLabel);

        // Generate IR for code being protected
        Operand rv = bodyNode instanceof RescueNode ? buildRescueInternal((RescueNode) bodyNode, s, ebi) : build(bodyNode, s);

        // end of protected region
        addInstr(s, new ExceptionRegionEndMarkerInstr());
        activeRescuers.pop();

        // Clone the ensure body and jump to the end.
        // Dont bother if the protected body ended in a return.
        if (rv != U_NIL && !(bodyNode instanceof RescueNode)) {
            ebi.cloneIntoHostScope(this, s);
            addInstr(s, new JumpInstr(ebi.end));
        }

        // Pop the current ensure block info node
        activeEnsureBlockStack.pop();

        // ------------ Emit the ensure body alongwith dummy rescue block ------------
        // Now build the dummy rescue block that:
        // * catches all exceptions thrown by the body
        Variable exc = s.createTemporaryVariable();
        addInstr(s, new LabelInstr(ebi.dummyRescueBlockLabel));
        addInstr(s, new ReceiveJRubyExceptionInstr(exc));

        // Now emit the ensure body's stashed instructions
        ebi.emitBody(this, s);

        // 1. Ensure block has no explicit return => the result of the entire ensure expression is the result of the protected body.
        // 2. Ensure block has an explicit return => the result of the protected body is ignored.
        // U_NIL => there was a return from within the ensure block!
        if (ensureRetVal == U_NIL) rv = U_NIL;

        // Return (rethrow exception/end)
        // rethrows the caught exception from the dummy ensure block
        addInstr(s, new ThrowExceptionInstr(exc));

        // End label for the exception region
        addInstr(s, new LabelInstr(ebi.end));

        return rv;
    }

    public Operand buildEvStr(EvStrNode node, IRScope s) {
        return new AsString(build(node.getBody(), s));
    }

    public Operand buildFalse() {
        return manager.getFalse();
    }

    public Operand buildFCall(FCallNode fcallNode, IRScope s) {
        Node          callArgsNode = fcallNode.getArgsNode();
        Operand[] args         = setupCallArgs(callArgsNode, s);
        Operand       block        = setupCallClosure(fcallNode.getIterNode(), s);
        Variable      callResult   = s.createTemporaryVariable();
        CallInstr     callInstr    = CallInstr.create(CallType.FUNCTIONAL, callResult, fcallNode.getName(), s.getSelf(), args, block);
        receiveBreakException(s, block, callInstr);
        return callResult;
    }

    private Operand setupCallClosure(Node node, IRScope s) {
        if (node == null) return null;

        switch (node.getNodeType()) {
            case ITERNODE:
                return build(node, s);
            case BLOCKPASSNODE:
                return build(((BlockPassNode)node).getBodyNode(), s);
            default:
                throw new NotCompilableException("ERROR: Encountered a method with a non-block, non-blockpass iter node at: " + node);
        }
    }

    public Operand buildFixnum(FixnumNode node) {
        return new Fixnum(node.getValue());
    }

    public Operand buildFlip(FlipNode flipNode, IRScope s) {
        /* ----------------------------------------------------------------------
         * Consider a simple 2-state (s1, s2) FSM with the following transitions:
         *
         *     new_state(s1, F) = s1
         *     new_state(s1, T) = s2
         *     new_state(s2, F) = s2
         *     new_state(s2, T) = s1
         *
         * Here is the pseudo-code for evaluating the flip-node.
         * Let 'v' holds the value of the current state.
         *
         *    1. if (v == 's1') f1 = eval_condition(s1-condition); v = new_state(v, f1); ret = f1
         *    2. if (v == 's2') f2 = eval_condition(s2-condition); v = new_state(v, f2); ret = true
         *    3. return ret
         *
         * For exclusive flip conditions, line 2 changes to:
         *    2. if (!f1 && (v == 's2')) f2 = eval_condition(s2-condition); v = new_state(v, f2)
         *
         * In IR code below, we are representing the two states as 1 and 2.  Any
         * two values are good enough (even true and false), but 1 and 2 is simple
         * enough and also makes the IR output readable
         * ---------------------------------------------------------------------- */

        Fixnum s1 = new Fixnum((long)1);
        Fixnum s2 = new Fixnum((long)2);

        // Create a variable to hold the flip state
        IRScope nearestNonClosure = s.getNearestFlipVariableScope();
        Variable flipState = nearestNonClosure.getNewFlipStateVariable();
        nearestNonClosure.initFlipStateVariable(flipState, s1);
        if (s instanceof IRClosure) {
            // Clone the flip variable to be usable at the proper-depth.
            int n = 0;
            IRScope x = s;
            while (!x.isFlipScope()) {
                n++;
                x = x.getLexicalParent();
            }
            if (n > 0) flipState = ((LocalVariable)flipState).cloneForDepth(n);
        }

        // Variables and labels needed for the code
        Variable returnVal = s.createTemporaryVariable();
        Label    s2Label   = s.getNewLabel();
        Label    doneLabel = s.getNewLabel();

        // Init
        addInstr(s, new CopyInstr(returnVal, manager.getFalse()));

        // Are we in state 1?
        addInstr(s, BNEInstr.create(flipState, s1, s2Label));

        // ----- Code for when we are in state 1 -----
        Operand s1Val = build(flipNode.getBeginNode(), s);
        addInstr(s, BNEInstr.create(s1Val, manager.getTrue(), s2Label));

        // s1 condition is true => set returnVal to true & move to state 2
        addInstr(s, new CopyInstr(returnVal, manager.getTrue()));
        addInstr(s, new CopyInstr(flipState, s2));

        // Check for state 2
        addInstr(s, new LabelInstr(s2Label));

        // For exclusive ranges/flips, we dont evaluate s2's condition if s1's condition was satisfied
        if (flipNode.isExclusive()) addInstr(s, BEQInstr.create(returnVal, manager.getTrue(), doneLabel));

        // Are we in state 2?
        addInstr(s, BNEInstr.create(flipState, s2, doneLabel));

        // ----- Code for when we are in state 2 -----
        Operand s2Val = build(flipNode.getEndNode(), s);
        addInstr(s, new CopyInstr(returnVal, manager.getTrue()));
        addInstr(s, BNEInstr.create(s2Val, manager.getTrue(), doneLabel));

        // s2 condition is true => move to state 1
        addInstr(s, new CopyInstr(flipState, s1));

        // Done testing for s1's and s2's conditions.
        // returnVal will have the result of the flip condition
        addInstr(s, new LabelInstr(doneLabel));

        return returnVal;
    }

    public Operand buildFloat(FloatNode node) {
        // SSS: Since flaot literals are effectively interned objects, no need to copyAndReturnValue(...)
        // Or is this a premature optimization?
        return new Float(node.getValue());
    }

    public Operand buildFor(ForNode forNode, IRScope s) {
        Variable result = s.createTemporaryVariable();
        Operand  receiver = build(forNode.getIterNode(), s);
        Operand  forBlock = buildForIter(forNode, s);
        CallInstr callInstr = new CallInstr(CallType.NORMAL, result, "each", receiver, NO_ARGS, forBlock);
        receiveBreakException(s, forBlock, callInstr);

        return result;
    }

    public Operand buildForIter(final ForNode forNode, IRScope s) {
            // Create a new closure context
        IRClosure closure = new IRFor(manager, s, forNode.getPosition().getLine(), forNode.getScope(), Arity.procArityOf(forNode.getVarNode()), forNode.getArgumentType());

        // Create a new nested builder to ensure this gets its own IR builder state
        // like the ensure block stack
        IRBuilder forBuilder = newIRBuilder(manager);

        // Prepare all implicit state (self, frame block, etc)
        forBuilder.prepareImplicitState(closure);

            // Build args
        Node varNode = forNode.getVarNode();
        if (varNode != null && varNode.getNodeType() != null) forBuilder.receiveBlockArgs(forNode, closure);

        // Set %current_scope = <current-scope>
        // Set %current_module = <current-module>
        forBuilder.addInstr(closure, new CopyInstr(closure.getCurrentScopeVariable(), CURRENT_SCOPE[0]));
        forBuilder.addInstr(closure, new CopyInstr(closure.getCurrentModuleVariable(), SCOPE_MODULE[0]));

        // Thread poll on entry of closure
        forBuilder.addInstr(closure, new ThreadPollInstr());

            // Start label -- used by redo!
        forBuilder.addInstr(closure, new LabelInstr(closure.startLabel));

            // Build closure body and return the result of the closure
        Operand closureRetVal = forNode.getBodyNode() == null ? manager.getNil() : forBuilder.build(forNode.getBodyNode(), closure);
        if (closureRetVal != U_NIL) { // can be null if the node is an if node with returns in both branches.
            forBuilder.addInstr(closure, new ReturnInstr(closureRetVal));
        }

        return new WrappedIRClosure(s.getSelf(), closure);
    }

    public Operand buildGlobalAsgn(GlobalAsgnNode globalAsgnNode, IRScope s) {
        Operand value = build(globalAsgnNode.getValueNode(), s);
        addInstr(s, new PutGlobalVarInstr(globalAsgnNode.getName(), value));
        return value;
    }

    public Operand buildGlobalVar(GlobalVarNode node, IRScope s) {
        return addResultInstr(s, new GetGlobalVariableInstr(s.createTemporaryVariable(), node.getName()));
    }

    public Operand buildHash(HashNode hashNode, IRScope s) {
        List<KeyValuePair<Operand, Operand>> args = new ArrayList<>();
        Operand splatKeywordArgument = null;

        for (KeyValuePair<Node, Node> pair: hashNode.getPairs()) {
            Node key = pair.getKey();
            Operand keyOperand;

            if (key == null) { // splat kwargs [e.g. foo(a: 1, **splat)] key is null and will be in last pair of hash
                splatKeywordArgument = build(pair.getValue(), s);
                break;
            } else {
               keyOperand = build(key, s);
            }

            args.add(new KeyValuePair<>(keyOperand, build(pair.getValue(), s)));
        }

        if (splatKeywordArgument != null) { // splat kwargs merge with any explicit kwargs
            Variable tmp = s.createTemporaryVariable();
            s.addInstr(new RuntimeHelperCall(tmp, MERGE_KWARGS, new Operand[] { splatKeywordArgument, new Hash(args)}));
            return tmp;
        } else {
            return copyAndReturnValue(s, new Hash(args));
        }
    }

    // Translate "r = if (cond); .. thenbody ..; else; .. elsebody ..; end" to
    //
    //     v = -- build(cond) --
    //     BEQ(v, FALSE, L1)
    //     r = -- build(thenbody) --
    //     jump L2
    // L1:
    //     r = -- build(elsebody) --
    // L2:
    //     --- r is the result of the if expression --
    //
    public Operand buildIf(final IfNode ifNode, IRScope s) {
        Node actualCondition = skipOverNewlines(s, ifNode.getCondition());

        Variable result;
        Label    falseLabel = s.getNewLabel();
        Label    doneLabel  = s.getNewLabel();
        Operand  thenResult;
        addInstr(s, BEQInstr.create(build(actualCondition, s), manager.getFalse(), falseLabel));

        boolean thenNull = false;
        boolean elseNull = false;
        boolean thenUnil = false;
        boolean elseUnil = false;

        // Build the then part of the if-statement
        if (ifNode.getThenBody() != null) {
            thenResult = build(ifNode.getThenBody(), s);
            if (thenResult != U_NIL) { // thenResult can be U_NIL if then-body ended with a return!
                // SSS FIXME: Can look at the last instr and short-circuit this jump if it is a break rather
                // than wait for dead code elimination to do it
                result = getValueInTemporaryVariable(s, thenResult);
                addInstr(s, new JumpInstr(doneLabel));
            } else {
                result = s.createTemporaryVariable();
                thenUnil = true;
            }
        } else {
            thenNull = true;
            result = addResultInstr(s, new CopyInstr(s.createTemporaryVariable(), manager.getNil()));
            addInstr(s, new JumpInstr(doneLabel));
        }

        // Build the else part of the if-statement
        addInstr(s, new LabelInstr(falseLabel));
        if (ifNode.getElseBody() != null) {
            Operand elseResult = build(ifNode.getElseBody(), s);
            // elseResult can be U_NIL if then-body ended with a return!
            if (elseResult != U_NIL) {
                addInstr(s, new CopyInstr(result, elseResult));
            } else {
                elseUnil = true;
            }
        } else {
            elseNull = true;
            addInstr(s, new CopyInstr(result, manager.getNil()));
        }

        if (thenNull && elseNull) {
            addInstr(s, new LabelInstr(doneLabel));
            return manager.getNil();
        } else if (thenUnil && elseUnil) {
            return U_NIL;
        } else {
            addInstr(s, new LabelInstr(doneLabel));
            return result;
        }
    }

    public Operand buildInstAsgn(final InstAsgnNode instAsgnNode, IRScope s) {
        Operand val = build(instAsgnNode.getValueNode(), s);
        // NOTE: if 's' happens to the a class, this is effectively an assignment of a class instance variable
        addInstr(s, new PutFieldInstr(s.getSelf(), instAsgnNode.getName(), val));
        return val;
    }

    public Operand buildInstVar(InstVarNode node, IRScope s) {
        return addResultInstr(s, new GetFieldInstr(s.createTemporaryVariable(), s.getSelf(), node.getName()));
    }

    public Operand buildIter(final IterNode iterNode, IRScope s) {
        IRClosure closure = new IRClosure(manager, s, iterNode.getPosition().getLine(), iterNode.getScope(), Arity.procArityOf(iterNode.getVarNode()), iterNode.getArgumentType());

        // Create a new nested builder to ensure this gets its own IR builder state
        // like the ensure block stack
        IRBuilder closureBuilder = newIRBuilder(manager);

        // Prepare all implicit state (self, frame block, etc)
        closureBuilder.prepareImplicitState(closure);

        // load block into temporary variable
        if (s instanceof IRMethod) {
            addInstr(s, new LoadImplicitClosureInstr(s.getYieldClosureVariable()));
        } else {
            addInstr(s, new LoadFrameClosureInstr(s.getYieldClosureVariable()));
        }

        // Build args
        if (iterNode.getVarNode().getNodeType() != null) closureBuilder.receiveBlockArgs(iterNode, closure);

        // Set %current_scope = <current-scope>
        // Set %current_module = <current-module>
        closureBuilder.addInstr(closure, new CopyInstr(closure.getCurrentScopeVariable(), CURRENT_SCOPE[0]));
        closureBuilder.addInstr(closure, new CopyInstr(closure.getCurrentModuleVariable(), SCOPE_MODULE[0]));

        // Thread poll on entry of closure
        closureBuilder.addInstr(closure, new ThreadPollInstr());

        // start label -- used by redo!
        closureBuilder.addInstr(closure, new LabelInstr(closure.startLabel));

        // Build closure body and return the result of the closure
        Operand closureRetVal = iterNode.getBodyNode() == null ? manager.getNil() : closureBuilder.build(iterNode.getBodyNode(), closure);
        if (closureRetVal != U_NIL) { // can be U_NIL if the node is an if node with returns in both branches.
            closureBuilder.addInstr(closure, new ReturnInstr(closureRetVal));
        }

        // Always add break/return handling even though this
        // is only required for lambdas, but we don't know at this time,
        // if this is a lambda or not.
        //
        // SSS FIXME: At a later time, see if we can optimize this and
        // do this on demand.
        closureBuilder.handleBreakAndReturnsInLambdas(closure);

        return new WrappedIRClosure(s.getSelf(), closure);
    }

    public Operand buildLiteral(LiteralNode literalNode, IRScope s) {
        return copyAndReturnValue(s, new StringLiteral(literalNode.getName()));
    }

    public Operand buildLocalAsgn(LocalAsgnNode localAsgnNode, IRScope s) {
        Variable var  = s.getLocalVariable(localAsgnNode.getName(), localAsgnNode.getDepth());
        Operand value = build(localAsgnNode.getValueNode(), s);
        addInstr(s, new CopyInstr(var, value));
        return value;

        // IMPORTANT: The return value of this method is value, not var!
        //
        // Consider this Ruby code: foo((a = 1), (a = 2))
        //
        // If we return 'value' this will get translated to:
        //    a = 1
        //    a = 2
        //    call("foo", [1,2]) <---- CORRECT
        //
        // If we return 'var' this will get translated to:
        //    a = 1
        //    a = 2
        //    call("foo", [a,a]) <---- BUGGY
        //
        // This technique only works if 'value' is an immutable value (ex: fixnum) or a variable
        // So, for Ruby code like this:
        //     def foo(x); x << 5; end;
        //     foo(a=[1,2]);
        //     p a
        // we are guaranteed that the value passed into foo and 'a' point to the same object
        // because of the use of copyAndReturnValue method for literal objects.
    }

    public Operand buildLocalVar(LocalVarNode node, IRScope s) {
        return s.getLocalVariable(node.getName(), node.getDepth());
    }

    public Operand buildMatch(MatchNode matchNode, IRScope s) {
        Operand regexp = build(matchNode.getRegexpNode(), s);

        return addResultInstr(s, new MatchInstr(s.createTemporaryVariable(), regexp));
    }

    public Operand buildMatch2(Match2Node matchNode, IRScope s) {
        Operand receiver = build(matchNode.getReceiverNode(), s);
        Operand value    = build(matchNode.getValueNode(), s);
        Variable result  = s.createTemporaryVariable();
        addInstr(s, new Match2Instr(result, receiver, value));
        if (matchNode instanceof Match2CaptureNode) {
            Match2CaptureNode m2c = (Match2CaptureNode)matchNode;
            for (int slot:  m2c.getScopeOffsets()) {
                // Static scope scope offsets store both depth and offset
                int depth = slot >> 16;
                int offset = slot & 0xffff;

                // For now, we'll continue to implicitly reference "$~"
                String var = getVarNameFromScopeTree(s, depth, offset);
                addInstr(s, new SetCapturedVarInstr(s.getLocalVariable(var, depth), result, var));
            }
        }
        return result;
    }

    private String getVarNameFromScopeTree(IRScope scope, int depth, int offset) {
        if (depth == 0) {
            return scope.getStaticScope().getVariables()[offset];
        }
        return getVarNameFromScopeTree(scope.getLexicalParent(), depth - 1, offset);
    }

    public Operand buildMatch3(Match3Node matchNode, IRScope s) {
        Operand receiver = build(matchNode.getReceiverNode(), s);
        Operand value    = build(matchNode.getValueNode(), s);

        return addResultInstr(s, new Match3Instr(s.createTemporaryVariable(), receiver, value));
    }

    private Operand getContainerFromCPath(Colon3Node cpath, IRScope s) {
        Operand container;

        if (cpath instanceof Colon2Node) {
            Node leftNode = ((Colon2Node) cpath).getLeftNode();

            if (leftNode != null) { // Foo::Bar
                container = build(leftNode, s);
            } else { // Only name with no left-side Bar <- Note no :: on left
                container = findContainerModule(s);
            }
        } else { //::Bar
            container = new ObjectClass();
        }

        return container;
    }

    public Operand buildModule(ModuleNode moduleNode, IRScope s) {
        Colon3Node cpath = moduleNode.getCPath();
        String moduleName = cpath.getName();
        Operand container = getContainerFromCPath(cpath, s);
        IRModuleBody body = new IRModuleBody(manager, s, moduleName, moduleNode.getPosition().getLine(), moduleNode.getScope());
        Variable moduleVar = addResultInstr(s, new DefineModuleInstr(s.createTemporaryVariable(), body, container));

        return buildModuleOrClassBody(s, moduleVar, body, moduleNode.getBodyNode(), moduleNode.getPosition().getLine(), NullBlock.INSTANCE);
    }

    public Operand buildMultipleAsgn(MultipleAsgnNode multipleAsgnNode, IRScope s) {
        Operand  values = build(multipleAsgnNode.getValueNode(), s);
        Variable ret = getValueInTemporaryVariable(s, values);
        buildMultipleAsgnAssignment(multipleAsgnNode, s, null, ret);
        return ret;
    }

    // SSS: This method is called both for regular multiple assignment as well as argument passing
    //
    // Ex: a,b,*c=v  is a regular assignment and in this case, the "values" operand will be non-null
    // Ex: { |a,b,*c| ..} is the argument passing case
    public void buildMultipleAsgnAssignment(final MultipleAsgnNode multipleAsgnNode, IRScope s, Operand argsArray, Operand values) {
        final ListNode sourceArray = multipleAsgnNode.getHeadNode();

        // First, build assignments for specific named arguments
        int i = 0;
        if (sourceArray != null) {
            for (Node an: sourceArray.childNodes()) {
                if (values == null) {
                    buildBlockArgsAssignment(an, s, argsArray, i, false);
                } else {
                    Variable rhsVal = addResultInstr(s, new ReqdArgMultipleAsgnInstr(s.createTemporaryVariable(), values, i));
                    buildAssignment(an, s, rhsVal);
                }
                i++;
            }
        }

        // First, build an assignment for a splat, if any, with the rest of the args!
        Node argsNode = multipleAsgnNode.getArgsNode();
        if (argsNode == null) {
            if (sourceArray == null) {
                throw new NotCompilableException("Something's wrong, multiple assignment with no head or args at: " + multipleAsgnNode.getPosition());
            }
        } else if (!(argsNode instanceof StarNode)) {
            if (values != null) {
                buildAssignment(argsNode, s,    // rest of the argument array!
                        addResultInstr(s, new RestArgMultipleAsgnInstr(s.createTemporaryVariable(), values, i)));
            } else {
                buildBlockArgsAssignment(argsNode, s, argsArray, i, true); // rest of the argument array!
            }
        }
    }

    public Operand buildNewline(NewlineNode node, IRScope s) {
        return build(skipOverNewlines(s, node), s);
    }

    public Operand buildNext(final NextNode nextNode, IRScope s) {
        IRLoop currLoop = getCurrentLoop();

        Operand rv = (nextNode.getValueNode() == null) ? manager.getNil() : build(nextNode.getValueNode(), s);

        // If we have ensure blocks, have to run those first!
        if (!activeEnsureBlockStack.empty()) emitEnsureBlocks(s, currLoop);
        else if (!activeRescueBlockStack.empty()) activeRescueBlockStack.peek().restoreException(this, s, currLoop);

        if (currLoop != null) {
            // If a regular loop, the next is simply a jump to the end of the iteration
            addInstr(s, new JumpInstr(currLoop.iterEndLabel));
        } else {
            addInstr(s, new ThreadPollInstr(true));
            // If a closure, the next is simply a return from the closure!
            if (s instanceof IRClosure) addInstr(s, new ReturnInstr(rv));
            else addInstr(s, new ThrowExceptionInstr(IRException.NEXT_LocalJumpError));
        }

        // Once the "next instruction" (closure-return) executes, control exits this scope
        return U_NIL;
    }

    public Operand buildNthRef(NthRefNode nthRefNode, IRScope s) {
        return copyAndReturnValue(s, new NthRef(nthRefNode.getMatchNumber()));
    }

    public Operand buildNil() {
        return manager.getNil();
    }

    public Operand buildOpAsgn(OpAsgnNode opAsgnNode, IRScope s) {
        Label l;
        Variable readerValue = s.createTemporaryVariable();
        Variable writerValue = s.createTemporaryVariable();

        // get attr
        Operand  v1 = build(opAsgnNode.getReceiverNode(), s);
        addInstr(s, CallInstr.create(readerValue, opAsgnNode.getVariableName(), v1, NO_ARGS, null));

        // Ex: e.val ||= n
        //     e.val &&= n
        String opName = opAsgnNode.getOperatorName();
        if (opName.equals("||") || opName.equals("&&")) {
            l = s.getNewLabel();
            addInstr(s, BEQInstr.create(readerValue, opName.equals("||") ? manager.getTrue() : manager.getFalse(), l));

            // compute value and set it
            Operand  v2 = build(opAsgnNode.getValueNode(), s);
            addInstr(s, CallInstr.create(writerValue, opAsgnNode.getVariableNameAsgn(), v1, new Operand[] {v2}, null));
            // It is readerValue = v2.
            // readerValue = writerValue is incorrect because the assignment method
            // might return something else other than the value being set!
            addInstr(s, new CopyInstr(readerValue, v2));
            addInstr(s, new LabelInstr(l));

            return readerValue;
        }
        // Ex: e.val = e.val.f(n)
        else {
            // call operator
            Operand  v2 = build(opAsgnNode.getValueNode(), s);
            Variable setValue = s.createTemporaryVariable();
            addInstr(s, CallInstr.create(setValue, opAsgnNode.getOperatorName(), readerValue, new Operand[]{v2}, null));

            // set attr
            addInstr(s, CallInstr.create(writerValue, opAsgnNode.getVariableNameAsgn(), v1, new Operand[] {setValue}, null));
            // Returning writerValue is incorrect becuase the assignment method
            // might return something else other than the value being set!
            return setValue;
        }
    }

    // Translate "x &&= y" --> "x = y if is_true(x)" -->
    //
    //    x = -- build(x) should return a variable! --
    //    f = is_true(x)
    //    beq(f, false, L)
    //    x = -- build(y) --
    // L:
    //
    public Operand buildOpAsgnAnd(OpAsgnAndNode andNode, IRScope s) {
        Label    l  = s.getNewLabel();
        Operand  v1 = build(andNode.getFirstNode(), s);
        Variable result = getValueInTemporaryVariable(s, v1);
        addInstr(s, BEQInstr.create(v1, manager.getFalse(), l));
        Operand v2 = build(andNode.getSecondNode(), s);  // This does the assignment!
        addInstr(s, new CopyInstr(result, v2));
        addInstr(s, new LabelInstr(l));
        return result;
    }

    // "x ||= y"
    // --> "x = (is_defined(x) && is_true(x) ? x : y)"
    // --> v = -- build(x) should return a variable! --
    //     f = is_true(v)
    //     beq(f, true, L)
    //     -- build(x = y) --
    //   L:
    //
    public Operand buildOpAsgnOr(final OpAsgnOrNode orNode, IRScope s) {
        Label    l1 = s.getNewLabel();
        Label    l2 = null;
        Variable flag = s.createTemporaryVariable();
        Operand  v1;
        boolean  needsDefnCheck = orNode.getFirstNode().needsDefinitionCheck();
        if (needsDefnCheck) {
            l2 = s.getNewLabel();
            v1 = buildGetDefinition(orNode.getFirstNode(), s);
            addInstr(s, new CopyInstr(flag, v1));
            addInstr(s, BEQInstr.create(flag, manager.getNil(), l2)); // if v1 is undefined, go to v2's computation
        }
        v1 = build(orNode.getFirstNode(), s); // build of 'x'
        addInstr(s, new CopyInstr(flag, v1));
        Variable result = getValueInTemporaryVariable(s, v1);
        if (needsDefnCheck) {
            addInstr(s, new LabelInstr(l2));
        }
        addInstr(s, BEQInstr.create(flag, manager.getTrue(), l1));  // if v1 is defined and true, we are done!
        Operand v2 = build(orNode.getSecondNode(), s); // This is an AST node that sets x = y, so nothing special to do here.
        addInstr(s, new CopyInstr(result, v2));
        addInstr(s, new LabelInstr(l1));

        // Return value of x ||= y is always 'x'
        return result;
    }

    public Operand buildOpElementAsgn(OpElementAsgnNode node, IRScope s) {
        if (node.isOr()) return buildOpElementAsgnWithOr(node, s);
        if (node.isAnd()) return buildOpElementAsgnWithAnd(node, s);

        return buildOpElementAsgnWithMethod(node, s);
    }

    // Translate "a[x] ||= n" --> "a[x] = n if !is_true(a[x])"
    //
    //    tmp = build(a) <-- receiver
    //    arg = build(x) <-- args
    //    val = buildCall([], tmp, arg)
    //    f = is_true(val)
    //    beq(f, true, L)
    //    val = build(n) <-- val
    //    buildCall([]= tmp, arg, val)
    // L:
    //
    public Operand buildOpElementAsgnWithOr(OpElementAsgnNode opElementAsgnNode, IRScope s) {
        Operand array = build(opElementAsgnNode.getReceiverNode(), s);
        Label    l     = s.getNewLabel();
        Variable elt   = s.createTemporaryVariable();
        Operand[] argList = setupCallArgs(opElementAsgnNode.getArgsNode(), s);
        addInstr(s, CallInstr.create(elt, "[]", array, argList, null));
        addInstr(s, BEQInstr.create(elt, manager.getTrue(), l));
        Operand value = build(opElementAsgnNode.getValueNode(), s);
        argList = addArg(argList, value);
        addInstr(s, CallInstr.create(elt, "[]=", array, argList, null));
        addInstr(s, new CopyInstr(elt, value));
        addInstr(s, new LabelInstr(l));
        return elt;
    }

    // Translate "a[x] &&= n" --> "a[x] = n if is_true(a[x])"
    public Operand buildOpElementAsgnWithAnd(OpElementAsgnNode opElementAsgnNode, IRScope s) {
        Operand array = build(opElementAsgnNode.getReceiverNode(), s);
        Label    l     = s.getNewLabel();
        Variable elt   = s.createTemporaryVariable();
        Operand[] argList = setupCallArgs(opElementAsgnNode.getArgsNode(), s);
        addInstr(s, CallInstr.create(elt, "[]", array, argList, null));
        addInstr(s, BEQInstr.create(elt, manager.getFalse(), l));
        Operand value = build(opElementAsgnNode.getValueNode(), s);

        argList = addArg(argList, value);
        addInstr(s, CallInstr.create(elt, "[]=", array, argList, null));
        addInstr(s, new CopyInstr(elt, value));
        addInstr(s, new LabelInstr(l));
        return elt;
    }

    // a[i] *= n, etc.  anything that is not "a[i] &&= .. or a[i] ||= .."
    //    arr = build(a) <-- receiver
    //    arg = build(x) <-- args
    //    elt = buildCall([], arr, arg)
    //    val = build(n) <-- val
    //    val = buildCall(METH, elt, val)
    //    val = buildCall([]=, arr, arg, val)
    public Operand buildOpElementAsgnWithMethod(OpElementAsgnNode opElementAsgnNode, IRScope s) {
        Operand array = build(opElementAsgnNode.getReceiverNode(), s);
        Operand[] argList = setupCallArgs(opElementAsgnNode.getArgsNode(), s);
        Variable elt = s.createTemporaryVariable();
        addInstr(s, CallInstr.create(elt, "[]", array, argList, null)); // elt = a[args]
        Operand value = build(opElementAsgnNode.getValueNode(), s);                                       // Load 'value'
        String  operation = opElementAsgnNode.getOperatorName();
        addInstr(s, CallInstr.create(elt, operation, elt, new Operand[] { value }, null)); // elt = elt.OPERATION(value)
        // SSS: do not load the call result into 'elt' to eliminate the RAW dependency on the call
        // We already know what the result is going be .. we are just storing it back into the array
        Variable tmp = s.createTemporaryVariable();
        argList = addArg(argList, elt);
        addInstr(s, CallInstr.create(tmp, "[]=", array, argList, null));   // a[args] = elt
        return elt;
    }

    // Translate ret = (a || b) to ret = (a ? true : b) as follows
    //
    //    v1 = -- build(a) --
    //       OPT: ret can be set to v1, but effectively v1 is true if we take the branch to L.
    //            while this info can be inferred by using attributes, why bother if we can do this?
    //    ret = v1
    //    beq(v1, true, L)
    //    v2 = -- build(b) --
    //    ret = v2
    // L:
    //
    public Operand buildOr(final OrNode orNode, IRScope s) {
        if (orNode.getFirstNode().getNodeType().alwaysTrue()) {
            // build first node only and return true
            return build(orNode.getFirstNode(), s);
        } else if (orNode.getFirstNode().getNodeType().alwaysFalse()) {
            // build first node as non-expr and build second node
            build(orNode.getFirstNode(), s);
            return build(orNode.getSecondNode(), s);
        } else {
            Label    l   = s.getNewLabel();
            Operand  v1  = build(orNode.getFirstNode(), s);
            Variable ret = getValueInTemporaryVariable(s, v1);
            addInstr(s, BEQInstr.create(v1, manager.getTrue(), l));
            Operand  v2  = build(orNode.getSecondNode(), s);
            addInstr(s, new CopyInstr(ret, v2));
            addInstr(s, new LabelInstr(l));
            return ret;
        }
    }

    public Operand buildPostExe(PostExeNode postExeNode, IRScope s) {
        IRScope topLevel = s.getTopLevelScope();
        IRScope nearestLVarScope = s.getNearestTopLocalVariableScope();

        IRClosure endClosure = new IRClosure(manager, s, postExeNode.getPosition().getLine(), nearestLVarScope.getStaticScope(), Arity.procArityOf(postExeNode.getVarNode()), postExeNode.getArgumentType(), "_END_", true);
        // Create a new nested builder to ensure this gets its own IR builder state
        // like the ensure block stack
        IRBuilder closureBuilder = newIRBuilder(manager);

        // Set up %current_scope and %current_module
        closureBuilder.addInstr(endClosure, new CopyInstr(endClosure.getCurrentScopeVariable(), CURRENT_SCOPE[0]));
        closureBuilder.addInstr(endClosure, new CopyInstr(endClosure.getCurrentModuleVariable(), SCOPE_MODULE[0]));
        closureBuilder.build(postExeNode.getBodyNode(), endClosure);

        // END does not have either explicit or implicit return, so we add one
        closureBuilder.addInstr(endClosure, new ReturnInstr(new Nil()));

        // Add an instruction in 's' to record the end block in the 'topLevel' scope.
        // SSS FIXME: IR support for end-blocks that access vars in non-toplevel-scopes
        // might be broken currently. We could either fix it or consider dropping support
        // for END blocks altogether or only support them in the toplevel. Not worth the pain.
        addInstr(s, new RecordEndBlockInstr(topLevel, new WrappedIRClosure(s.getSelf(), endClosure)));
        return manager.getNil();
    }

    public Operand buildPreExe(PreExeNode preExeNode, IRScope s) {
        IRClosure beginClosure = new IRFor(manager, s, preExeNode.getPosition().getLine(), s.getTopLevelScope().getStaticScope(), Arity.procArityOf(preExeNode.getVarNode()), preExeNode.getArgumentType(), "_BEGIN_");
        // Create a new nested builder to ensure this gets its own IR builder state
        // like the ensure block stack
        IRBuilder closureBuilder = newIRBuilder(manager);

        // Set up %current_scope and %current_module
        closureBuilder.addInstr(beginClosure, new CopyInstr(beginClosure.getCurrentScopeVariable(), CURRENT_SCOPE[0]));
        closureBuilder.addInstr(beginClosure, new CopyInstr(beginClosure.getCurrentModuleVariable(), SCOPE_MODULE[0]));
        closureBuilder.build(preExeNode.getBodyNode(), beginClosure);

        // BEGIN does not have either explicit or implicit return, so we add one
        closureBuilder.addInstr(beginClosure, new ReturnInstr(new Nil()));

        // Record the begin block at IR build time
        s.getTopLevelScope().recordBeginBlock(beginClosure);
        return manager.getNil();
    }

    public Operand buildRational(RationalNode rationalNode) {
        return new Rational(rationalNode.getNumerator(), rationalNode.getDenominator());
    }

    public Operand buildRedo(IRScope s) {
        // If in a loop, a redo is a jump to the beginning of the loop.
        // If not, for closures, a redo is a jump to the beginning of the closure.
        // If not in a loop or a closure, it is a local jump error
        IRLoop currLoop = getCurrentLoop();
        if (currLoop != null) {
             addInstr(s, new JumpInstr(currLoop.iterStartLabel));
        } else {
            if (s instanceof IRClosure) {
                addInstr(s, new ThreadPollInstr(true));
                addInstr(s, new JumpInstr(((IRClosure)s).startLabel));
            } else {
                addInstr(s, new ThrowExceptionInstr(IRException.REDO_LocalJumpError));
            }
        }
        return manager.getNil();
    }

    public Operand buildRegexp(RegexpNode reNode, IRScope s) {
        // SSS FIXME: Rather than throw syntax error at runtime, we should detect
        // regexp syntax errors at build time and add an exception-throwing instruction instead
        return copyAndReturnValue(s, new Regexp(new StringLiteral(reNode.getValue()), reNode.getOptions()));
    }

    public Operand buildRescue(RescueNode node, IRScope s) {
        return buildRescueInternal(node, s, null);
    }

    private Operand buildRescueInternal(RescueNode rescueNode, IRScope s, EnsureBlockInfo ensure) {
        // Labels marking start, else, end of the begin-rescue(-ensure)-end block
        Label rBeginLabel = ensure == null ? s.getNewLabel() : ensure.regionStart;
        Label rEndLabel   = ensure == null ? s.getNewLabel() : ensure.end;
        Label rescueLabel = s.getNewLabel(); // Label marking start of the first rescue code.

        // Save $! in a temp var so it can be restored when the exception gets handled.
        Variable savedGlobalException = s.createTemporaryVariable();
        addInstr(s, new GetGlobalVariableInstr(savedGlobalException, "$!"));
        if (ensure != null) ensure.savedGlobalException = savedGlobalException;

        addInstr(s, new LabelInstr(rBeginLabel));

        // Placeholder rescue instruction that tells rest of the compiler passes the boundaries of the rescue block.
        addInstr(s, new ExceptionRegionStartMarkerInstr(rescueLabel));
        activeRescuers.push(rescueLabel);

        // Body
        Operand tmp = manager.getNil();  // default return value if for some strange reason, we neither have the body node or the else node!
        Variable rv = s.createTemporaryVariable();
        if (rescueNode.getBodyNode() != null) tmp = build(rescueNode.getBodyNode(), s);

        // Push rescue block *after* body has been built.
        // If not, this messes up generation of retry in these scenarios like this:
        //
        //     begin    -- 1
        //       ...
        //     rescue
        //       begin  -- 2
        //         ...
        //         retry
        //       rescue
        //         ...
        //       end
        //     end
        //
        // The retry should jump to 1, not 2.
        // If we push the rescue block before building the body, we will jump to 2.
        RescueBlockInfo rbi = new RescueBlockInfo(rescueNode, rBeginLabel, savedGlobalException, getCurrentLoop());
        activeRescueBlockStack.push(rbi);

        // Since rescued regions are well nested within Ruby, this bare marker is sufficient to
        // let us discover the edge of the region during linear traversal of instructions during cfg construction.
        addInstr(s, new ExceptionRegionEndMarkerInstr());
        activeRescuers.pop();

        // Else part of the body -- we simply fall through from the main body if there were no exceptions
        Label elseLabel = rescueNode.getElseNode() == null ? null : s.getNewLabel();
        if (elseLabel != null) {
            addInstr(s, new LabelInstr(elseLabel));
            tmp = build(rescueNode.getElseNode(), s);
        }

        if (tmp != U_NIL) {
            addInstr(s, new CopyInstr(rv, tmp));

            // No explicit return from the protected body
            // - If we dont have any ensure blocks, simply jump to the end of the rescue block
            // - If we do, execute the ensure code.
            if (ensure != null) {
                ensure.cloneIntoHostScope(this, s);
            }
            addInstr(s, new JumpInstr(rEndLabel));
        }   //else {
            // If the body had an explicit return, the return instruction IR build takes care of setting
            // up execution of all necessary ensure blocks.  So, nothing to do here!
            //
            // Additionally, the value in 'rv' will never be used, so need to set it to any specific value.
            // So, we can leave it undefined.  If on the other hand, there was an exception in that block,
            // 'rv' will get set in the rescue handler -- see the 'rv' being passed into
            // buildRescueBodyInternal below.  So, in either case, we are good!
            //}

        // Start of rescue logic
        addInstr(s, new LabelInstr(rescueLabel));

        // Save off exception & exception comparison type
        Variable exc = addResultInstr(s, new ReceiveRubyExceptionInstr(s.createTemporaryVariable()));

        // Build the actual rescue block(s)
        buildRescueBodyInternal(s, rescueNode.getRescueNode(), rv, exc, rEndLabel);

        // End label -- only if there is no ensure block!  With an ensure block, you end at ensureEndLabel.
        if (ensure == null) addInstr(s, new LabelInstr(rEndLabel));

        activeRescueBlockStack.pop();
        return rv;
    }

    private void outputExceptionCheck(IRScope s, Operand excType, Operand excObj, Label caughtLabel) {
        Variable eqqResult = addResultInstr(s, new RescueEQQInstr(s.createTemporaryVariable(), excType, excObj));
        addInstr(s, BEQInstr.create(eqqResult, manager.getTrue(), caughtLabel));
    }

    private void buildRescueBodyInternal(IRScope s, RescueBodyNode rescueBodyNode, Variable rv, Variable exc, Label endLabel) {
        final Node exceptionList = rescueBodyNode.getExceptionNodes();

        // Compare and branch as necessary!
        Label uncaughtLabel = s.getNewLabel();
        Label caughtLabel = s.getNewLabel();
        if (exceptionList != null) {
            if (exceptionList instanceof ListNode) {
                List<Operand> excTypes = new ArrayList<>();
                for (Node excType : exceptionList.childNodes()) {
                    excTypes.add(build(excType, s));
                }
                outputExceptionCheck(s, new Array(excTypes), exc, caughtLabel);
            } else if (exceptionList instanceof SplatNode) { // splatnode, catch
                outputExceptionCheck(s, build(((SplatNode)exceptionList).getValue(), s), exc, caughtLabel);
            } else { // argscat/argspush
                outputExceptionCheck(s, build(exceptionList, s), exc, caughtLabel);
            }
        } else {
            // SSS FIXME:
            // rescue => e AND rescue implicitly EQQ the exception object with StandardError
            // We generate explicit IR for this test here.  But, this can lead to inconsistent
            // behavior (when compared to MRI) in certain scenarios.  See example:
            //
            //   self.class.const_set(:StandardError, 1)
            //   begin; raise TypeError.new; rescue; puts "AHA"; end
            //
            // MRI rescues the error, but we will raise an exception because of reassignment
            // of StandardError.  I am ignoring this for now and treating this as undefined behavior.
            //
            // Solution: Create a 'StandardError' operand type to eliminate this.
            Variable v = addResultInstr(s, new InheritanceSearchConstInstr(s.createTemporaryVariable(), s.getCurrentModuleVariable(), "StandardError", false));
            outputExceptionCheck(s, v, exc, caughtLabel);
        }

        // Uncaught exception -- build other rescue nodes or rethrow!
        addInstr(s, new LabelInstr(uncaughtLabel));
        if (rescueBodyNode.getOptRescueNode() != null) {
            buildRescueBodyInternal(s, rescueBodyNode.getOptRescueNode(), rv, exc, endLabel);
        } else {
            addInstr(s, new ThrowExceptionInstr(exc));
        }

        // Caught exception case -- build rescue body
        addInstr(s, new LabelInstr(caughtLabel));
        Node realBody = skipOverNewlines(s, rescueBodyNode.getBodyNode());
        Operand x = build(realBody, s);
        if (x != U_NIL) { // can be U_NIL if the rescue block has an explicit return
            // Restore "$!"
            RescueBlockInfo rbi = activeRescueBlockStack.peek();
            addInstr(s, new PutGlobalVarInstr("$!", rbi.savedExceptionVariable));

            // Set up node return value 'rv'
            addInstr(s, new CopyInstr(rv, x));

            // If we have a matching ensure block, clone it so ensure block runs here
            if (!activeEnsureBlockStack.empty() && rbi.rescueNode == activeEnsureBlockStack.peek().matchingRescueNode) {
                activeEnsureBlockStack.peek().cloneIntoHostScope(this, s);
            }
            addInstr(s, new JumpInstr(endLabel));
        }
    }

    public Operand buildRetry(IRScope s) {
        // JRuby only supports retry when present in rescue blocks!
        // 1.9 doesn't support retry anywhere else.

        // Jump back to the innermost rescue block
        // We either find it, or we add code to throw a runtime exception
        if (activeRescueBlockStack.empty()) {
            addInstr(s, new ThrowExceptionInstr(IRException.RETRY_LocalJumpError));
        } else {
            addInstr(s, new ThreadPollInstr(true));
            // Restore $! and jump back to the entry of the rescue block
            RescueBlockInfo rbi = activeRescueBlockStack.peek();
            addInstr(s, new PutGlobalVarInstr("$!", rbi.savedExceptionVariable));
            addInstr(s, new JumpInstr(rbi.entryLabel));
            // Retries effectively create a loop
            s.setHasLoopsFlag();
        }
        return manager.getNil();
    }

    private Operand processEnsureRescueBlocks(IRScope s, Operand retVal) { 
        // Before we return,
        // - have to go execute all the ensure blocks if there are any.
        //   this code also takes care of resetting "$!"
        // - if we have a rescue block, reset "$!".
        if (!activeEnsureBlockStack.empty()) {
            retVal = addResultInstr(s, new CopyInstr(s.createTemporaryVariable(), retVal));
            emitEnsureBlocks(s, null);
        } else if (!activeRescueBlockStack.empty()) {
            // Restore $!
            RescueBlockInfo rbi = activeRescueBlockStack.peek();
            addInstr(s, new PutGlobalVarInstr("$!", rbi.savedExceptionVariable));
        }
       return retVal;
    }

    public Operand buildReturn(ReturnNode returnNode, IRScope s) {
        Operand retVal = (returnNode.getValueNode() == null) ? manager.getNil() : build(returnNode.getValueNode(), s);

        if (s instanceof IRClosure) {
            // If 'm' is a block scope, a return returns from the closest enclosing method.
            // If this happens to be a module body, the runtime throws a local jump error if the
            // closure is a proc. If the closure is a lambda, then this becomes a normal return.
            IRMethod m = s.getNearestMethod();
            addInstr(s, new RuntimeHelperCall(null, CHECK_FOR_LJE, new Operand[] { new Boolean(m == null) }));
            retVal = processEnsureRescueBlocks(s, retVal);
            addInstr(s, new NonlocalReturnInstr(retVal, m == null ? "--none--" : m.getName()));
        } else if (s.isModuleBody()) {
            IRMethod sm = s.getNearestMethod();

            // Cannot return from top-level module bodies!
            if (sm == null) addInstr(s, new ThrowExceptionInstr(IRException.RETURN_LocalJumpError));
            retVal = processEnsureRescueBlocks(s, retVal);
            if (sm != null) addInstr(s, new NonlocalReturnInstr(retVal, sm.getName()));
        } else {
            retVal = processEnsureRescueBlocks(s, retVal);
            addInstr(s, new ReturnInstr(retVal));
        }

        // The value of the return itself in the containing expression can never be used because of control-flow reasons.
        // The expression that uses this result can never be executed beyond the return and hence the value itself is just
        // a placeholder operand.
        return U_NIL;
    }

    public IREvalScript buildEvalRoot(StaticScope staticScope, IRScope containingScope, String file, int lineNumber, RootNode rootNode, EvalType evalType) {
        // Top-level script!
        IREvalScript script;

        if (evalType == EvalType.BINDING_EVAL) {
            script = new IRBindingEvalScript(manager, containingScope, file, lineNumber, staticScope, evalType);
        } else {
            script = new IREvalScript(manager, containingScope, file, lineNumber, staticScope, evalType);
        }

        // We link IRScope to StaticScope because we may add additional variables (like %block).  During execution
        // we end up growing dynamicscope potentially based on any changes made.
        staticScope.setIRScope(script);

        // Debug info: record line number
        addInstr(script, new LineNumberInstr(lineNumber));

        // Set %current_scope = <current-scope>
        // Set %current_module = <current-module>
        addInstr(script, new CopyInstr(script.getCurrentScopeVariable(), CURRENT_SCOPE[0]));
        addInstr(script, new CopyInstr(script.getCurrentModuleVariable(), SCOPE_MODULE[0]));
        // Build IR for the tree and return the result of the expression tree
        Operand rval = rootNode.getBodyNode() == null ? manager.getNil() : build(rootNode.getBodyNode(), script);
        addInstr(script, new ReturnInstr(rval));

        return script;
    }

    public IRScriptBody buildRoot(RootNode rootNode) {
        String file = rootNode.getPosition().getFile();
        StaticScope staticScope = rootNode.getStaticScope();

        // Top-level script!
        IRScriptBody script = new IRScriptBody(manager, file, staticScope);

        // Prepare all implicit state (self, frame block, etc)
        prepareImplicitState(script);

        // Set %current_scope = <current-scope>
        // Set %current_module = <current-module>
        addInstr(script, new CopyInstr(script.getCurrentScopeVariable(), CURRENT_SCOPE[0]));
        addInstr(script, new CopyInstr(script.getCurrentModuleVariable(), SCOPE_MODULE[0]));

        // Build IR for the tree and return the result of the expression tree
        addInstr(script, new ReturnInstr(build(rootNode.getBodyNode(), script)));

        return script;
    }

    public Operand buildSelf(IRScope s) {
        return s.getSelf();
    }

    public Operand buildSplat(SplatNode splatNode, IRScope s) {
        Variable res = s.createTemporaryVariable();
        addInstr(s, new BuildSplatInstr(res, build(splatNode.getValue(), s)));
        return res;
    }

    public Operand buildStr(StrNode strNode, IRScope s) {
        return copyAndReturnValue(s, new StringLiteral(strNode.getValue(), strNode.getCodeRange()));
    }

    private Operand buildSuperInstr(IRScope s, Operand block, Operand[] args) {
        CallInstr superInstr;
        Variable ret = s.createTemporaryVariable();
        if ((s instanceof IRMethod) && (s.getLexicalParent() instanceof IRClassBody)) {
            IRMethod m = (IRMethod)s;
            if (m.isInstanceMethod) {
                superInstr = new InstanceSuperInstr(ret, s.getCurrentModuleVariable(), s.getName(), args, block);
            } else {
                superInstr = new ClassSuperInstr(ret, s.getCurrentModuleVariable(), s.getName(), args, block);
            }
        } else {
            // We dont always know the method name we are going to be invoking if the super occurs in a closure.
            // This is because the super can be part of a block that will be used by 'define_method' to define
            // a new method.  In that case, the method called by super will be determined by the 'name' argument
            // to 'define_method'.
            superInstr = new UnresolvedSuperInstr(ret, s.getSelf(), args, block);
        }
        receiveBreakException(s, block, superInstr);
        return ret;
    }

    public Operand buildSuper(SuperNode superNode, IRScope s) {
        if (s.isModuleBody()) return buildSuperInScriptBody(s);

        Operand[] args = setupCallArgs(superNode.getArgsNode(), s);
        Operand block = setupCallClosure(superNode.getIterNode(), s);
        if (block == null) block = s.getYieldClosureVariable();
        return buildSuperInstr(s, block, args);
    }

    private Operand buildSuperInScriptBody(IRScope s) {
        return addResultInstr(s, new UnresolvedSuperInstr(s.createTemporaryVariable(), s.getSelf(), NO_ARGS, null));
    }

    public Operand buildSValue(SValueNode node, IRScope s) {
        // SSS FIXME: Required? Verify with Tom/Charlie
        return copyAndReturnValue(s, new SValue(build(node.getValue(), s)));
    }

    public Operand buildSymbol(SymbolNode node) {
        // SSS: Since symbols are interned objects, no need to copyAndReturnValue(...)
        return new Symbol(node.getName(), node.getEncoding());
    }

    public Operand buildTrue() {
        return manager.getTrue();
    }

    public Operand buildUndef(Node node, IRScope s) {
        Operand methName = build(((UndefNode) node).getName(), s);
        return addResultInstr(s, new UndefMethodInstr(s.createTemporaryVariable(), methName));
    }

    private Operand buildConditionalLoop(IRScope s, Node conditionNode,
            Node bodyNode, boolean isWhile, boolean isLoopHeadCondition) {
        if (isLoopHeadCondition &&
                ((isWhile && conditionNode.getNodeType().alwaysFalse()) ||
                (!isWhile && conditionNode.getNodeType().alwaysTrue()))) {
            // we won't enter the loop -- just build the condition node
            build(conditionNode, s);
            return manager.getNil();
        } else {
            IRLoop loop = new IRLoop(s, getCurrentLoop());
            Variable loopResult = loop.loopResult;
            Label setupResultLabel = s.getNewLabel();

            // Push new loop
            loopStack.push(loop);

            // End of iteration jumps here
            addInstr(s, new LabelInstr(loop.loopStartLabel));
            if (isLoopHeadCondition) {
                Operand cv = build(conditionNode, s);
                addInstr(s, BEQInstr.create(cv, isWhile ? manager.getFalse() : manager.getTrue(), setupResultLabel));
            }

            // Redo jumps here
            addInstr(s, new LabelInstr(loop.iterStartLabel));

            // Thread poll at start of iteration -- ensures that redos and nexts run one thread-poll per iteration
            addInstr(s, new ThreadPollInstr(true));

            // Build body
            if (bodyNode != null) build(bodyNode, s);

            // Next jumps here
            addInstr(s, new LabelInstr(loop.iterEndLabel));
            if (isLoopHeadCondition) {
                addInstr(s, new JumpInstr(loop.loopStartLabel));
            } else {
                Operand cv = build(conditionNode, s);
                addInstr(s, BEQInstr.create(cv, isWhile ? manager.getTrue() : manager.getFalse(), loop.iterStartLabel));
            }

            // Loop result -- nil always
            addInstr(s, new LabelInstr(setupResultLabel));
            addInstr(s, new CopyInstr(loopResult, manager.getNil()));

            // Loop end -- breaks jump here bypassing the result set up above
            addInstr(s, new LabelInstr(loop.loopEndLabel));

            // Done with loop
            loopStack.pop();

            return loopResult;
        }
    }

    public Operand buildUntil(final UntilNode untilNode, IRScope s) {
        return buildConditionalLoop(s, untilNode.getConditionNode(), untilNode.getBodyNode(), false, untilNode.evaluateAtStart());
    }

    public Operand buildVAlias(VAliasNode valiasNode, IRScope s) {
        addInstr(s, new GVarAliasInstr(new StringLiteral(valiasNode.getNewName()), new StringLiteral(valiasNode.getOldName())));

        return manager.getNil();
    }

    public Operand buildVCall(VCallNode node, IRScope s) {
        return addResultInstr(s, CallInstr.create(CallType.VARIABLE, s.createTemporaryVariable(),
                node.getName(), s.getSelf(), NO_ARGS, null));
    }

    public Operand buildWhile(final WhileNode whileNode, IRScope s) {
        return buildConditionalLoop(s, whileNode.getConditionNode(), whileNode.getBodyNode(), true, whileNode.evaluateAtStart());
    }

    public Operand buildXStr(XStrNode node, IRScope s) {
        Variable res = s.createTemporaryVariable();
        addInstr(s, new BacktickInstr(res, new StringLiteral(node.getValue())));
        return res;
    }

    public Operand buildYield(YieldNode node, IRScope s) {
        boolean unwrap = true;
        Node argNode = node.getArgsNode();
        // Get rid of one level of array wrapping
        if (argNode != null && (argNode instanceof ArrayNode) && ((ArrayNode)argNode).size() == 1) {
            argNode = ((ArrayNode)argNode).getLast();
            unwrap = false;
        }

        Variable ret = s.createTemporaryVariable();
        addInstr(s, new YieldInstr(ret, s.getYieldClosureVariable(), build(argNode, s), unwrap));
        return ret;
    }

    public Operand buildZArray(IRScope s) {
       return copyAndReturnValue(s, new Array());
    }

    private Operand buildZSuperIfNest(final IRScope s, final Operand block) {
        // If we are in a block, we cannot make any assumptions about what args
        // the super instr is going to get -- if there were no 'define_method'
        // for defining methods, we could guarantee that the super is going to
        // receive args from the nearest method the block is embedded in.  But,
        // in the presence of 'define_method' (and eval and aliasing), all bets
        // are off because, any of the intervening block scopes could be a method
        // via a define_method call.
        //
        // Instead, we can actually collect all arguments of all scopes from here
        // till the nearest method scope and select the right set at runtime based
        // on which one happened to be a method scope. This has the additional
        // advantage of making explicit all used arguments.
        CodeBlock zsuperBuilder = new CodeBlock() {
            public Operand run() {
                Variable scopeDepth = s.createTemporaryVariable();
                addInstr(s, new ArgScopeDepthInstr(scopeDepth));

                Label allDoneLabel = s.getNewLabel();

                IRScope superScope = s;
                int depthFromSuper = 0;
                Label next = null;

                // Loop and generate a block for each possible value of depthFromSuper
                Variable zsuperResult = s.createTemporaryVariable();
                while (superScope instanceof IRClosure) {
                    // Generate the next set of instructions
                    if (next != null) addInstr(s, new LabelInstr(next));
                    next = s.getNewLabel();
                    addInstr(s, BNEInstr.create(new Fixnum(depthFromSuper), scopeDepth, next));
                    Operand[] args = adjustVariableDepth(((IRClosure)superScope).getBlockArgs(), depthFromSuper);
                    addInstr(s, new ZSuperInstr(zsuperResult, s.getSelf(), args,  block));
                    addInstr(s, new JumpInstr(allDoneLabel));

                    // Move on
                    superScope = superScope.getLexicalParent();
                    depthFromSuper++;
                }

                addInstr(s, new LabelInstr(next));

                // If we hit a method, this is known to always succeed
                if (superScope instanceof IRMethod) {
                    Operand[] args = adjustVariableDepth(((IRMethod)superScope).getCallArgs(), depthFromSuper);
                    addInstr(s, new ZSuperInstr(zsuperResult, s.getSelf(), args, block));
                } //else {
                // FIXME: Do or don't ... there is no try
                    /* Control should never get here in the runtime */
                    /* Should we add an exception throw here just in case? */
                //}

                addInstr(s, new LabelInstr(allDoneLabel));
                return zsuperResult;
            }
        };

        return receiveBreakException(s, block, zsuperBuilder);
    }

    public Operand buildZSuper(ZSuperNode zsuperNode, IRScope s) {
        if (s.isModuleBody()) return buildSuperInScriptBody(s);

        Operand block = setupCallClosure(zsuperNode.getIterNode(), s);
        if (block == null) block = s.getYieldClosureVariable();

        // Enebo:ZSuper in for (or nested for) can be statically resolved like method but it needs to fixup depth.
        if (s instanceof IRMethod) {
            return buildSuperInstr(s, block, ((IRMethod)s).getCallArgs());
        } else {
            return buildZSuperIfNest(s, block);
        }
    }

    /*
     * Adjust all argument operands by changing their depths to reflect how far they are from
     * super.  This fixup is only currently happening in supers nested in closures.
     */
    private Operand[] adjustVariableDepth(Operand[] args, int depthFromSuper) {
        Operand[] newArgs = new Operand[args.length];

        for (int i = 0; i < args.length; i++) {
            // Because of keyword args, we can have a keyword-arg hash in the call args.
            if (args[i] instanceof Hash) {
                newArgs[i] = ((Hash) args[i]).cloneForLVarDepth(depthFromSuper);
            } else {
                newArgs[i] = ((DepthCloneable) args[i]).cloneForDepth(depthFromSuper);
            }
        }

        return newArgs;
    }

    private Operand buildModuleOrClassBody(IRScope parent, Variable moduleVar, IRModuleBody body, Node bodyNode, int linenumber, Operand block) {
        Variable processBodyResult = addResultInstr(parent, new ProcessModuleBodyInstr(parent.createTemporaryVariable(), moduleVar, block));
        IRBuilder bodyBuilder = newIRBuilder(manager);

        if (RubyInstanceConfig.FULL_TRACE_ENABLED) {
            bodyBuilder.addInstr(body, new TraceInstr(RubyEvent.CLASS, null, body.getFileName(), linenumber));
        }

        // Prepare all implicit state (self, frame block, etc)
        bodyBuilder.prepareImplicitState(body);

        bodyBuilder.addInstr(body, new CopyInstr(body.getCurrentScopeVariable(), CURRENT_SCOPE[0])); // %scope
        bodyBuilder.addInstr(body, new CopyInstr(body.getCurrentModuleVariable(), SCOPE_MODULE[0])); // %module
        // Create a new nested builder to ensure this gets its own IR builder state
        Operand bodyReturnValue = bodyBuilder.build(bodyNode, body);

        if (RubyInstanceConfig.FULL_TRACE_ENABLED) {
            bodyBuilder.addInstr(body, new TraceInstr(RubyEvent.END, null, body.getFileName(), -1));
        }

        bodyBuilder.addInstr(body, new ReturnInstr(bodyReturnValue));

        return processBodyResult;
    }

    private String methodNameFor(IRScope s) {
        IRScope method = s.getNearestMethod();

        return method == null ? null : method.getName();
    }
}
