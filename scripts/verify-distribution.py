#!/usr/bin/env python3
"""Check actual Maven release JAR bytes against collected legal material.

This checks completeness and integrity of collected files, not contribution rights,
license alternatives, corresponding-source offers or other legal approval.
"""
import hashlib
import io
import json
import os
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def verify_archive(archive, label, prefix='', nested=False):
    legal = prefix + 'META-INF/'
    for name in ('LICENSE', 'NOTICE'):
        if archive.read(legal + name) != (ROOT / name).read_bytes():
            raise ValueError(label + ': project ' + name + ' differs from source')
    base = legal + 'third-party/'
    inventory = json.loads(archive.read(base + 'inventory.json'))
    if not inventory.get('collectionComplete') or inventory.get('missing') or not inventory.get('artifacts'):
        raise ValueError(label + ': incomplete/empty license collection')
    if not archive.read(base + 'THIRD-PARTY.txt').strip():
        raise ValueError(label + ': empty third-party summary')
    for name, spec in inventory['standardTextSources'].items():
        if sha(archive.read(base + 'licenses/' + spec['file'])) != spec['sha256']:
            raise ValueError(label + ': changed standard license text ' + name)
    for artifact in inventory['artifacts']:
        for notice in artifact['upstreamNoticeFiles']:
            path = base + artifact['noticeDirectory'] + '/' + notice['path']
            if sha(archive.read(path)) != notice['sha256']:
                raise ValueError(label + ': changed upstream legal text ' + artifact['coordinate'])
    external_count, internal_count = 0, 0
    if nested:
        expected = {artifact['artifactSha256'] for artifact in inventory['artifacts']}
        observed = set()
        for name in archive.namelist():
            if not name.startswith('BOOT-INF/lib/') or not name.endswith('.jar'):
                continue
            data = archive.read(name)
            with zipfile.ZipFile(io.BytesIO(data)) as library:
                if 'META-INF/maven/com.gaiys.proto/' in '\n'.join(library.namelist()):
                    verify_archive(library, name)
                    internal_count += 1
                    continue
            observed.add(sha(data))
            if sha(data) not in expected:
                raise ValueError(label + ': unlisted distributed JAR ' + name)
            external_count += 1
        # Boot's official packager omits dependencies-starter marker JARs. Keep
        # them in Maven's closure inventory, but require no unclassified omissions.
        omitted_starters = {a['artifactSha256'] for a in inventory['artifacts']
                            if a.get('springBootJarType') == 'dependencies-starter'}
        if observed | omitted_starters != expected:
            missing = [a['coordinate'] for a in inventory['artifacts']
                       if a['artifactSha256'] not in observed | omitted_starters]
            raise ValueError(label + ': inventory artifacts absent from distribution: ' + ', '.join(missing))
    return {'jar': label, 'runtimeArtifacts': len(inventory['artifacts']),
            'upstreamNoticeFiles': sum(len(a['upstreamNoticeFiles']) for a in inventory['artifacts']),
            'componentsNeedingUpstreamNoticeReview': [a['coordinate'] for a in inventory['artifacts']
                if not a['upstreamNoticeFiles']],
            'standardTexts': len(inventory['standardTextSources']),
            'starterMarkersExcludedByBoot': sum(a.get('springBootJarType') == 'dependencies-starter' for a in inventory['artifacts']),
            'checkedNestedExternalJars': external_count, 'checkedNestedProjectJars': internal_count}


def main():
    root = ET.parse(ROOT / 'pom.xml').getroot()
    version = root.findtext('m:version', namespaces=NS)
    result = []
    bootstrap_jar = ROOT / 'bootstrap/target' / f'bootstrap-{version}.jar'
    sidecar = bootstrap_jar.with_suffix('.jar.license-sha256')
    # A failed verification must not leave an earlier success marker available.
    sidecar.unlink(missing_ok=True)
    for module in root.findall('m:modules/m:module', NS):
        path = ROOT / module.text
        pom = ET.parse(path / 'pom.xml').getroot()
        artifact = pom.findtext('m:artifactId', namespaces=NS)
        jar = path / 'target' / f'{artifact}-{version}.jar'
        with zipfile.ZipFile(jar) as archive:
            result.append(verify_archive(archive, str(jar.relative_to(ROOT)),
                          prefix='',
                          nested=artifact == 'bootstrap'))
    output = ROOT / 'target/distribution-license-verification.json'
    output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps({'collectionVerified': True, 'complianceApproved': False,
                                 'jars': result}, ensure_ascii=False, indent=2) + '\n')
    # The sidecar prevents accidentally building Docker from stale/unverified legal
    # material. It is a local integrity marker, not a signature or authenticity proof.
    temporary = sidecar.with_suffix(sidecar.suffix + '.tmp')
    temporary.write_text(sha(bootstrap_jar.read_bytes()) + '  app.jar\n', encoding='ascii')
    os.replace(temporary, sidecar)
    for item in result:
        print(f'{item["jar"]}: legal bytes verified for {item["runtimeArtifacts"]} runtime artifacts')
    print('Integrity verified; component obligations and contribution rights still require review.')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, ET.ParseError, zipfile.BadZipFile) as error:
        print('Distribution verification failed: ' + str(error), file=sys.stderr)
        sys.exit(1)
