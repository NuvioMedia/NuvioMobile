import os
import xml.etree.ElementTree as ET

def get_keys(dir_path):
    keys = set()
    if not os.path.exists(dir_path): return keys
    for file in os.listdir(dir_path):
        if file.endswith('.xml'):
            tree = ET.parse(os.path.join(dir_path, file))
            for elem in tree.iter():
                if 'name' in elem.attrib:
                    keys.add(elem.attrib['name'])
    return keys

en_keys = get_keys('composeApp/src/commonMain/composeResources/values')
bn_keys = get_keys('composeApp/src/commonMain/composeResources/values-bn')

missing = en_keys - bn_keys
print(f"Found {len(missing)} missing keys in bn.")
if missing:
    print(list(missing)[:20])
