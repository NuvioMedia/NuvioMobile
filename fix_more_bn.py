import xml.etree.ElementTree as ET

path = 'composeApp/src/commonMain/composeResources/values-bn/strings.xml'
parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
tree = ET.parse(path, parser)
root = tree.getroot()

fixes = {
    "updates_channel_title": "আপডেট চ্যানেল",
    "updates_channel_stable_description": "নিয়মিত ব্যবহারের জন্য উপযুক্ত।",
    "updates_channel_beta_description": "প্রারম্ভিক আপডেটে বাগ থাকতে পারে, কোনো ফিচার কাজ না-ও করতে পারে, অথবা অপ্রত্যাশিত আচরণ করতে পারে।",
    "compose_settings_page_advanced": "অ্যাডভান্সড",
    "settings_advanced_clear_cw_cache": "কন্টিনিউ ওয়াচিং ক্যাশে ক্লিয়ার করুন",
    "settings_advanced_clear_cw_cache_subtitle": "কন্টিনিউ ওয়াচিং ডেটা এবং দেখার প্রোগ্রেসের ক্যাশে ক্লিয়ার করুন",
    "settings_advanced_sentry_reports_subtitle": "সেফ কনটেক্সটসহ ক্র্যাশ এবং ANR রিপোর্ট পাঠান। ডিফল্টভাবে চালু।",
    "settings_card_depth_sheen_soft": "হালকা",
    "settings_card_depth_sheen": "টপ হাইলাইট",
    "settings_card_depth_description": "কার্ডের গভীরতা বোঝাতে ইমেজগুলোতে একটি উজ্জ্বল উপরের প্রান্ত এবং হালকা হাইলাইট যোগ করে।",
    "compose_settings_page_addons": "অ্যাডন",
    "addon_title": "অ্যাডন",
    "addons_overview_addons": "অ্যাডন",
    "compose_settings_page_streams": "স্ট্রিম",
    "settings_appearance_section_streams": "স্ট্রিম",
    "compose_settings_page_poster_customization": "পোস্টার স্টাইল",
    "settings_poster_card_style": "পোস্টার স্টাইল",
    "settings_continue_watching_section_card_style": "পোস্টার স্টাইল",
    "updates_channel_stable": "স্ট্যাবল",
    "settings_updates_check": "আপডেট চেক করুন"
}

changed = 0
for elem in root.iter():
    name = elem.attrib.get('name')
    if name in fixes:
        if elem.text != fixes[name]:
            elem.text = fixes[name]
            changed += 1

xmlstr = ET.tostring(root, encoding='utf-8').decode('utf-8')
with open(path, 'w', encoding='utf-8') as f:
    f.write('<?xml version="1.0" encoding="utf-8"?>\n' + xmlstr)

print(f"Changed {changed} strings.")
