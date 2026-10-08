import xml.etree.ElementTree as ET

path = 'composeApp/src/commonMain/composeResources/values-bn/strings.xml'
parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
tree = ET.parse(path, parser)
root = tree.getroot()

changed = 0
for elem in root.iter():
    if elem.text:
        original = elem.text
        # Replace Notification -> বিজ্ঞপ্তি
        new_text = original.replace('নোটিফিকেশন', 'বিজ্ঞপ্তি')
        # Replace Contributor -> অবদানকারী
        new_text = new_text.replace('কন্ট্রিবিউটর', 'অবদানকারী')
        new_text = new_text.replace('কন্ট্রিবিউটরদের', 'অবদানকারীদের')
        # Replace Supporter -> সমর্থক
        new_text = new_text.replace('সাপোর্টার', 'সমর্থক')
        new_text = new_text.replace('সাপোর্টারদের', 'সমর্থকদের')
        
        if new_text != original:
            elem.text = new_text
            changed += 1

xmlstr = ET.tostring(root, encoding='utf-8').decode('utf-8')
with open(path, 'w', encoding='utf-8') as f:
    f.write('<?xml version="1.0" encoding="utf-8"?>\n' + xmlstr)

print(f"Changed {changed} strings in strings.xml")
