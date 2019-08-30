package dev.openrs2.deob.transform;

import com.google.common.collect.ImmutableSet;
import dev.openrs2.asm.InsnMatcher;
import dev.openrs2.asm.InsnNodeUtils;
import dev.openrs2.asm.classpath.ClassPath;
import dev.openrs2.asm.classpath.Library;
import dev.openrs2.asm.transform.Transformer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BitShiftTransformer extends Transformer {
	private static final Logger logger = LoggerFactory.getLogger(BitShiftTransformer.class);

	private static final InsnMatcher CONST_SHIFT_MATCHER = InsnMatcher.compile("(ICONST | BIPUSH | SIPUSH | LDC) (ISHL | ISHR | IUSHR | LSHL | LSHR | LUSHR)");
	private static final ImmutableSet<Integer> LONG_SHIFTS = ImmutableSet.of(Opcodes.LSHL, Opcodes.LSHR, Opcodes.LUSHR);

	private int simplified;

	@Override
	public void preTransform(ClassPath classPath) {
		simplified = 0;
	}

	@Override
	public boolean transformCode(ClassPath classPath, Library library, ClassNode clazz, MethodNode method) {
		CONST_SHIFT_MATCHER.match(method).forEach(match -> {
			var push = match.get(0);
			var bits = InsnNodeUtils.getIntConstant(push);

			var opcode = match.get(1).getOpcode();
			var simplifiedBits = bits & (LONG_SHIFTS.contains(opcode) ? 63 : 31);

			if (bits != simplifiedBits) {
				method.instructions.set(push, InsnNodeUtils.createIntConstant(simplifiedBits));
				simplified++;
			}
		});

		return false;
	}

	@Override
	public void postTransform(ClassPath classPath) {
		logger.info("Simplified {} bit shifts", simplified);
	}
}
