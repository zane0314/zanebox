#!/usr/bin/env python3
"""Compare saved builder/assets with current code; unchanged helpers use current compiled classes."""
import argparse, glob, ipaddress, json, pathlib, subprocess, tempfile
root=pathlib.Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--baseline-source',type=pathlib.Path,required=True,help='已核实历史源码根目录（包含 app/src/main/java）')
parser.add_argument('--output',type=pathlib.Path,required=True,help='本次比较结果目录')
args=parser.parse_args()
before=args.baseline_source.resolve()/'app/src/main/java/com/zane/zanebox'
required=['ConfigBuilder.kt','RouteAssets.kt']
for name in required:
    if not (before/'config'/name).is_file(): parser.error(f'基线不完整：缺少 {before/"config"/name}；请从已核实的历史 Git 提交导出源码')
current=root/'app/src/main/java/com/zane/zanebox'
if before==current or all((before/'config'/name).read_bytes()==(current/'config'/name).read_bytes() for name in required):
    parser.error('基线与当前构建源码相同，不能用自己比较自己；请指定真实的变更前源码')
out=args.output.resolve()
if out==args.baseline_source.resolve() or args.baseline_source.resolve() in out.parents:
    parser.error('输出目录必须独立于基线源码')
classes=root/'app/build/tmp/kotlin-classes/debug'
built=classes/'com/zane/zanebox/config/ConfigBuilder.class'
if not built.is_file() or built.stat().st_mtime < (current/'config/ConfigBuilder.kt').stat().st_mtime:
    parser.error('当前 ConfigBuilder 编译产物缺失或过期；请先构建当前 debug Kotlin classes')
out.mkdir(parents=True,exist_ok=True)
cache=pathlib.Path.home()/'.gradle/caches/modules-2/files-2.1'
def jar(pattern):
    found=glob.glob(str(cache/pattern));assert found,pattern
    return found[0]
std=jar('org.jetbrains.kotlin/kotlin-stdlib/2.0.21/*/*.jar')
compiler=[jar('org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21/*/*.jar'),std,jar('org.jetbrains.kotlin/kotlin-script-runtime/2.0.21/*/*.jar'),jar('org.jetbrains.intellij.deps/trove4j/*/*/*.jar'),jar('org.jetbrains/annotations/13.0/*/*.jar'),jar('org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.6.4/*/*.jar')]
classes=root/'app/build/tmp/kotlin-classes/debug'
libs=[str(classes),std,jar('org.json/json/20240303/*/*.jar'),jar('org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0/*/*.jar'),jar('com.squareup.okhttp3/okhttp/4.12.0/*/*.jar'),jar('com.squareup.okio/okio-jvm/3.6.0/*/*.jar'),jar('org.yaml/snakeyaml/2.3/*/*.jar')]
java='/opt/homebrew/opt/openjdk@17/bin/java'
with tempfile.TemporaryDirectory(prefix='links-config-compare-') as tmp:
    tmp=pathlib.Path(tmp);sources=[]
    for name in required:
        package='package baseline'+ ('\n\nimport com.zane.zanebox.config.effectiveSmartPackages' if name=='ConfigBuilder.kt' else '')
        p=tmp/name;p.write_text((before/'config'/name).read_text().replace('package com.zane.zanebox.config',package));sources.append(str(p))
    harness=tmp/'Bench.kt';harness.write_text('''
import com.zane.zanebox.data.*
import org.json.JSONObject
import java.io.File
fun main(args:Array<String>) {
 val dir=File(args[0]);val assets=File(args[1])
 val data=AppData(nodes=(1..1000).map{Node(it.toLong(),1,"fixture $it","""{"type":"socks","server":"127.0.0.1","server_port":9}""")},groups=listOf(Group(1,"g","https://fixture.test/sub")),rules=listOf(RouteRule(1,"priority","priority.test",outbound="direct",prioritize=true),RouteRule(2,"ordinary","user.test",outbound="node:1")),settings=mapOf("selectedNodeId" to "1","fakeDns" to "false","logLevel" to "warn"))
 val mixed=data.copy(settings=data.settings+baseline.builtinSmartRuleFiles.keys.withIndex().associate { (index,key)->"smart.$key.target" to when(index%3){0->"direct";1->"node:2";else->"auto"} })
 val loaded=baseline.withBuiltinSmartRules(mixed){File(assets,it).readText()}
 fun measure(name:String,build:()->String):JSONObject {
  repeat(3){build()};val times=(1..9).map{val start=System.nanoTime();build();(System.nanoTime()-start)/1e6}.sorted()
  val config=build();File(dir,"config-$name.json").writeText(config);val o=JSONObject(config)
  return JSONObject().put("medianBuildMs",times[4]).put("routeRules",o.getJSONObject("route").getJSONArray("rules").length()).put("dnsRules",o.getJSONObject("dns").getJSONArray("rules").length()).put("configBytes",config.toByteArray().size)
 }
 val result=JSONObject().put("nodes",1000).put("old",measure("old"){baseline.ConfigBuilder.build(loaded)}).put("new",measure("new"){com.zane.zanebox.config.ConfigBuilder.build(loaded)})
 val fake=JSONObject(com.zane.zanebox.config.ConfigBuilder.build(loaded.copy(settings=loaded.settings+("fakeDns" to "true"))));result.put("newWithFakeIpDnsRules",fake.getJSONObject("dns").getJSONArray("rules").length())
 File(dir,"config-performance.json").writeText(result.toString(2));println(result)
}''');sources.append(str(harness))
    subprocess.run([java,'-cp',':'.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-Xfriend-paths='+str(classes),'-classpath',':'.join(libs),'-d',str(tmp/'classes'),*sources],check=True)
    subprocess.run([java,'-cp',':'.join([str(tmp/'classes'),*libs]),'BenchKt',str(out),str(root/'app/src/main/assets')],check=True)
old=json.loads((out/'config-old.json').read_text());new=json.loads((out/'config-new.json').read_text())
def target(config,host='',ip=''):
    for r in config['route']['rules']:
        domains=r.get('domain',[]);suffix=r.get('domain_suffix',[]);keywords=r.get('domain_keyword',[])
        domain_match=host in domains or any(host==d.lstrip('.') or host.endswith('.'+d.lstrip('.')) for d in suffix) or any(d in host for d in keywords)
        ip_match=bool(ip) and any(ipaddress.ip_address(ip) in ipaddress.ip_network(c,strict=False) for c in r.get('ip_cidr',[]))
        if (domain_match or ip_match) and ('outbound' in r or r.get('action')=='reject'):return r.get('outbound','block')
    return config['route']['final']
cases=[('priority.test',''),('user.test',''),('unmatched.test','')]
for r in old['route']['rules']:
    cases.extend((d,'') for d in r.get('domain',[]));cases.extend(('probe.'+d.lstrip('.'),'') for d in r.get('domain_suffix',[]));cases.extend(('probe'+d+'.test','') for d in r.get('domain_keyword',[]))
    cases.extend(('',str(ipaddress.ip_network(c,strict=False).network_address)) for c in r.get('ip_cidr',[]))
for h,i in cases:assert target(old,h,i)==target(new,h,i),(h,i,target(old,h,i),target(new,h,i))
result=json.loads((out/'config-performance.json').read_text());result['routingComparisons']=len(cases);result['routingEquivalent']=True
assert result['new']['routeRules']<100 and result['new']['dnsRules']<100
(out/'config-performance.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
