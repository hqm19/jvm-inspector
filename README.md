jvm-inspector
=============

# Java进程运行期窥探器
    1. classdump： 类加载情况分析展示，用于排查jar包冲突、类冲突、类版本冲突、
       NoClassDefFoundError、ClassNotFoundException 等等类加载相关问题的辅助工具。
    2. method trace 动态截获方法调用参数返回值
    3. 通过java表达式读写内存中的java对象


抛弃brace完全不影响运行期的理念：
1. 既然到了需要在线排查的地步，必然是已经出了问题。定位原因才是第一需要。
2. 互联网场景都是分布式集群化，搞挂一台机器没那么严重
3. 工具的开销，相对于正在跑的业务可以忽略
 
新版不再需要配置jvm启动参数，启动命令样例：

$ /opt/taobao/java/bin/java -Xbootclasspath/a:/opt/taobao/java/lib/tools.jar -jar jvm-inspect-2.0.jar 48475
其中jvm-inspect-2.0.jar为下载本工具获得的jar包路径；48475为jvm进程pid；

```
Dec 2, 2013 9:29:17 AM com.taobao.tae.jvm.Log warn
WARNING: attach to pid:48475
Dec 2, 2013 9:29:18 AM com.taobao.tae.jvm.Log warn
WARNING: successed attach to pid:48475,vm:sun.tools.attach.LinuxAttachProvider@771c8a71: 48475
Dec 2, 2013 9:29:18 AM com.taobao.tae.jvm.Log warn
WARNING: loadAgent from:/home/admin/taegrid/jvm-inspect-2.0.jar
Dec 2, 2013 9:29:19 AM com.taobao.tae.jvm.Log warn
WARNING: successed loadAgent from:/home/admin/taegrid/jvm-inspect-2.0.jar


inspect>
```

出现类似上面的信息说明attach进程成功；接下来可以用交互式命令进行窥探分析。

```
inspect>help
usage:
  quit        terminate the process.  退出
  help        display this infomation.
  classesdump dump all class informations to pid.classloaders.n.html
  dumpthreads dump all thread stacks to pid.threads.n.txt
  trace       display or output infomation of method invocaton.
     trace com.taobao.tae.grid.servlet.TaePhpServlet.render()
  get         execute a java-style expression and display the result.
     get com.taobao.tae.grid.config.TaeGridConfig.instance.isDisablePageCache()
  set         set a value to a reference specified by the java-style expression
     set com.taobao.tae.common.context.TaeContext.enableDotTime true
```

上面命令都支持tab键自动补全，下面分别介绍
 
1. get  <<java表达式>>  
读取java表达式表示的对象值

```
inspect>get com.taobao.tae.engine.php.PhpEngineInitializer._quercus._classDefMap.length
256
inspect>get com.taobao.tae.engine.php.PhpEngineInitializer._quercus._classDefMap[0]
JavaClassDef@1898143411[Exception]
 
inspect>get com.taobao.tae.engine.php.PhpEngineInitializer._quercus._modules.entrySet().iterator().next()
com.caucho.quercus.lib.HttpModule=ModuleInfo[com.caucho.quercus.lib.HttpModule]
```

内部用反射实现。支持读取父类的非public字段。支持常见原子类型的方法参数，以及直接将另一个表达式作为方法的参数（后面会详述）。
注意这种模式必须找到一个static的入口，取得一个对象；之后从这个对象访问的任意可关联的路径就不需要是static了。
 
 
2. set <<java表达式>> value

```
java表达式表示的对象进行赋值。java表达式的规则和get相同。只是结果必须是一个对象或类的字段
这个操作会直接改变内存中的值。
 
inspect>get com.taobao.tae.common.context.TaeContext.enableDotTime0         
false
inspect>set com.taobao.tae.common.context.TaeContext.enableDotTime0 true
old value:false
inspect>get com.taobao.tae.common.context.TaeContext.enableDotTime0     
true
```

改完了记得改回来哦~~
 
3. 直接执行表达式
get set 命令的核心是表达式执行。 只是get额外返回表达式的结果。set额外作一次赋值。有些时候即非get也非set，那就直接执行好了。请看下面的例子

```
inspect>org.apache.commons.logging.LogFactory.getLog("com.taobao.tae").getLogger().getLevel()
DEBUG
inspect>org.apache.commons.logging.LogFactory.getLog("com.taobao.tae").getLogger().setLevel(org.apache.log4j.Level.toLevel("INFO"))
null
inspect>org.apache.commons.logging.LogFactory.getLog("com.taobao.tae").getLogger().getLevel()                                      
INFO
```
 
对于无法匹配任何指令的输入，都会当做表达式执行
第一句表达式输出"com.taobao.tae"的log4j level（当然要预先知道用了log4j实现才能这么写）
第二句表达式修改level为INFO级别。注意setLevel的参数又是一段表达式。
第三句重复执行第一个表达式，可以看到log级别已更改。
 
总结一下jvm-inspector支持的表达式特性：
a. 必须以一个全限定类名开始，第一个访问必须是static的方法或字段。
 
 
b.  支持读取父类的非public字段 。比如com.taobao.tae.engine.php.PhpEngineInitializer._quercus的定义是Taobao
Quercus, 其父类的父类是QuercusContext；
QuercusContext类中有个classDefMap；那么只要com.taobao.tae.engine.php.PhpEngineInitializer._quercus._classDefMap就可以直接获取其值了。
 
c.  支持直接调用实现类的方法，无需cast；比如

```
inspect>org.apache.commons.logging.LogFactory.getLog("com.taobao.tae")                       
org.apache.commons.logging.impl.Log4JLogger@2f2773b9
inspect>org.apache.commons.logging.LogFactory.getLog("com.taobao.tae").getLogger().getLevel()
INFO
```

我们知道org.apache.commons.logging.LogFactory.getLog("com.taobao.tae")这步返回的类型是Log，但如果实现是Log4jLogger，后面就可以直接调用Log4jLogger的getLogger方法，无需cast
 
以上这两点总结起来就是以具体对象为准，穿透类型马甲，直达方法、字段。
 
 
d.  支持表达式嵌套。表达式可以直接作为方法参数或value值：

```
inspect>set org.apache.commons.logging.LogFactory.getLog("com.taobao.tae").getLogger().level org.apache.log4j.Level.toLevel("debug")
old value:INFO
inspect>org.apache.commons.logging.LogFactory.getLog("com.taobao.tae").getLogger().getLevel()                                       
DEBUG
```

这个特性非常重要。基本上实际可以执行的java代码，去掉cast，串起来就是一个jvm-inspector支持的表达式。当然目前还没有完善到能够支持所有java语法。有发现不能支持又觉得有需要的请反馈给我。
 
4. classesdump 
输出单个class加载信息：

```
inspect>classesdump org.apache.commons.logging.Log
 
loader  :org.jboss.mx.loading.UnifiedClassLoader3@1735954c{ url=null ,addedOrder=2}
location:file:/home/admin/taegrid/.default/lib/commons-logging-1.1.1.jar
 
loader  :org.eclipse.osgi.internal.baseadaptor.DefaultClassLoader@780bd9ca
location:file:/home/admin/taegrid/hsf.configuration/org.eclipse.osgi/bundles/2/1/.cp/commons-logging-1.1.jar
 
inspect>
```

可以看到common Log类被两个classloader从两个地方加载了两份。
 
dump单个class字节码到文件：

```
inspect>classesdump -b com.taobao.tae.jvm.Log
Done. bytecode dumped to /home/admin/taegrid/com.taobao.tae.jvm.Log.class
```

加-b参数dump字节码到文件。排查动态生成类的时候可能会用的上。
 
dump全部类加载信息：

```
inspect>classesdump 
Done. class & loader infos dumped to /home/admin/taegrid/target/48475.classloaders.1.html
```

这个功能就是1.0版本的唯一功能。class加载信息输出为一个html文件，下到本地用浏览器打开可以看到以classloader、物理地址、包路径3种角度展示类装载树状结构
alt
  
原始的类加载文本信息会输出到${user.dir}/jvm.inspect这个文件中。
 

5. method trace 方法调用跟踪

```
inspect>help trace
trace qualified-mehtod-name [-t timout] [-l count] [-caremhsTLd]
  -t time out in milliseconds. defaut 15000
  -l max invoke count. defaut 2
  -c show class name. default on
  -a show argument vlaues. default on.
  -r show return value. default on
  -e show time elapsed in nano. default on
  -m show method description. default off
  -h show thread name. default off
  -s show stack trace. default skip depth 0
  -T show this Object. default off
  -L show the ClassLoader of the inspect class. default off
  -d dump details to file. default off
```

注意不要trace jdk的常用类。工具使用了java.io java.lang java.util下面的类。trace这些类可能产生死循环。

 

这个功能和housemd完全相同。字节码植入的部分参考了housemd，部分代码直接翻译自housemd的scala代码；额外支持同时trace多个方法，dump方法执行时的调用栈等增强功能。在eclipse中选中一个方法，右键菜单"Copy Qualified Name"得到的路径就可以作为trace命令的method参数。支持逗号分隔多个全限定方法名。

 
```
inspect>trace com.taobao.tae.grid.tbml.TBMLfilterHelper.filterHtml() -l 1 -d
Done. detail info dumped to /home/admin/taegrid/target/8342.details.1.txt
```

这样会拦截一次filterHtml方法调用，输出字段全部用默认，结果写入到文件中。不加-d会直接输出到界面上。
 

可以通过启动参数修改几处默认行为： 

```
/opt/taobao/java/bin/java -Xbootclasspath/a:/opt/taobao/java/lib/tools.jar -jar jvm-inspect-2.0.jar <<pid>> <<agentArgs>>

<<agentArgs>>形式为： k1=v1,k2=v2, ...；(逗号、等号分隔的kv串)
```

1.  outputfile=jvm.inspect  这个是本工具的原始数据输出文件名，文件的每一行记录一个类加载事件的详细信息
2.  HtmlFlusher.enableHyperlink=false 是否在classloaders.html中加入超链接。加入超链接会使文件体积变大约1倍，但是方便点击查看缩写对应表等

3.  listenPort=54321  工具attach成功后会在目标JVM上开一个tcp端口，默认端口是54321；可以通过这个参数指定这个端口。

4.  outputdir=/home/admin 通过outputdir参数指定所有工具产生文件的根目录；不设置默认用"user.dir"；"user.dir"没有写权限时（例如配置了java沙箱的某些环境），默认用"java.io.tmpdir"

 

不加agentArgs的启动命令等同于


/opt/taobao/java/bin/java -Xbootclasspath/a:/opt/taobao/java/lib/tools.jar -jar jvm-inspect-2.0.jar <<pid>> outputfile=jvm.inspect,HtmlFlusher.enableHyperlink=false,listenPort=54321,outputdir=System.getProperty("user.dir")或/tmp