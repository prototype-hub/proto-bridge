#!/usr/bin/env python3
from pathlib import Path
import re,sys,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
ns={"m":"http://maven.apache.org/POM/4.0.0"}
errors=[]
for pom in root.rglob('pom.xml'):
 if 'target' in pom.parts:continue
 tree=ET.parse(pom)
 for dependency in tree.findall('.//m:dependency',ns):
  group=dependency.findtext('m:groupId','',ns);version=dependency.findtext('m:version','',ns)
  if group.startswith('org.jetlinks') or 'SNAPSHOT' in version:errors.append(str(pom))
for source in root.rglob('*.java'):
 if 'target' in source.parts:continue
 text=source.read_text()
 package=re.search(r'^package ([\w.]+);',text,re.M)
 if not package or not package[1].startswith('com.gaiys.proto'):errors.append(str(source))
 if re.search(r'org\.jetlinks|nexus\.jetlinks',text):errors.append(str(source))
 if package:
  expected=Path(*package[1].split('.'))/source.name
  if not str(source).endswith(str(expected)):errors.append('Package path: '+str(source))
if errors:print('\n'.join(errors));sys.exit(1)
print('Coordinates, stable dependencies and source package paths verified')
