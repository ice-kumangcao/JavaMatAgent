import java.lang.instrument.*;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.objectweb.asm.*;

public class MatAgent {
    // 记录创建但未释放的 Mat 对象
    private static final Map<Integer, String> activeMats = new ConcurrentHashMap<>();

    public static void premain(String agentArgs, Instrumentation inst) {
        System.out.println("[Agent] MatAgent Loaded!");
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                if (className.equals("org/opencv/core/Mat")) {  // 只修改 Mat 类
                    System.out.println("[Agent] Transforming Mat class...");
                    return modifyMatMethods(classfileBuffer);
                }
                return classfileBuffer;
            }
        });

        // 定期打印未释放的 Mat
        startMemoryMonitor();
    }

    private static void startMemoryMonitor() {
        Thread monitorThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000); // 每 5 秒检查一次
                    System.out.println("[Agent] Active Mat Count: " + activeMats.size());
                    if (!activeMats.isEmpty()) {
                        System.out.println("[Agent] Unreleased Mat objects: " + String.join("\n", new HashSet<>(activeMats.values())));
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    private static byte[] modifyMatMethods(byte[] classfileBuffer) {
        ClassReader reader = new ClassReader(classfileBuffer);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

                // 处理 Mat 构造方法 <init>
                if (name.equals("<init>")) {
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN) { // 在 return 前插入打印代码
                                printMatInfo(mv, "[Agent] Mat object created: ", true);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }

                // 处理 Mat.release() 方法
                if (name.equals("release") && descriptor.equals("()V")) {
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitCode() {
                            printMatInfo(mv, "[Agent] Mat object released: ", false);
                            super.visitCode();
                        }
                    };
                }
                return mv;
            }
        }, ClassReader.EXPAND_FRAMES);

        return writer.toByteArray();
    }

    private static void printMatInfo(MethodVisitor mv, String message, boolean isCreation) {
        // System.out.print(message);
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn(message);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "print",
                "(Ljava/lang/String;)V", false);

        // System.out.println(System.identityHashCode(this));
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "identityHashCode",
                "(Ljava/lang/Object;)I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 1); // 存储对象的 hash 值

        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                "(I)V", false);

        // 记录到 activeMats
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "MatAgent", isCreation ? "addMat" : "removeMat", "(I)V", false);

        // 打印调用堆栈
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Thread", "currentThread",
                "()Ljava/lang/Thread;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Thread", "getStackTrace",
                "()[Ljava/lang/StackTraceElement;", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/Arrays", "toString",
                "([Ljava/lang/Object;)Ljava/lang/String;", false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                "(Ljava/lang/String;)V", false);
    }

    // 记录 Mat 对象
    public static void addMat(int hashCode) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            sb.append(element.toString()).append("\n");
        }
        activeMats.put(hashCode, sb.toString());
    }

    // 移除 Mat 对象
    public static void removeMat(int hashCode) {
        activeMats.remove(hashCode);
    }
}