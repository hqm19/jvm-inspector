package cn.hqm.jvm;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import cn.hqm.jvm.asm.MethodAdvice;
import cn.hqm.jvm.cmd.Option;
import cn.hqm.jvm.transformer.TraceTransformer;


/**
 * 
 * @author linxuan
 *
 */
public class MethodSpy {
    private static final char k_t = 't';
    private static final char k_l = 'l';
    private static final char k_s = 's';
    private static final char k_d = 'd';
    private static Map<Character, Option> options = new LinkedHashMap<Character, Option>();
    private static String usage;
    static {
        put0(new Option(k_t, true, "time out in milliseconds. defaut 15000", null));
        put0(new Option(k_l, true, "max invoke count. defaut 2", null));
        put0(new Option('c', false, "show class name. default on", MethodAdvice.CLASS));
        put0(new Option('a', false, "show argument vlaues. default on.", MethodAdvice.ARGUMENTS));
        put0(new Option('r', false, "show return value. default on", MethodAdvice.RESULT));
        put0(new Option('e', false, "show time elapsed in nano. default on", MethodAdvice.TIMEUSE));
        put0(new Option('m', false, "show method description. default off", MethodAdvice.DESCRIPTOR));
        put0(new Option('h', false, "show thread name. default off", MethodAdvice.THREAD));
        put0(new Option(k_s, true, "show stack trace. default skip depth 0", MethodAdvice.STACK));
        put0(new Option('T', false, "show this Object. default off", MethodAdvice.THIS));
        put0(new Option('L', false, "show the ClassLoader of the inspect class. default off", MethodAdvice.CLASS_LOADER));
        put0(new Option(k_d, false, "dump details to file. default off", null));

        StringBuilder sb = new StringBuilder("trace qualified-mehtod-name [-t timout] [-l count] [-caremhsTLd]\n");
        for (Map.Entry<Character, Option> entry : options.entrySet()) {
            Option o = entry.getValue();
            sb.append("  -").append(o.key).append(" ").append(o.msg).append("\n");
        }
        usage = sb.toString();
    }


    private static void put0(Option o) {
        options.put(o.key, o);
    }


    public static String usage() {
        return usage;
    }


    /**
     * 方法调用跟踪，参数解析；支持两种顺序：
     *  trace com.taobao.tae.grid.tbml.TBMLfilterHelper.filterHtml() -l 1 -d
     *  trace  -l 1 -d com.taobao.tae.grid.tbml.TBMLfilterHelper.filterHtml()
     * 效果相同
     * 
     * @param tokens 如：trace com.taobao.tae.grid.tbml.TBMLfilterHelper.filterHtml() -l 1 -d 空格分割出的数组
     * @param expressions
     * @param params
     * @return
     */
    private static String dealParam(String[] tokens, List<String> expressions, Map<Option, String> params) {
        Option lastOption = null;
        for (int i = 1; i < tokens.length; i++) { //第一个tokens是trace ，跳过
            String token = tokens[i];
            if (token.charAt(0) == '-') {
                // 第一个字符是减号，说明是选项键，如 -l， -d 等
                int n = token.length();
                if (n < 2) {
                    // 只有一个单独的减号不合法
                    return usage;
                }

                //负数的处理
                char c2 = token.charAt(1);
                if ('0' <= c2 && c2 <= '9') {
                    // 减号后面紧跟着数字，和数字一起构成负数，作为选项值，放入上一个识别出的选项中
                    if (lastOption != null) {
                        params.put(lastOption, token);
                        lastOption = null;
                    }
                    continue;
                }

                lastOption = null;
                for (int j = 1; j < n; j++) {
                    char c = token.charAt(j);  // 取减号后面的选项键(目前只有单字符选项)
                    Option o = options.get(c); // 根据选项字符，从预置选项 map 中，获取 Option 对象
                    if (o != null) {           // 选项键有对应的 Option，则将 Option 对象 put 到参数列表 params 中
                        params.put(o, null); 
                        if (o.hasvalue) {
                            // 有些选项不需要额外值(本质上只有两个取值)，有些需要额外值。需要额外值的，将其设置为 lastOption
                            lastOption = o;
                        }
                    }
                }
            }
            else if (lastOption != null) {
                // 非选项键(不以减号好开头)，这时如果 lastOption 存在，
                // 说明上一个 token 对应的选项是需要带值的，那么就把当前 token 设置为其选项值。
                params.put(lastOption, token);
                lastOption = null; // 重置 lastOption 为空
            }
            else {
                // 非选项键(不以减号好开头)，同时 lastOption 为空，说明也不是选项值，则被认为是表达式的一部分
                expressions.add(token);
            }
        }
        return null;
    }

    // 方法调用跟踪
    public static String trace(File outputDir, Instrumentation instrumentation, String[] tokens)
            throws ClassNotFoundException {
        Set<Class<?>> classes = new HashSet<Class<?>>();
        Set<String> methods = new HashSet<String>();

        Map<Option, String> params = new HashMap<Option, String>();
        List<String> expressions = new ArrayList<String>();
        String errormsg = dealParam(tokens, expressions, params);
        if (errormsg != null) {
            return errormsg;
        }
        ClassLoader loader = null;

        // expressions 形如 com.taobao.tae.grid.tbml.TBMLfilterHelper.filterHtml() 等多个
        for (String expr : expressions) {
            Class<?> clazz = InspectAgent.inspector.getClazzByExepression(expr);
            if (clazz == null) {
                return "Class not found. expr:" + expr;
            }
            if (!expr.startsWith(clazz.getName())) {
                throw new IllegalStateException(expr + ",but class:" + clazz);
            }
            if (cn.hqm.jvm.asm.MethodAdvice.usedClasses.contains(clazz)) {
                return "Class used by tracing self could not be traced: " + clazz;
            }
            loader = clazz.getClassLoader(); //TODO 用另一种方式
            String method = expr.substring(clazz.getName().length()); 

            // 类名之后，紧接着方法命名，如 some.ClassX.foo() 或 some.ClassX.foo
            int index1 = method.indexOf(".");
            int index2 = method.indexOf("(");
            if (index2 != -1) {
                method = method.substring(index1 + 1, index2).trim();
            }
            else {
                method = method.substring(index1 + 1).trim();
            }

            // 从类定义中，遍历方法定义，与截取到的方法名 method 匹配的方法，加入到待 trace 集合
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(method)) {
                    methods.add(m.getName());
                    classes.add(m.getDeclaringClass());
                }
            }
            if (method.equals(clazz.getSimpleName())) {
                //如果是构造函数，也加入；构造函数在字节码层面的名字都是 <init>
                methods.add("<init>");
                classes.add(clazz);
            }
        }
        if (classes.isEmpty() || methods.isEmpty()) {
            return "No methods found.";
        }

        //设置 MethodAdvice 参数及 timeout
        long timeoutMS = 15000L;
        try {
            MethodAdvice.loader = loader;  //设置 MethodAdvice 的全局 loader
            Set<String> displays = new HashSet<String>();
            for (Map.Entry<Option, String> entry : params.entrySet()) {
                Option o = entry.getKey();
                if (o.name != null) {
                    // 参数选项中有 name 属性，则把 name 加入到 trace 要显示的字段列表中
                    displays.add(o.name);
                }
            }
            if (!displays.isEmpty()) {
                MethodAdvice.displays = displays; // 设置 trace 要显示的字段列表
            }
            String stackDepth = params.get(options.get(k_s));
            if (stackDepth != null) {
                // 设置要截取的栈深度
                MethodAdvice.stackDepth = Integer.valueOf(stackDepth);
            }
            String timeout = params.get(options.get(k_t));
            if (timeout != null) {
                timeoutMS = Long.valueOf(timeout);
            }
            String targetInvokeCount = params.get(options.get(k_l));
            if (targetInvokeCount != null) {
                // 设置要 trace 的次数
                MethodAdvice.targetCount = Integer.valueOf(targetInvokeCount);
            }
        }
        catch (Exception e) {
            MethodAdvice.reset();
            return e.getMessage();
        }

        File detailFile = null;
        if (params.containsKey(options.get(k_d))) {
            // 如果加了 -d 参数，则打开输出文件的 fileWriter
            detailFile = InspectAgent.openEchoToFile(outputDir);
        }

        //boolean isInterrupted = false;
        TraceTransformer ttf = new TraceTransformer(methods, classes);
        try {
            try {
                MethodAdvice.count.set(0);
                // 注册自定义的 ClassFileTransformer 对象 ttf
                instrumentation.addTransformer(ttf, true);
                // 做字节码替换，一次替换多个类
                instrumentation.retransformClasses(classes.toArray(new Class[classes.size()]));
                synchronized (MethodAdvice.counterLock) {
                    try {
                        MethodAdvice.counterLock.wait(timeoutMS);
                    }
                    catch (InterruptedException e) {
                        //isInterrupted = true;
                        Thread.currentThread().interrupt();
                    }
                }
            }
            finally {
                // 移除自定义的 ClassFileTransformer 对象 ttf
                instrumentation.removeTransformer(ttf);
                // 重新 transform 回原来的状态
                instrumentation.retransformClasses(classes.toArray(new Class[classes.size()]));
            }
        }
        catch (UnmodifiableClassException e) {
            throw new RuntimeException("trace failed", e);
        }
        catch (Exception e) {
            throw new RuntimeException("trace failed", e);
        }
        finally {
            MethodAdvice.reset();
            if (params.containsKey(options.get(k_d))) {
                InspectAgent.closeEchoToFile();
                return Common.DONE + " detail info dumped to " + detailFile.getAbsolutePath();
            }
        }
        return Common.DONE;
    }
}
