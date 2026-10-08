import os
import xml.etree.ElementTree as ET
import json

def get_keys(dir_path):
    keys = {}
    if not os.path.exists(dir_path): return keys
    for file in os.listdir(dir_path):
        if file.endswith('.xml'):
            tree = ET.parse(os.path.join(dir_path, file))
            for elem in tree.iter():
                if 'name' in elem.attrib and elem.text is not None:
                    keys[elem.attrib['name']] = elem.text
    return keys

en_keys = get_keys('composeApp/src/commonMain/composeResources/values')
bn_keys = get_keys('composeApp/src/commonMain/composeResources/values-bn')

missing_dict = {k: v for k, v in en_keys.items() if k not in bn_keys}
with open('missing_en.json', 'w') as f:
    json.dump(missing_dict, f, indent=2)
