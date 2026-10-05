#!/usr/bin/env python3
import glob,pathlib,subprocess,tempfile
root=pathlib.Path(__file__).resolve().parents[1]
cache=pathlib.Path.home()/'.gradle/caches/modules-2/files-2.1'
def jar(pattern):
    values=glob.glob(str(cache/pattern));assert values,pattern
    return values[0]
std=jar('org.jetbrains.kotlin/kotlin-stdlib/2.0.21/*/*.jar')
compiler=[jar('org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21/*/*.jar'),std,jar('org.jetbrains.kotlin/kotlin-script-runtime/2.0.21/*/*.jar'),jar('org.jetbrains.intellij.deps/trove4j/*/*/*.jar'),jar('org.jetbrains/annotations/13.0/*/*.jar'),jar('org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.6.4/*/*.jar')]
libs=[std,jar('org.json/json/20240303/*/*.jar'),jar('junit/junit/4.13.2/*/*.jar'),jar('org.hamcrest/hamcrest-core/1.3/*/*.jar')]
java='/opt/homebrew/opt/openjdk@17/bin/java'
with tempfile.TemporaryDirectory(prefix='zanebox-traffic-test-') as tmp:
    sources=['app/src/main/java/com/zane/zanebox/runtime/TrafficSamples.kt','app/src/test/java/com/zane/zanebox/TrafficSamplesTest.kt']
    subprocess.run([java,'-cp',':'.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',':'.join(libs),'-d',tmp]+[str(root/s) for s in sources],check=True)
    subprocess.run([java,'-cp',':'.join([tmp]+libs),'org.junit.runner.JUnitCore','com.zane.zanebox.TrafficSamplesTest'],check=True)
