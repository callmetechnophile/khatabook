import sys
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding='utf-8')
tree = ET.parse('dump_check.xml')
root = tree.getroot()

for node in root.iter('node'):
    text = node.attrib.get('text', '')
    res_id = node.attrib.get('resource-id', '')
    if text or 'btn' in res_id or 'nav' in res_id:
        print(f"id={res_id} | text={text} | bounds={node.attrib.get('bounds')}")
