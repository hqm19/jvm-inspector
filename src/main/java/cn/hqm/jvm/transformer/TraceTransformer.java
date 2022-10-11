package cn.hqm.jvm.transformer;

import static org.objectweb.asm.ClassWriter.COMPUTE_FRAMES;
import static org.objectweb.asm.ClassWriter.COMPUTE_MAXS;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import cn.hqm.jvm.Log;
import cn.hqm.jvm.asm.MethodInterceptor;


/**
 * 字节码编织
 * 
 * @author linxuan
 *
 */
public class TraceTransformer implements ClassFileTransformer {
    private Set<String> methods;
    private Set<Class<?>> classes;
    private MethodInterceptor methodInterceptor;


    public TraceTransformer(Set<String> methods, Set<Class<?>> classes) {
        this.methods = methods;
        this.classes = classes;
    }


    @Override
    public byte[] transform(final ClassLoader loader, String className, Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
        Log.warn("transform " + className);
        if (!classes.contains(classBeingRedefined)) {
            // 目标类，不在要替换的列表中，返回 null 表示不改变
            return null;
        }

        // COMPUTE_MAXS 表示让asm自动计算局部变量与操作数栈部分的大小； 必须调用 visitMaxs；只使用 COMPUTE_MAXS，还需要自行计算帧
        // COMPUTE_FRAMES 表示让asm自动计算帧、局部变量与操作数栈大小，不再需要调用visitFrame；必须调用 visitMaxs ，其参数被忽略重新计算
        ClassWriter cw = new ClassWriter(COMPUTE_FRAMES | COMPUTE_MAXS) {
            // 为了自动计算帧，有时需要计算两个给定类的公共超类，默认的 getCommonSuperClass 会将2个类加载到jvm中用反射api计算。
            // 如果正在生成几个相互引用的类，这块就可能会出问题（被引用的类尚未存在）。重写 getCommonSuperClass 就是为了解决这一问题
            // todo 貌似在这个场景中，不需要这个额外的重写 ？
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                Class<?> c, d;
                try {
                    if (loader != null) {
                        c = loader.loadClass(type1.replace('/', '.'));
                        d = loader.loadClass(type2.replace('/', '.'));
                    }
                    else {
                        c = Class.forName(type1.replace('/', '.'));
                        d = Class.forName(type2.replace('/', '.'));
                    }
                }
                catch (Exception e) {
                    throw new RuntimeException(e.toString());
                }
                if (c.isAssignableFrom(d)) {
                    return type1;
                }
                if (d.isAssignableFrom(c)) {
                    return type2;
                }
                if (c.isInterface() || d.isInterface()) {
                    return "java/lang/Object";
                }
                else {
                    do {
                        c = c.getSuperclass();
                    } while (!c.isAssignableFrom(d));
                    return c.getName().replace('.', '/');
                }
            }
        };

        // ClassWriter 按 ClassReader 对原字节码 visit 到的顺序写新字节。默认生成和原来完全相同的字节码。其中特定环节被替换后，就完成了字节码植入。
        ClassReader cr = new ClassReader(classfileBuffer);
        methodInterceptor = new MethodInterceptor(cw, className, methods);
        try {
            // todo 这里貌似用 SKIP_FRAMES 更合适？
            cr.accept(methodInterceptor, ClassReader.EXPAND_FRAMES);
            //cr.accept(cw, ClassReader.EXPAND_FRAMES);
        }
        catch (Exception e) {
            Log.warn("accept failed. className:" + className, e);
            return null;
        }
        byte[] bytes = cw.toByteArray();
        //dump2file(bytes, "TraceTransformer.x.class");
        /*
        try {
            Class<?> c = loader.loadClass("cn.hqm.jvm.asm.MethodAdvice");
            Log.warn("MethodAdvice loaded by the ClassLoader of " + className);
        }
        catch (ClassNotFoundException e) {
            Log.warn("MethodAdvice can't load by the ClassLoader of " + className, e);
        }
        */
        return bytes;
    }


    public static void dump2file(byte[] bytes, String fileName) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(new File(fileName));
            out.write(bytes);
            out.flush();
            out.close();
        }
        catch (Exception e) {
            Log.warn("", e);
        }
        finally {
            if (out != null) {
                try {
                    out.close();
                }
                catch (IOException e) {
                }
            }
        }
    }
}
