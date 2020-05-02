package dev.openrs2.deob.transform

import com.github.michaelbull.logging.InlineLogger
import dev.openrs2.asm.MemberRef
import dev.openrs2.asm.classpath.ClassPath
import dev.openrs2.asm.classpath.Library
import dev.openrs2.asm.copy
import dev.openrs2.asm.deleteExpression
import dev.openrs2.asm.hasCode
import dev.openrs2.asm.intConstant
import dev.openrs2.asm.isPure
import dev.openrs2.asm.nextReal
import dev.openrs2.asm.replaceExpression
import dev.openrs2.asm.stackMetadata
import dev.openrs2.asm.toAbstractInsnNode
import dev.openrs2.asm.transform.Transformer
import dev.openrs2.deob.ArgRef
import dev.openrs2.deob.analysis.IntBranch
import dev.openrs2.deob.analysis.IntBranchResult.ALWAYS_TAKEN
import dev.openrs2.deob.analysis.IntBranchResult.NEVER_TAKEN
import dev.openrs2.deob.analysis.IntInterpreter
import dev.openrs2.deob.analysis.IntValueSet
import dev.openrs2.deob.remap.TypedRemapper
import dev.openrs2.util.collect.DisjointSet
import dev.openrs2.util.collect.removeFirstOrNull
import org.objectweb.asm.Opcodes.GOTO
import org.objectweb.asm.Opcodes.IFEQ
import org.objectweb.asm.Opcodes.IFGE
import org.objectweb.asm.Opcodes.IFGT
import org.objectweb.asm.Opcodes.IFLE
import org.objectweb.asm.Opcodes.IFLT
import org.objectweb.asm.Opcodes.IFNE
import org.objectweb.asm.Opcodes.IF_ICMPEQ
import org.objectweb.asm.Opcodes.IF_ICMPGE
import org.objectweb.asm.Opcodes.IF_ICMPGT
import org.objectweb.asm.Opcodes.IF_ICMPLE
import org.objectweb.asm.Opcodes.IF_ICMPLT
import org.objectweb.asm.Opcodes.IF_ICMPNE
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.analysis.Analyzer
import javax.inject.Singleton

@Singleton
class ConstantArgTransformer : Transformer() {
    private val pendingMethods = LinkedHashSet<MemberRef>()
    private val arglessMethods = mutableSetOf<DisjointSet.Partition<MemberRef>>()
    private val argValues = mutableMapOf<ArgRef, IntValueSet>()
    private lateinit var inheritedMethodSets: DisjointSet<MemberRef>
    private var branchesSimplified = 0
    private var constantsInlined = 0

    override fun preTransform(classPath: ClassPath) {
        pendingMethods.clear()
        arglessMethods.clear()
        argValues.clear()
        inheritedMethodSets = classPath.createInheritedMethodSets()
        branchesSimplified = 0
        constantsInlined = 0

        queueEntryPoints(classPath)

        while (true) {
            val method = pendingMethods.removeFirstOrNull() ?: break
            analyzeMethod(classPath, method)
        }
    }

    private fun queueEntryPoints(classPath: ClassPath) {
        for (partition in inheritedMethodSets) {
            /*
             * The set of non-renamable methods roughly matches up with the
             * methods we want to consider as entry points. It includes methods
             * which we override, which may be called by the standard library),
             * the main() method (called by the JVM), providesignlink() (called
             * with reflection) and <clinit> (called by the JVM).
             *
             * It isn't perfect - it counts every <init> method as an entry
             * point, but strictly speaking we only need to count <init>
             * methods invoked with reflection as entry points (like
             * VisibilityTransformer). However, it makes no difference in this
             * case, as the obfuscator does not add dummy constant arguments to
             * constructors.
             *
             * It also counts native methods as an entry point. This isn't
             * problematic as they don't have an InsnList, so we skip them.
             */
            if (!TypedRemapper.isMethodRenamable(classPath, partition)) {
                pendingMethods.addAll(partition)
            }
        }
    }

    private fun analyzeMethod(classPath: ClassPath, ref: MemberRef) {
        // find ClassNode/MethodNode
        val owner = classPath.getClassNode(ref.owner) ?: return
        val originalMethod = owner.methods.singleOrNull { it.name == ref.name && it.desc == ref.desc } ?: return
        if (!originalMethod.hasCode) {
            return
        }

        /*
         * Clone the method - we don't want to mutate it permanently until the
         * final pass, as we might discover more routes through the call graph
         * later which reduce the number of branches we can simplify.
         */
        val method = originalMethod.copy()

        // find existing constant arguments
        val args = getArgs(ref)

        // simplify branches
        simplifyBranches(owner, method, args)

        /*
         * Record new constant arguments in method calls. This re-runs the
         * analyzer rather than re-using the frames from simplifyBranches. This
         * ensures we ignore branches that always evaluate to false, preventing
         * us from recording constant arguments found in dummy calls (which
         * would prevent us from removing further dummy calls/branches).
         */
        addArgValues(owner, method, args)
    }

    private fun getArgs(ref: MemberRef): Array<IntValueSet> {
        val partition = inheritedMethodSets[ref]!!
        val size = Type.getArgumentTypes(ref.desc).sumBy { it.size }
        return Array(size) { i -> argValues[ArgRef(partition, i)] ?: IntValueSet.Unknown }
    }

    private fun addArgValues(owner: ClassNode, method: MethodNode, args: Array<IntValueSet>) {
        val analyzer = Analyzer(IntInterpreter(args))
        val frames = analyzer.analyze(owner.name, method)
        for ((i, frame) in frames.withIndex()) {
            if (frame == null) {
                continue
            }

            val insn = method.instructions[i]
            if (insn !is MethodInsnNode) {
                continue
            }

            val invokedMethod = inheritedMethodSets[MemberRef(insn)] ?: continue
            val size = Type.getArgumentTypes(insn.desc).size

            var index = 0
            for (j in 0 until size) {
                val value = frame.getStack(frame.stackSize - size + j)
                if (addArgValues(ArgRef(invokedMethod, index), value.set)) {
                    pendingMethods.addAll(invokedMethod)
                }
                index += value.size
            }

            if (size == 0 && arglessMethods.add(invokedMethod)) {
                pendingMethods.addAll(invokedMethod)
            }
        }
    }

    private fun addArgValues(ref: ArgRef, value: IntValueSet): Boolean {
        val old = argValues[ref]

        val new = if (value.singleton != null) {
            if (old != null) {
                old union value
            } else {
                value
            }
        } else {
            IntValueSet.Unknown
        }
        argValues[ref] = new

        return old != new
    }

    private fun simplifyBranches(owner: ClassNode, method: MethodNode, args: Array<IntValueSet>): Int {
        val analyzer = Analyzer(IntInterpreter(args))
        val frames = analyzer.analyze(owner.name, method)

        val alwaysTakenBranches = mutableListOf<JumpInsnNode>()
        val neverTakenBranches = mutableListOf<JumpInsnNode>()

        frame@ for ((i, frame) in frames.withIndex()) {
            if (frame == null) {
                continue
            }

            val insn = method.instructions[i]
            if (insn !is JumpInsnNode) {
                continue
            }

            when (insn.opcode) {
                IFEQ, IFNE, IFLT, IFGE, IFGT, IFLE -> {
                    val value = frame.getStack(frame.stackSize - 1)
                    if (value.set !is IntValueSet.Constant) {
                        continue@frame
                    }

                    @Suppress("NON_EXHAUSTIVE_WHEN")
                    when (IntBranch.evaluateUnary(insn.opcode, value.set.values)) {
                        ALWAYS_TAKEN -> alwaysTakenBranches += insn
                        NEVER_TAKEN -> neverTakenBranches += insn
                    }
                }
                IF_ICMPEQ, IF_ICMPNE, IF_ICMPLT, IF_ICMPGE, IF_ICMPGT, IF_ICMPLE -> {
                    val value1 = frame.getStack(frame.stackSize - 2)
                    val value2 = frame.getStack(frame.stackSize - 1)
                    if (value1.set !is IntValueSet.Constant || value2.set !is IntValueSet.Constant) {
                        continue@frame
                    }

                    @Suppress("NON_EXHAUSTIVE_WHEN")
                    when (IntBranch.evaluateBinary(insn.opcode, value1.set.values, value2.set.values)) {
                        ALWAYS_TAKEN -> alwaysTakenBranches += insn
                        NEVER_TAKEN -> neverTakenBranches += insn
                    }
                }
            }
        }

        var simplified = 0

        for (insn in alwaysTakenBranches) {
            val replacement = JumpInsnNode(GOTO, insn.label)
            if (method.instructions.replaceExpression(insn, replacement, AbstractInsnNode::isPure)) {
                simplified++
            }
        }

        for (insn in neverTakenBranches) {
            if (method.instructions.deleteExpression(insn, AbstractInsnNode::isPure)) {
                simplified++
            }
        }

        return simplified
    }

    private fun inlineConstantArgs(clazz: ClassNode, method: MethodNode, args: Array<IntValueSet>): Int {
        val analyzer = Analyzer(IntInterpreter(args))
        val frames = analyzer.analyze(clazz.name, method)

        val constInsns = mutableMapOf<AbstractInsnNode, Int>()

        for ((i, frame) in frames.withIndex()) {
            if (frame == null) {
                continue
            }

            val insn = method.instructions[i]
            if (insn.intConstant != null) {
                // already constant
                continue
            } else if (!insn.isPure) {
                // can't replace instructions with a side effect
                continue
            } else if (insn.stackMetadata.pushes != 1) {
                // can't replace instructions pushing more than one result
                continue
            }

            // the value pushed by this instruction is held in the following frame
            val nextInsn = insn.nextReal ?: continue
            val nextInsnIndex = method.instructions.indexOf(nextInsn)
            val nextFrame = frames[nextInsnIndex]

            val value = nextFrame.getStack(nextFrame.stackSize - 1)
            val singleton = value.set.singleton
            if (singleton != null) {
                constInsns[insn] = singleton
            }
        }

        var inlined = 0

        for ((insn, value) in constInsns) {
            if (insn !in method.instructions) {
                continue
            }

            val replacement = value.toAbstractInsnNode()
            if (method.instructions.replaceExpression(insn, replacement, AbstractInsnNode::isPure)) {
                inlined++
            }
        }

        return inlined
    }

    override fun transformCode(classPath: ClassPath, library: Library, clazz: ClassNode, method: MethodNode): Boolean {
        val args = getArgs(MemberRef(clazz, method))
        branchesSimplified += simplifyBranches(clazz, method, args)
        constantsInlined += inlineConstantArgs(clazz, method, args)
        return false
    }

    override fun postTransform(classPath: ClassPath) {
        logger.info { "Simplified $branchesSimplified branches and inlined $constantsInlined constants" }
    }

    companion object {
        private val logger = InlineLogger()
    }
}
