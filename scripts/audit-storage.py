#!/usr/bin/env python3
"""Inspect a read-only version manifest; repairs require an offline snapshot and explicit flags."""
import argparse,json,re,sys,shutil
from pathlib import Path
parser=argparse.ArgumentParser()
parser.add_argument('--root',required=True);parser.add_argument('--manifest',required=True)
parser.add_argument('--delete-unreferenced',action='store_true');parser.add_argument('--offline',action='store_true');parser.add_argument('--confirm')
args=parser.parse_args();root=Path(args.root).absolute()
if root.is_symlink():parser.error('Storage root must not be a symlink')
root=root.resolve();manifest=json.loads(Path(args.manifest).read_text());referenced=set();findings=[]
for row in manifest:
 project=row['projectId'];storage=row['storagePath']
 if not re.fullmatch('[A-Za-z0-9_-]{1,128}',project):parser.error('Invalid project in manifest')
 if not re.fullmatch(r'projects/'+re.escape(project)+r'/versions/[A-Za-z0-9_-]{1,128}/site',storage):parser.error('Invalid storage boundary in manifest')
 path=root/storage;version=path.parent
 if path.is_absolute() and (not path.is_relative_to(root) or version.parent!=root/'projects'/project/'versions' or path.name!='site'):parser.error('Invalid storage boundary in manifest')
 if any(part.is_symlink() for part in [path,version,version.parent,version.parent.parent]):parser.error('Symlink in manifest storage boundary')
 referenced.add(version)
 if not path.is_dir():findings.append({'kind':'missing-files','projectId':project,'versionId':row['id']})
unreferenced=[]
for version in sorted((root/'projects').glob('*/versions/*')):
 if any(part.is_symlink() for part in [version,version.parent,version.parent.parent,version.parent.parent.parent]):findings.append({'kind':'symlink','path':str(version.relative_to(root))});continue
 if version not in referenced and version.is_dir():
  unreferenced.append(version);findings.append({'kind':'unreferenced-directory','path':str(version.relative_to(root))})
print(json.dumps(findings,ensure_ascii=False,indent=2))
if args.delete_unreferenced:
 if not args.offline or not args.confirm or Path(args.confirm).resolve()!=root:parser.error('Repair requires --offline and --confirm equal to the absolute storage root')
 for version in unreferenced:
  if any(part.is_symlink() for part in [version,version.parent,version.parent.parent,version.parent.parent.parent]):parser.error('Refusing repair through symlink')
  shutil.rmtree(version)
 print('Removed '+str(len(unreferenced))+' unreferenced directories from the offline snapshot')
