#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[[ $# == 6 ]] || { echo "Usage: $0 OLD_PROJECT NEW_PROJECT OLD_GROUP NEW_GROUP OLD_PACKAGE NEW_PACKAGE" >&2; exit 2; }
[[ -z "$(git status --porcelain)" ]] || { echo "Git working tree must be clean" >&2; exit 2; }
candidate=$(mktemp -d "${TMPDIR:-/tmp}/proto-rename.XXXXXX")
git clone -q --no-hardlinks . "$candidate"
python3 - "$candidate" "$@" <<'RENAME'
from pathlib import Path
import re,sys,xml.etree.ElementTree as ET
root=Path(sys.argv[1]);old_project,new_project,old_group,new_group,old_package,new_package=sys.argv[2:]
for value in (old_project,new_project):
 if not re.fullmatch('[a-z][a-z0-9-]*',value):raise SystemExit('Invalid project name')
for value in (old_group,new_group,old_package,new_package):
 if not re.fullmatch('[a-zA-Z_]\w*(?:[.][a-zA-Z_]\w*)+',value):raise SystemExit('Invalid group/package')
ns={'m':'http://maven.apache.org/POM/4.0.0'}
pom=ET.parse(root/'pom.xml')
if pom.findtext('m:groupId',namespaces=ns)!=old_group or pom.findtext('m:artifactId',namespaces=ns)!=old_project:raise SystemExit('Old coordinates do not match the source project')
startup=root/'bootstrap/src/main/java'/Path(*old_package.split('.'))/(''.join(word.title() for word in old_project.split('-'))+'Application.java')
if not startup.is_file():raise SystemExit('Old package/startup class does not match source')
old_class=''.join(word.title() for word in old_project.split('-'))+'Application'
new_class=''.join(word.title() for word in new_project.split('-'))+'Application'
for file in list(root.rglob('*')):
 if not file.is_file() or '.git' in file.parts or 'baseline' in file.parts or 'evidence' in file.parts:continue
 if file.suffix in ('.java','.kt'):
  file.write_text(file.read_text().replace(old_package,new_package).replace(old_class,new_class))
  marker='/'+old_package.replace('.','/')+'/'
  target=Path(str(file).replace(marker,'/'+new_package.replace('.','/')+'/'))
  if target.name==old_class+file.suffix:target=target.with_name(new_class+file.suffix)
  if target!=file:target.parent.mkdir(parents=True,exist_ok=True);file.rename(target)
 elif file.name=='pom.xml':
  text=file.read_text().replace('<groupId>'+old_group+'</groupId>','<groupId>'+new_group+'</groupId>')
  text=text.replace(old_package+'.'+old_class,new_package+'.'+new_class).replace('<artifactId>'+old_project+'</artifactId>','<artifactId>'+new_project+'</artifactId>').replace('<name>'+old_project+'</name>','<name>'+new_project+'</name>')
  file.write_text(text)
 elif file.name in ('verify-identifiers.py','check-coverage.py'):
  file.write_text(file.read_text().replace(old_package,new_package).replace(old_package.replace('.','/'),new_package.replace('.','/')))
 elif file.name in ('README.md','Dockerfile','docker-compose.yml','application.yml','logback-spring.xml','.env.example') or '.github' in file.parts:
  file.write_text(file.read_text().replace(old_project,new_project).replace(old_package,new_package).replace(old_class,new_class))
RENAME
(
 cd "$candidate"
 ./mvnw -B -ntp spotless:apply clean verify &&
 python3 scripts/verify-identifiers.py &&
 python3 scripts/check-coverage.py &&
 python3 - "$1" "$2" "$3" "$4" "$5" "$6" <<'SCAN'
from pathlib import Path
import sys
old_project,new_project,old_group,new_group,old_package,new_package=sys.argv[1:]
checks=[(old_project,new_project),(old_group,new_group),(old_package,new_package)]
files=list(Path('.').rglob('pom.xml'))+list(Path('.').rglob('*.java'))
files += [Path('README.md'),Path('Dockerfile'),Path('docker-compose.yml'),Path('bootstrap/src/main/resources/application.yml'),Path('bootstrap/src/main/resources/logback-spring.xml')]
files += list(Path('.github').rglob('*.yml'))
for file in files:
 if 'target' in file.parts:continue
 for old,new in checks:
  if old!=new and old in file.read_text().replace(new,''):raise SystemExit('Old identifier remains: '+str(file)+' '+old)
SCAN
) || { echo "Rename verification failed. Original source unchanged. Candidate: $candidate" >&2; exit 1; }
rsync -a --delete --exclude=.git --exclude=target --exclude=data --exclude=.env --exclude='.env.*' --exclude='.idea' "$candidate/" ./
cp "$candidate/.env.example" .env.example
echo "Rename verified and applied. Check database, domains, image registry and OAuth callbacks. Candidate: $candidate"
