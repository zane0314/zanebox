#!/usr/bin/env python3
"""独立 JVM 检查配置/解析，不依赖其他协作者正在编辑的 Android 类。"""
import glob, pathlib, subprocess, tempfile, urllib.request
root = pathlib.Path(__file__).resolve().parents[1]
cache = pathlib.Path.home()/'.gradle/caches/modules-2/files-2.1'
def jar(pattern):
    values=glob.glob(str(cache/pattern)); assert values,pattern
    return values[0]
with tempfile.TemporaryDirectory(prefix='zanebox-config-test-') as tmp:
    tmp=pathlib.Path(tmp)
    libraries=[]
    for name,url in [('json','org/json/json/20240303/json-20240303.jar'),('junit','junit/junit/4.13.2/junit-4.13.2.jar'),('mockwebserver','com/squareup/okhttp3/mockwebserver/4.12.0/mockwebserver-4.12.0.jar')]:
        path=tmp/(name+'.jar'); subprocess.run(['curl','-fsSL','--retry','3','-o',str(path),'https://repo.maven.apache.org/maven2/'+url],check=True);libraries.append(str(path))
    std=jar('org.jetbrains.kotlin/kotlin-stdlib/2.0.21/*/*.jar')
    libs=libraries+[jar('com.squareup.okhttp3/okhttp/4.12.0/*/*.jar'),jar('com.squareup.okio/okio-jvm/3.6.0/*/*.jar'),jar('org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0/*/*.jar'),std,jar('org.yaml/snakeyaml/2.3/*/*.jar'),jar('org.hamcrest/hamcrest-core/1.3/*/*.jar')]
    compiler=[jar('org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21/*/*.jar'),std,jar('org.jetbrains.kotlin/kotlin-script-runtime/2.0.21/*/*.jar'),jar('org.jetbrains.intellij.deps/trove4j/*/*/*.jar'),jar('org.jetbrains/annotations/13.0/*/*.jar'),jar('org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.6.4/*/*.jar')]
    java='/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home/bin/java'
    if not pathlib.Path(java).exists(): java='java'
    sources=['app/src/main/java/com/zane/zanebox/data/Models.kt','app/src/main/java/com/zane/zanebox/data/NodeIdentity.kt','app/src/main/java/com/zane/zanebox/subscription/SubscriptionOptions.kt','app/src/main/java/com/zane/zanebox/subscription/SubscriptionDnsOptions.kt','app/src/main/java/com/zane/zanebox/config/ConfigBuilder.kt','app/src/main/java/com/zane/zanebox/subscription/SubscriptionParser.kt','app/src/main/java/com/zane/zanebox/subscription/SubscriptionClient.kt','app/src/test/java/com/zane/zanebox/ConfigSubscriptionTest.kt']
    subprocess.run([java,'-cp',':'.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',':'.join(libs),'-d',str(tmp/'classes')]+[str(root/s) for s in sources],check=True)
    subprocess.run([java,'-cp',':'.join([str(tmp/'classes')]+libs),'org.junit.runner.JUnitCore','com.zane.zanebox.ConfigSubscriptionTest'],check=True)
