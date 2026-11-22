import sys
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding='utf-8')

tree = ET.parse('dump_now.xml')
root = tree.getroot()

for node in root.iter('node'):
    res_id = node.attrib.get('resource-id', '')
    text = node.attrib.get('text', '')
    bounds = node.attrib.get('bounds', '')
    checked = node.attrib.get('checked', '')
    if any(k in res_id for k in ['lang', 'tab', 'nav', 'container', 'btn', 'rb', 'tv']):
        print(f"id={res_id} | text={text} | checked={checked} | bounds={bounds}")
