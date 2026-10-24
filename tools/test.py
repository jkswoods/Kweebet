#!/usr/bin/env python3
"""Run the authoritative accounting and display-state checks on the JVM."""
import json,os,shutil,subprocess
from build import ROOT,toolchain

server,jdk,_=toolchain()
classes=ROOT/'build/test-classes'
if classes.exists():shutil.rmtree(classes)
classes.mkdir(parents=True)
cp=os.pathsep.join(map(str,[ROOT/'build/classes',server]))
subprocess.run([str(jdk/'bin/javac'),'--release','25','-cp',cp,'-d',str(classes),*map(str,(ROOT/'src/test/java').rglob('*.java'))],check=True)
for name,report,scope in [
    ('AccountingTest','accounting-tests.json','JVM state and item-accounting checks with simulated wallets; not native-client or multiplayer evidence'),
    ('WorldSceneTest','world-scene-tests.json','JVM display geometry, outcomes and hidden-state checks; not native rendering')
]:
    result=subprocess.run([str(jdk/'bin/java'),'-ea','-cp',str(classes)+os.pathsep+cp,'dev.kweebet.'+name],capture_output=True,text=True)
    print(result.stdout,end='')
    if result.stderr:print(result.stderr,end='')
    record={'exit_code':result.returncode,'stdout':result.stdout,'stderr':result.stderr,'scope':scope}
    (ROOT/'build'/report).write_text(json.dumps(record,indent=2)+'\n')
    local=ROOT/'data/verification'
    if local.is_dir():(local/report).write_text(json.dumps(record,indent=2)+'\n')
    result.check_returncode()
