#!/usr/bin/env python3
"""Build Kweebet with a locally supplied, pinned Hytale server and Java 25."""
from pathlib import Path
import hashlib,json,os,shutil,subprocess,zipfile

ROOT=Path(__file__).resolve().parents[1]
from release_pipeline import config, filename
def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def toolchain():
    target=json.loads((ROOT/'build-target.json').read_text())
    local=ROOT/'data/target-lock.json'
    private=json.loads(local.read_text()) if local.exists() else {}
    server=os.environ.get('HYTALE_SERVER_JAR') or private.get('server_path')
    java=os.environ.get('JAVA_HOME') or private.get('jdk_path')
    if not server or not java:
        raise SystemExit('Set HYTALE_SERVER_JAR to your Hytale server JAR and JAVA_HOME to a Java 25 JDK. See https://jkswoods.github.io/Kweebet/hytale0.7/building/.')
    server,jdk=Path(server),Path(java)
    if not server.is_file() or not (jdk/'bin/javac').is_file():
        raise SystemExit('The supplied Hytale server JAR or Java compiler does not exist.')
    if sha(server)!=target['server_sha256']:
        raise SystemExit('Hytale server checksum differs from build-target.json. Use the supported Hytale build.')
    return server,jdk,target

def main():
    server,jdk,target=toolchain()
    for notice in ['LICENSE','THIRD_PARTY_NOTICES.md']:
        if (ROOT/notice).read_bytes() != (ROOT/'src/main/resources/META-INF'/notice).read_bytes():
            raise SystemExit('Packaged '+notice+' must match the repository notice.')
    classes=ROOT/'build/classes'
    if classes.exists():shutil.rmtree(classes)
    classes.mkdir(parents=True)
    sources=sorted((ROOT/'src/main/java').rglob('*.java'))
    subprocess.run([str(jdk/'bin/javac'),'--release','25','-Xlint:all','-cp',str(server),'-d',str(classes),*map(str,sources)],check=True)
    manifest=json.loads((ROOT/'src/main/resources/manifest.json').read_text())
    out=ROOT/'build/libs'/filename(config(ROOT/'release.json'))
    out.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for folder in [classes,ROOT/'src/main/resources']:
            for p in sorted(folder.rglob('*')):
                if p.is_file():
                    info=zipfile.ZipInfo(p.relative_to(folder).as_posix(),(2026,10,24,0,0,0))
                    info.compress_type=zipfile.ZIP_DEFLATED
                    info.external_attr=0o100644<<16
                    z.writestr(info,p.read_bytes())
    record={'artifact':str(out.relative_to(ROOT)),'sha256':sha(out),'bytes':out.stat().st_size,
            'server_sha256':target['server_sha256'],'java_release':25,
            'source_sha256':{str(p.relative_to(ROOT)):sha(p) for p in sources}}
    (ROOT/'build/build-info.json').write_text(json.dumps(record,indent=2)+'\n')
    if (ROOT/'project').is_dir():
        (ROOT/'project/build.json').write_text(json.dumps(record|{'installed':False,'native_client_observed':False},indent=2)+'\n')
    print(json.dumps({k:v for k,v in record.items() if k!='source_sha256'},indent=2))

if __name__=='__main__':main()
