#!/usr/bin/env python3
from pathlib import Path
import json,sys,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
report=root/'bootstrap/target/site/jacoco-aggregate/jacoco.xml'
if not report.exists():raise SystemExit('Run clean verify to generate aggregate coverage first')
results={}
for area in ('service','storage'):
 covered=missed=0
 for package in ET.parse(report).findall('.//package'):
  name=package.get('name','');prefix='com/gaiys/proto/prototypehub/'+area
  if name!=prefix and not name.startswith(prefix+'/'):continue
  for cls in package.findall('class'):
   line=cls.find("counter[@type='LINE']")
   if line is not None:covered+=int(line.get('covered'));missed+=int(line.get('missed'))
 total=covered+missed
 if not total:raise SystemExit('No coverage data for '+area)
 results[area]={'covered':covered,'total':total,'percent':round(100*covered/total,2)}
print(json.dumps(results,indent=2));(root/'target/coverage-summary.json').write_text(json.dumps(results,indent=2)+'\n')
if any(row['percent']<80 for row in results.values()):sys.exit(1)
