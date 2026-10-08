#!/usr/bin/env python3
"""Compile only backup production Kotlin + Models and execute JUnit, while other agents edit UI/runtime."""
from pathlib import Path
import subprocess
import tempfile
import os
root=Path(__file__).resolve().parents[1]
cache=Path.home()/'.gradle/caches/modules-2/files-2.1'
def jar(group,name,version):
    matches=list((cache/group/name/version).glob('*/*.jar'))
    if not matches: raise RuntimeError(f'Gradle dependency missing: {group}/{name}/{version}; run Gradle once first')
    return str(matches[0])
stdlib=jar('org.jetbrains.kotlin','kotlin-stdlib','2.0.21')
compiler=[jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.0.21'),stdlib,jar('org.jetbrains.kotlin','kotlin-script-runtime','2.0.21'),jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),jar('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),jar('org.jetbrains','annotations','13.0'),jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.6.4')]
sdk=(root/'local.properties').read_text().strip().split('=',1)[1]
deps=[jar('org.json','json','20240303'),stdlib,str(Path(sdk)/'platforms/android-35/android.jar'),jar('com.squareup.okhttp3','okhttp','4.12.0'),jar('com.squareup.okhttp3','mockwebserver','4.12.0'),jar('com.squareup.okio','okio-jvm','3.6.0'),jar('org.yaml','snakeyaml','2.3'),jar('org.json','json','20240303'),jar('junit','junit','4.13.2'),jar('org.hamcrest','hamcrest-core','1.3'),jar('org.jetbrains','annotations','13.0'),jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.9.0')]
files=[root/'app/src/main/java/com/zane/zanebox/data/Models.kt',root/'app/src/main/java/com/zane/zanebox/data/SettingDefaults.kt',root/'app/src/main/java/com/zane/zanebox/data/NodeIdentity.kt',root/'app/src/main/java/com/zane/zanebox/subscription/SubscriptionDnsOptions.kt',root/'app/src/main/java/com/zane/zanebox/subscription/SubscriptionOptions.kt',root/'app/src/main/java/com/zane/zanebox/config/ConfigBuilder.kt',root/'app/src/main/java/com/zane/zanebox/config/AppSelection.kt',root/'app/src/main/java/com/zane/zanebox/config/CommonProxyApps.kt',root/'app/src/main/java/com/zane/zanebox/config/RouteAssets.kt',root/'app/src/main/java/com/zane/zanebox/core/RouteMath.kt',root/'app/src/main/java/com/zane/zanebox/subscription/ShareUri.kt',root/'app/src/main/java/com/zane/zanebox/subscription/SubscriptionClient.kt',root/'app/src/main/java/com/zane/zanebox/subscription/SubscriptionParser.kt',*sorted((root/'app/src/main/java/com/zane/zanebox/backup').glob('*.kt')),root/'app/src/test/java/com/zane/zanebox/BackupTest.kt',root/'app/src/test/java/com/zane/zanebox/WebDavInteropTest.kt']
with tempfile.TemporaryDirectory(prefix='zanebox-backup-check-') as output:
    subprocess.run(['java','-cp',':'.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',':'.join(deps),'-d',output,*map(str,files)],check=True)
    keystore=str(Path(output)/'fixture.p12')
    subprocess.run(['keytool','-genkeypair','-alias','fixture','-keyalg','RSA','-keysize','2048','-validity','1','-dname','CN=localhost','-ext','SAN=dns:localhost,ip:127.0.0.1','-storetype','PKCS12','-keystore',keystore,'-storepass','fixturepass','-keypass','fixturepass'],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    environment=os.environ.copy();environment['ZANEBOX_WEBDAV_TEST_KEYSTORE']=keystore;environment['ZANEBOX_BACKUP_EVIDENCE_DIR']=str(Path(output)/'legacy-native')
    subprocess.run(['java','-cp',':'.join([output,*deps]),'org.junit.runner.JUnitCore','com.zane.zanebox.BackupTest','com.zane.zanebox.WebDavInteropTest'],check=True,env=environment)
